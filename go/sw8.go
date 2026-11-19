package skywalking

import (
	"context"
	"encoding/base64"
	"strconv"
	"strings"

	"go.opentelemetry.io/otel/propagation"
)

// SkyWalking cross-process propagation (sw8 v3).
const (
	// PropagatorSW8 is the TracingConfig.Propagators name of SW8Propagator.
	PropagatorSW8 = "sw8"
	// SW8HeaderName carries the parent of a native SkyWalking span.
	SW8HeaderName = "sw8"
	// SW8CorrelationHeaderName carries SkyWalking correlation (baggage-like) values.
	SW8CorrelationHeaderName = "sw8-correlation"
)

func init() {
	RegisterPropagator(PropagatorSW8, func() propagation.TextMapPropagator { return SW8Propagator{} })
}

// SW8Context is a decoded sw8 header: the parent span of a native SkyWalking trace.
type SW8Context struct {
	// Sample is the sampling flag (header field 1: "1" or "0").
	Sample bool `json:"sample"`
	// TraceID is the native trace id ("<hex>.<n>.<ms>").
	TraceID string `json:"traceId"`
	// ParentTraceSegmentID is the segment of the parent span.
	ParentTraceSegmentID string `json:"parentTraceSegmentId"`
	// ParentSpanID is the parent span id within that segment.
	ParentSpanID int `json:"parentSpanId"`
	// ParentService, ParentServiceInstance and ParentEndpoint describe the caller.
	ParentService         string `json:"parentService"`
	ParentServiceInstance string `json:"parentServiceInstance"`
	ParentEndpoint        string `json:"parentEndpoint"`
	// NetworkAddressUsedAtPeer is the address the caller used to reach this service.
	NetworkAddressUsedAtPeer string `json:"networkAddressUsedAtPeer"`
}

func b64(s string) string { return base64.StdEncoding.EncodeToString([]byte(s)) }

func unb64(op, s string) (string, error) {
	b, err := base64.StdEncoding.DecodeString(s)
	if err != nil {
		return "", ValidationError(op, "field is not valid base64")
	}
	return string(b), nil
}

// ParseSW8 decodes an sw8 header: 8 fields separated by "-", the sample flag, the
// parent span id as an integer and the other fields standard base64 with padding.
// Anything else is a VALIDATION error.
func ParseSW8(header string) (SW8Context, error) {
	const op = "sw8.parse"
	parts := strings.Split(strings.TrimSpace(header), "-")
	if len(parts) != 8 {
		return SW8Context{}, ValidationError(op, "sw8 must have 8 fields")
	}
	var c SW8Context
	switch parts[0] {
	case "1":
		c.Sample = true
	case "0":
	default:
		return SW8Context{}, ValidationError(op, "sample flag must be 0 or 1")
	}
	span, err := strconv.Atoi(parts[3])
	if err != nil {
		return SW8Context{}, ValidationError(op, "parent span id must be an integer")
	}
	c.ParentSpanID = span
	fields := map[int]*string{1: &c.TraceID, 2: &c.ParentTraceSegmentID, 4: &c.ParentService, 5: &c.ParentServiceInstance, 6: &c.ParentEndpoint, 7: &c.NetworkAddressUsedAtPeer}
	for i := 1; i < 8; i++ {
		dst, ok := fields[i]
		if !ok {
			continue
		}
		v, err := unb64(op, parts[i])
		if err != nil {
			return SW8Context{}, err
		}
		*dst = v
	}
	if c.TraceID == "" || c.ParentTraceSegmentID == "" {
		return SW8Context{}, ValidationError(op, "trace id and parent segment id are required")
	}
	return c, nil
}

// Encode renders the sw8 header value.
func (c SW8Context) Encode() string {
	sample := "0"
	if c.Sample {
		sample = "1"
	}
	return strings.Join([]string{
		sample, b64(c.TraceID), b64(c.ParentTraceSegmentID), strconv.Itoa(c.ParentSpanID),
		b64(c.ParentService), b64(c.ParentServiceInstance), b64(c.ParentEndpoint), b64(c.NetworkAddressUsedAtPeer),
	}, "-")
}

// Attributes returns the parent as span/log attributes (sw8.parent.*), for example to
// record an incoming native parent on an OpenTelemetry server span.
func (c SW8Context) Attributes() map[string]any {
	return map[string]any{
		"sw8.parent.trace_id":         c.TraceID,
		"sw8.parent.segment_id":       c.ParentTraceSegmentID,
		"sw8.parent.span_id":          int64(c.ParentSpanID),
		"sw8.parent.service":          c.ParentService,
		"sw8.parent.service_instance": c.ParentServiceInstance,
		"sw8.parent.endpoint":         c.ParentEndpoint,
		"sw8.peer":                    c.NetworkAddressUsedAtPeer,
	}
}

// ParseSW8Correlation decodes an sw8-correlation header ("b64(key):b64(value),…") in
// header order. Malformed entries (no single ":", invalid base64, empty key) are
// skipped; a repeated key keeps its first position and its last value.
func ParseSW8Correlation(header string) []KeyValue {
	out := []KeyValue{}
	index := map[string]int{}
	for _, item := range strings.Split(header, ",") {
		parts := strings.Split(strings.TrimSpace(item), ":")
		if len(parts) != 2 {
			continue
		}
		key, err1 := unb64("", parts[0])
		val, err2 := unb64("", parts[1])
		if err1 != nil || err2 != nil || key == "" {
			continue
		}
		if i, dup := index[key]; dup {
			out[i].Value = val
			continue
		}
		index[key] = len(out)
		out = append(out, KeyValue{Key: key, Value: val})
	}
	return out
}

// EncodeSW8Correlation renders an sw8-correlation header value in the given order.
// SkyWalking agents keep at most 3 keys with values up to 128 bytes by default.
func EncodeSW8Correlation(values []KeyValue) string {
	parts := make([]string, len(values))
	for i, kv := range values {
		parts[i] = b64(kv.Key) + ":" + b64(kv.Value)
	}
	return strings.Join(parts, ",")
}

type sw8Key struct{}

type sw8CorrelationKey struct{}

// ContextWithSW8 returns ctx carrying an sw8 parent; SW8Propagator injects it.
func ContextWithSW8(ctx context.Context, c SW8Context) context.Context {
	return context.WithValue(ctx, sw8Key{}, c)
}

// SW8FromContext returns the sw8 parent stored by ContextWithSW8 or extracted by
// SW8Propagator.
func SW8FromContext(ctx context.Context) (SW8Context, bool) {
	c, ok := ctx.Value(sw8Key{}).(SW8Context)
	return c, ok
}

// ContextWithSW8Correlation returns ctx carrying sw8-correlation values.
func ContextWithSW8Correlation(ctx context.Context, values []KeyValue) context.Context {
	return context.WithValue(ctx, sw8CorrelationKey{}, append([]KeyValue(nil), values...))
}

// SW8CorrelationFromContext returns the correlation values stored in ctx.
func SW8CorrelationFromContext(ctx context.Context) ([]KeyValue, bool) {
	v, ok := ctx.Value(sw8CorrelationKey{}).([]KeyValue)
	return v, ok
}

// SW8Propagator is an OpenTelemetry TextMapPropagator for SkyWalking's sw8 and
// sw8-correlation headers, used next to W3C trace context (TracingConfig.Propagators
// "sw8"; part of DefaultPropagators).
//
// An OpenTelemetry SDK cannot produce a native sw8 parent from W3C context (sw8 needs
// a segment id, an integer span id and the endpoint), so the propagator extracts by
// default and injects only what the caller supplied: Extract stores a valid sw8 header
// in the context (SW8FromContext) and the correlation values (SW8CorrelationFromContext)
// while the W3C header stays the OpenTelemetry parent; Inject writes sw8 only when the
// context carries native metadata (ContextWithSW8) and sw8-correlation when it carries
// correlation values. For full native traces use the official SkyWalking agents.
type SW8Propagator struct{}

var _ propagation.TextMapPropagator = SW8Propagator{}

// Inject writes sw8 / sw8-correlation from ctx (nothing when ctx has no native metadata).
func (SW8Propagator) Inject(ctx context.Context, carrier propagation.TextMapCarrier) {
	if c, ok := SW8FromContext(ctx); ok {
		carrier.Set(SW8HeaderName, c.Encode())
	}
	if v, ok := SW8CorrelationFromContext(ctx); ok && len(v) > 0 {
		carrier.Set(SW8CorrelationHeaderName, EncodeSW8Correlation(v))
	}
}

// Extract reads sw8 / sw8-correlation into ctx; invalid headers are ignored.
func (SW8Propagator) Extract(ctx context.Context, carrier propagation.TextMapCarrier) context.Context {
	if h := carrier.Get(SW8HeaderName); h != "" {
		if c, err := ParseSW8(h); err == nil {
			ctx = ContextWithSW8(ctx, c)
		}
	}
	if h := carrier.Get(SW8CorrelationHeaderName); h != "" {
		if v := ParseSW8Correlation(h); len(v) > 0 {
			ctx = ContextWithSW8Correlation(ctx, v)
		}
	}
	return ctx
}

// Fields returns the header names this propagator uses.
func (SW8Propagator) Fields() []string {
	return []string{SW8HeaderName, SW8CorrelationHeaderName}
}
