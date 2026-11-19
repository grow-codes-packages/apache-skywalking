package skywalking

import (
	"context"
	"strconv"
	"strings"

	sdktrace "go.opentelemetry.io/otel/sdk/trace"
)

// DefaultTracingProtocol is the export protocol when none is configured: OAP 10.4
// receives OTLP over gRPC only (port 11800).
const DefaultTracingProtocol = ProtocolGRPC

// DefaultOTLPEndpoint is the OTLP endpoint when neither the config nor the environment
// names one.
const DefaultOTLPEndpoint = "http://127.0.0.1:11800"

// AuthenticationHeader is the gRPC metadata key of OAP's token (SW_AUTHENTICATION).
const AuthenticationHeader = "authentication"

// DefaultPropagators are the propagators InitTracing uses when none are configured:
// W3C trace context and baggage plus sw8 (extract, inject only with native metadata;
// see SW8Propagator).
var DefaultPropagators = []string{PropagatorTraceContext, PropagatorBaggage, PropagatorSW8}

// InitTracing starts OpenTelemetry tracing that exports spans to SkyWalking OAP over
// OTLP gRPC. Unset fields of cfg are filled from the environment
// (TracingConfigFromEnvWith). OAP stores OTLP spans in its Zipkin model: read them
// back with Client.Traces (zipkin or auto backend; the server needs SW_QUERY_ZIPKIN).
// Shut the handle down to flush.
func InitTracing(ctx context.Context, cfg TracingConfig) (*TracingHandle, error) {
	resolved, err := TracingConfigFromEnvWith(cfg, nil)
	if err != nil {
		return nil, err
	}
	return InitTracingWith(ctx, resolved, NewExporter)
}

// InitTracing starts tracing with the client's OTLP settings (OTLPEndpoint,
// OTLPProtocol, OTLPHeaders, Authentication, TLS) for the fields cfg leaves unset.
func (c *Client) InitTracing(ctx context.Context, cfg TracingConfig) (*TracingHandle, error) {
	resolved, err := tracingFromConfig(cfg, c.cfg, nil)
	if err != nil {
		return nil, err
	}
	return InitTracingWith(ctx, resolved, NewExporter)
}

// NewExporter is the SkyWalking ExporterFactory: protocol grpc unless set, endpoint
// DefaultOTLPEndpoint unless set, then the official OTLP exporter (NewOTLPExporter).
// ProtocolNative is a CONFIG error (OAP has no native span exporter for OpenTelemetry),
// and ProtocolHTTPProtobuf needs an explicit endpoint (an OpenTelemetry Collector).
func NewExporter(ctx context.Context, cfg TracingConfig) (sdktrace.SpanExporter, error) {
	if cfg.Protocol == "" {
		cfg.Protocol = DefaultTracingProtocol
	}
	switch cfg.Protocol {
	case ProtocolNative:
		return nil, ConfigError("tracing: SkyWalking OAP receives OpenTelemetry spans over OTLP gRPC; protocol native is not supported")
	case ProtocolHTTPProtobuf:
		if cfg.Endpoint == "" {
			return nil, ConfigError("tracing: http/protobuf needs an explicit endpoint (an OpenTelemetry Collector); OAP serves OTLP over gRPC only")
		}
	}
	if cfg.Endpoint == "" {
		cfg.Endpoint = DefaultOTLPEndpoint
	}
	return NewOTLPExporter(ctx, cfg)
}

// TracingConfigFromEnvWith fills the unset fields of explicit from the environment
// (explicit > environment > default):
//
//   - Endpoint, Protocol, Headers and the authentication token: the client settings of
//     ConfigFromEnvWith (OTLP_ENDPOINT, OTEL_EXPORTER_OTLP_*, SW_AGENT_COLLECTOR_BACKEND_SERVICES,
//     AUTHENTICATION / SW_AUTHENTICATION; default http://<URL host>:11800, grpc).
//   - ServiceName: OTEL_SERVICE_NAME, else service.name in OTEL_RESOURCE_ATTRIBUTES (the
//     other entries fill unset ResourceAttributes), else SW_AGENT_NAME.
//   - Sampler / SamplerRatio: OTEL_TRACES_SAMPLER / OTEL_TRACES_SAMPLER_ARG.
//   - Propagators: OTEL_PROPAGATORS, default DefaultPropagators.
//
// env replaces the process environment when non-nil. Invalid values are CONFIG errors
// naming the variable, never its value.
func TracingConfigFromEnvWith(explicit TracingConfig, env map[string]string) (TracingConfig, error) {
	sw, err := ConfigFromEnvWith(Config{}, env)
	if err != nil {
		return TracingConfig{}, err
	}
	return tracingFromConfig(explicit, sw, env)
}

// tracingFromConfig applies the client OTLP settings and the OTEL_* variables.
func tracingFromConfig(explicit TracingConfig, sw Config, env map[string]string) (TracingConfig, error) {
	r := NewEnvReader(EnvPrefix, env)
	c := explicit
	c.Headers = copyStrings(c.Headers)
	if c.ResourceAttributes != nil {
		attrs := make(map[string]any, len(c.ResourceAttributes))
		for k, v := range c.ResourceAttributes {
			attrs[k] = v
		}
		c.ResourceAttributes = attrs
	}
	if c.Protocol == "" {
		c.Protocol = sw.OTLPProtocol
	}
	if c.Endpoint == "" {
		c.Endpoint = sw.OTLPEndpoint
	}
	if c.TLS == (TLSConfig{}) {
		c.TLS = sw.TLS
	}
	for k, v := range sw.OTLPHeaders {
		if !hasHeader(c.Headers, k) {
			c.Headers[k] = v
		}
	}
	if !sw.Authentication.IsZero() && !hasHeader(c.Headers, AuthenticationHeader) {
		c.Headers[AuthenticationHeader] = sw.Authentication.Reveal()
	}
	if len(c.Headers) == 0 {
		c.Headers = nil
	}
	if raw, variable, ok := r.find("", []string{"OTEL_RESOURCE_ATTRIBUTES"}); ok {
		attrs, err := ParseOTLPHeaders(raw)
		if err != nil {
			return TracingConfig{}, ConfigError(variable + " is not a valid attribute list")
		}
		for k, v := range attrs {
			if k == "service.name" {
				if c.ServiceName == "" {
					c.ServiceName = v
				}
				continue
			}
			if _, set := c.ResourceAttributes[k]; !set {
				if c.ResourceAttributes == nil {
					c.ResourceAttributes = map[string]any{}
				}
				c.ResourceAttributes[k] = v
			}
		}
	}
	if v, ok := r.String("", "OTEL_SERVICE_NAME"); ok && explicit.ServiceName == "" {
		c.ServiceName = strings.TrimSpace(v)
	}
	if c.ServiceName == "" {
		if v, ok := r.String("", "SW_AGENT_NAME"); ok {
			c.ServiceName = strings.TrimSpace(v)
		}
	}
	if c.Sampler == "" {
		if v, ok := r.String("", "OTEL_TRACES_SAMPLER"); ok {
			c.Sampler = Sampler(strings.ToLower(strings.TrimSpace(v)))
		}
	}
	if c.SamplerRatio == 0 {
		if v, variable, ok := r.find("", []string{"OTEL_TRACES_SAMPLER_ARG"}); ok {
			f, err := strconv.ParseFloat(strings.TrimSpace(v), 64)
			if err != nil || f < 0 || f > 1 {
				return TracingConfig{}, ConfigError(variable + " must be a number between 0 and 1")
			}
			c.SamplerRatio = f
		}
	}
	if len(c.Propagators) == 0 {
		if v, ok := r.String("", "OTEL_PROPAGATORS"); ok {
			for _, p := range strings.Split(v, ",") {
				if p = strings.ToLower(strings.TrimSpace(p)); p != "" && p != "none" {
					c.Propagators = append(c.Propagators, p)
				}
			}
		}
		if len(c.Propagators) == 0 {
			c.Propagators = append([]string(nil), DefaultPropagators...)
		}
	}
	return c, nil
}
