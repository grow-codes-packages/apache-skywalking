package skywalking

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"

	"go.opentelemetry.io/otel/propagation"
)

type sw8Vectors struct {
	SW8 struct {
		Header  string     `json:"header"`
		Decoded SW8Context `json:"decoded"`
		Invalid []string   `json:"invalid"`
	} `json:"sw8"`
	Correlation struct {
		Header  string     `json:"header"`
		Decoded [][]string `json:"decoded"`
	} `json:"sw8Correlation"`
}

func loadSW8Vectors(t *testing.T) sw8Vectors {
	t.Helper()
	raw, err := os.ReadFile(filepath.Join("testdata", "fixtures", "sw8.vectors.json"))
	if err != nil {
		t.Fatal(err)
	}
	var v sw8Vectors
	if err := json.Unmarshal(raw, &v); err != nil {
		t.Fatal(err)
	}
	return v
}

func TestSW8Vectors(t *testing.T) {
	v := loadSW8Vectors(t)
	got, err := ParseSW8(v.SW8.Header)
	if err != nil || got != v.SW8.Decoded {
		t.Fatalf("%+v %v", got, err)
	}
	if got.Encode() != v.SW8.Header {
		t.Fatal(got.Encode())
	}
	for _, bad := range append(v.SW8.Invalid, "1-YQ==-YQ==-1-YQ==-YQ==-YQ==-!!!", "1--YQ==-1-YQ==-YQ==-YQ==-YQ==") {
		if _, err := ParseSW8(bad); CodeOf(err) != ErrorCodeValidation {
			t.Errorf("%q: %v", bad, err)
		}
	}
	unsampled := SW8Context{TraceID: "t", ParentTraceSegmentID: "s"}
	back, err := ParseSW8(unsampled.Encode())
	if err != nil || back.Sample || back != unsampled {
		t.Fatal(back, err)
	}
	attrs := got.Attributes()
	if attrs["sw8.parent.span_id"] != int64(1) || attrs["sw8.parent.service"] != "sw-native-a-1fddf66a" || attrs["sw8.peer"] != "sw-native-b-1fddf66a:8080" {
		t.Fatal(attrs)
	}

	kv := ParseSW8Correlation(v.Correlation.Header)
	if len(kv) != 2 || kv[0] != (KeyValue{Key: "tenant", Value: "acme"}) || kv[1].Value != v.Correlation.Decoded[1][1] {
		t.Fatal(kv)
	}
	if EncodeSW8Correlation(kv) != v.Correlation.Header {
		t.Fatal(EncodeSW8Correlation(kv))
	}
	// malformed entries are skipped; a repeated key keeps the last value
	kv = ParseSW8Correlation("novalue,!!:YQ==,YQ==:!!,:YQ==,YQ==:Yg==:Yw==, YQ==:Yg== ,YQ==:Yw==, ,")
	if len(kv) != 1 || kv[0] != (KeyValue{Key: "a", Value: "c"}) {
		t.Fatal(kv)
	}
}

func TestSW8Propagator(t *testing.T) {
	v := loadSW8Vectors(t)
	p := SW8Propagator{}
	if len(p.Fields()) != 2 {
		t.Fatal(p.Fields())
	}
	// nothing to inject without native metadata
	carrier := propagation.MapCarrier{}
	p.Inject(context.Background(), carrier)
	if len(carrier) != 0 {
		t.Fatal(carrier)
	}
	// extract
	carrier = propagation.MapCarrier{SW8HeaderName: v.SW8.Header, SW8CorrelationHeaderName: v.Correlation.Header}
	ctx := p.Extract(context.Background(), carrier)
	sw, ok := SW8FromContext(ctx)
	if !ok || sw != v.SW8.Decoded {
		t.Fatal(sw, ok)
	}
	corr, ok := SW8CorrelationFromContext(ctx)
	if !ok || len(corr) != 2 {
		t.Fatal(corr)
	}
	// inject what the context carries
	out := propagation.MapCarrier{}
	p.Inject(ctx, out)
	if out[SW8HeaderName] != v.SW8.Header || out[SW8CorrelationHeaderName] != v.Correlation.Header {
		t.Fatal(out)
	}
	// invalid headers are ignored
	ctx = p.Extract(context.Background(), propagation.MapCarrier{SW8HeaderName: "1-abc", SW8CorrelationHeaderName: "bad"})
	if _, ok := SW8FromContext(ctx); ok {
		t.Fatal("invalid sw8 extracted")
	}
	if _, ok := SW8CorrelationFromContext(ctx); ok {
		t.Fatal("invalid correlation extracted")
	}
	// registered by name and part of the defaults, next to W3C
	if _, ok := lookupPropagator("SW8"); !ok {
		t.Fatal("sw8 not registered")
	}
	comp := buildPropagator(DefaultPropagators)
	ctx = ContextWithSW8(context.Background(), v.SW8.Decoded)
	ctx = ContextWithSW8Correlation(ctx, []KeyValue{{Key: "a", Value: "b"}})
	out = propagation.MapCarrier{}
	comp.Inject(ctx, out)
	if out[SW8HeaderName] != v.SW8.Header || out[SW8CorrelationHeaderName] == "" {
		t.Fatal(out)
	}
}
