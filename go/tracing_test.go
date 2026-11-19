package skywalking

import (
	"strings"
	"testing"
)

func TestTracingConfigFromEnv(t *testing.T) {
	env := map[string]string{
		"GROW_APACHE_SKYWALKING_URL": "http://oap:12800",
		"SW_AUTHENTICATION":          "sw-tok",
		"OTEL_EXPORTER_OTLP_HEADERS": "x-a=1",
		"OTEL_RESOURCE_ATTRIBUTES":   "service.name=from-res,team=core",
		"OTEL_TRACES_SAMPLER":        "TraceIDRatio",
		"OTEL_TRACES_SAMPLER_ARG":    "0.5",
		"OTEL_PROPAGATORS":           "tracecontext, B3 ,none",
	}
	c, err := TracingConfigFromEnvWith(TracingConfig{ResourceAttributes: map[string]any{"team": "explicit"}}, env)
	if err != nil {
		t.Fatal(err)
	}
	if c.Endpoint != "http://oap:11800" || c.Protocol != ProtocolGRPC || c.Headers[AuthenticationHeader] != "sw-tok" || c.Headers["x-a"] != "1" ||
		c.ServiceName != "from-res" || c.ResourceAttributes["team"] != "explicit" || c.Sampler != SamplerTraceIDRatio || c.SamplerRatio != 0.5 ||
		strings.Join(c.Propagators, ",") != "tracecontext,b3" {
		t.Fatalf("%s %v", c, c.ResourceAttributes)
	}
	if strings.Contains(c.String(), "sw-tok") {
		t.Fatal("token leaked")
	}
	env["OTEL_SERVICE_NAME"] = "from-otel"
	delete(env, "OTEL_PROPAGATORS")
	c, _ = TracingConfigFromEnvWith(TracingConfig{Headers: map[string]string{"Authentication": "explicit"}}, env)
	if c.ServiceName != "from-otel" || c.Headers["Authentication"] != "explicit" || len(c.Propagators) != 2 || c.Propagators[1] != PropagatorBaggage || c.ResourceAttributes["team"] != "core" {
		t.Fatalf("%s", c)
	}
	c, _ = TracingConfigFromEnvWith(TracingConfig{ServiceName: "explicit"}, map[string]string{"SW_AGENT_NAME": "agent", "OTEL_SERVICE_NAME": "otel"})
	if c.ServiceName != "explicit" || c.Headers != nil {
		t.Fatal(c.ServiceName, c.Headers)
	}
	c, _ = TracingConfigFromEnvWith(TracingConfig{}, map[string]string{"SW_AGENT_NAME": "agent"})
	if c.ServiceName != "" {
		t.Fatal(c.ServiceName)
	}
	for _, bad := range []map[string]string{
		{"OTEL_TRACES_SAMPLER_ARG": "2"},
		{"OTEL_RESOURCE_ATTRIBUTES": "novalue"},
		{"GROW_APACHE_SKYWALKING_URL": "ftp://x"},
	} {
		_, err := TracingConfigFromEnvWith(TracingConfig{}, bad)
		mustCode(t, err, ErrorCodeConfig)
	}
}

func TestNewExporterRules(t *testing.T) {
	ctx := t.Context()
	_, err := NewExporter(ctx, TracingConfig{ServiceName: "s", Protocol: ProtocolNative})
	mustCode(t, err, ErrorCodeConfig)
	_, err = NewExporter(ctx, TracingConfig{ServiceName: "s", Protocol: ProtocolHTTPProtobuf})
	mustCode(t, err, ErrorCodeConfig)
	exp, err := NewExporter(ctx, TracingConfig{ServiceName: "s"})
	if err != nil {
		t.Fatal(err)
	}
	_ = exp.Shutdown(ctx)
	exp, err = NewExporter(ctx, TracingConfig{ServiceName: "s", Protocol: ProtocolHTTPProtobuf, Endpoint: "http://collector:4318"})
	if err != nil {
		t.Fatal(err)
	}
	_ = exp.Shutdown(ctx)
}

func TestInitTracingExportsOverGRPC(t *testing.T) {
	f := newFakeOTLP(t)
	m := newMockOAP(t)
	c := m.client(func(cfg *Config) { cfg.OTLPEndpoint = f.addr; cfg.Authentication = NewSecret("grpc-secret") })
	h, err := c.InitTracing(t.Context(), TracingConfig{ServiceName: "svc-go", DisableSetGlobal: true})
	if err != nil {
		t.Fatal(err)
	}
	_, span := h.Tracer("test").Start(t.Context(), "GET /checkout")
	span.End()
	if err := h.ForceFlush(t.Context()); err != nil {
		t.Fatal(err)
	}
	if len(f.traces) != 1 || f.lastMD().Get("authentication")[0] != "grpc-secret" {
		t.Fatal(f.traces, f.lastMD())
	}
	rs := f.traces[0].ResourceSpans[0]
	if rs.ScopeSpans[0].Spans[0].Name != "GET /checkout" {
		t.Fatal(rs)
	}
	if strings.Join(h.Config().Propagators, ",") != "tracecontext,baggage" {
		t.Fatal(h.Config().Propagators)
	}
	if err := h.Shutdown(t.Context()); err != nil {
		t.Fatal(err)
	}

	t.Setenv("GROW_APACHE_SKYWALKING_OTLP_ENDPOINT", f.addr)
	h, err = InitTracing(t.Context(), TracingConfig{ServiceName: "svc-env", DisableSetGlobal: true, Compression: CompressionNone})
	if err != nil {
		t.Fatal(err)
	}
	_ = h.Shutdown(t.Context())
	t.Setenv("OTEL_TRACES_SAMPLER_ARG", "x")
	_, err = InitTracing(t.Context(), TracingConfig{ServiceName: "svc-env"})
	mustCode(t, err, ErrorCodeConfig)
	_, err = c.InitTracing(t.Context(), TracingConfig{ServiceName: "svc"})
	mustCode(t, err, ErrorCodeConfig)
}
