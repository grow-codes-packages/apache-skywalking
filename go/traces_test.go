package skywalking

import (
	"context"
	"strings"
	"testing"
	"time"
)

const otlpTrace = "055ffba8ecc69a64950538fda698a308"
const nativeTraceID = "1fddf66a1c64db2c1345419627769f1f.1.1795036241775"

func TestIsOTelTraceID(t *testing.T) {
	for id, want := range map[string]bool{
		otlpTrace: true, "00f067aa0ba902b7": true, "055FFBA8ECC69A64950538FDA698A308": true,
		nativeTraceID: false, "0000000000000000": false, "xyz": false, "055ffba8ecc69a64950538fda698a30g": false,
	} {
		if IsOTelTraceID(id) != want {
			t.Errorf("%q", id)
		}
	}
}

func TestZipkinGetTrace(t *testing.T) {
	m := newMockOAP(t).route("GET /zipkin/api/v2/trace/"+otlpTrace, "getTrace.zipkin")
	tr, err := m.client().Traces().WithBackend(TraceBackendZipkin).GetTrace(t.Context(), strings.ToUpper(otlpTrace))
	if err != nil || tr == nil || tr.TraceID != otlpTrace || len(tr.Spans) != 2 {
		t.Fatal(tr, err)
	}
	root, child := tr.Spans[0], tr.Spans[1]
	if root.Name != "get /checkout" || root.Kind != SpanKindServer || root.ServiceName != "sw-otlp-1fddf66a" ||
		root.StartMicros != 1795036241725000 || root.DurationMicros != 50000 || root.EndMicros != 1795036241775000 ||
		root.Status.Code != SpanStatusOK || root.ParentSpanID != "" {
		t.Fatalf("%+v", root)
	}
	if root.Attributes["retries"] != "2" || root.Attributes["run_id"] != "1fddf66a" {
		t.Fatal(root.Attributes)
	}
	for _, k := range []string{"otel.status_code", "otel.library.name", "w3c.tracestate", "error"} {
		if _, ok := root.Attributes[k]; ok {
			t.Errorf("bookkeeping tag %s kept", k)
		}
	}
	if len(root.Events) != 1 || root.Events[0].Name != "cache.miss" || root.Events[0].Attributes["k"] != "v" || root.Events[0].TimeMicros != 1795036241735000 {
		t.Fatal(root.Events)
	}
	if child.ParentSpanID != root.SpanID || child.Kind != SpanKindClient || child.Status.Code != SpanStatusError || child.Status.Message != "boom" {
		t.Fatalf("%+v", child)
	}
}

func TestZipkinSpanMapping(t *testing.T) {
	s := ZipkinSpan{TraceID: "00f067aa0ba902b7", ID: "AB", Tags: map[string]string{"error": "", "otel.status_description": "x", "otel.custom": "y"},
		Annotations: []ZipkinAnnotation{{Timestamp: 1, Value: "plain"}, {Timestamp: 2, Value: "ev|not json|0"}, {Timestamp: 3, Value: "n|{\"i\":3,\"f\":1.5}|0"},
			{Timestamp: 4, Value: "e||0"}, {Timestamp: 5, Value: "|{}|0"}}}
	sp := s.ToSpan()
	if sp.TraceID != "000000000000000000f067aa0ba902b7" || sp.Kind != SpanKindInternal || sp.SpanID != "ab" || sp.Status != (SpanStatus{Code: SpanStatusError}) ||
		sp.ServiceName != "" || len(sp.Attributes) != 0 {
		t.Fatalf("%+v", sp)
	}
	if sp.Events[0].Name != "plain" || sp.Events[1].Name != "ev|not json|0" || len(sp.Events[1].Attributes) != 0 || sp.Events[2].Attributes["i"] != int64(3) ||
		sp.Events[2].Attributes["f"] != 1.5 || sp.Events[3].Name != "e" || sp.Events[4].Name != "|{}|0" {
		t.Fatal(sp.Events)
	}
	for kind, want := range map[string]SpanKind{"producer": SpanKindProducer, "CONSUMER": SpanKindConsumer, "weird": SpanKindUnspecified} {
		if got := (ZipkinSpan{TraceID: "1", ID: "1", Kind: kind, Tags: map[string]string{"otel.status_code": "ok"}}).ToSpan(); got.Kind != want || got.Status.Code != SpanStatusOK {
			t.Errorf("%s: %+v", kind, got)
		}
	}
	if got := (ZipkinSpan{TraceID: "1", ID: "1", Tags: map[string]string{"otel.status_code": "ERROR", "otel.status_description": "bad"}}).ToSpan(); got.Status.Message != "bad" {
		t.Fatal(got.Status)
	}
	if TraceFromZipkin(nil) != nil || TraceFromNative(NativeTrace{}) != nil {
		t.Fatal("empty traces must be nil")
	}
}

func TestNativeSpanMapping(t *testing.T) {
	m := newMockOAP(t).on("queryTrace", "getTrace.native")
	tr, err := m.client().Traces().WithBackend(TraceBackendNative).GetTrace(t.Context(), nativeTraceID)
	if err != nil || tr == nil || tr.TraceID != nativeTraceID || len(tr.Spans) != 3 {
		t.Fatal(tr, err)
	}
	byID := map[string]Span{}
	for _, s := range tr.Spans {
		byID[s.SpanID] = s
	}
	entry := byID["1fddf66a0691f3d5361838903180a743.1.1795036241775-0"]
	exit := byID["1fddf66a0691f3d5361838903180a743.1.1795036241775-1"]
	if entry.Kind != SpanKindServer || entry.ParentSpanID != "" || entry.Name != "/native/entry" || entry.StartMicros != 1795036241675000 ||
		entry.Attributes["sw.component"] != "Tomcat" || entry.Attributes["http.method"] != "GET" || entry.Attributes["sw.service_instance"] != "inst-a-1fddf66a" {
		t.Fatalf("%+v", entry)
	}
	if exit.Kind != SpanKindClient || exit.ParentSpanID != entry.SpanID || exit.Attributes["sw.peer"] != "sw-native-b-1fddf66a:8080" {
		t.Fatalf("%+v", exit)
	}
	var b Span
	for _, s := range tr.Spans {
		if s.ServiceName == "sw-native-b-1fddf66a" {
			b = s
		}
	}
	if b.ParentSpanID != exit.SpanID || b.Status.Code != SpanStatusError || len(b.Events) != 1 || b.Events[0].Name != "error" || b.Events[0].Attributes["message"] != "downstream failed" {
		t.Fatalf("%+v", b)
	}
	local := NativeSpan{TraceID: "t", SegmentID: "s", SpanID: 2, ParentSpanID: -1, Type: "Local", Logs: []NativeLogEntry{{Time: 5, Data: []KeyValue{{Key: "k", Value: "v"}}}}}.ToSpan()
	if local.Kind != SpanKindInternal || local.ParentSpanID != "" || local.Events[0].Name != "log" || local.Events[0].TimeMicros != 5000 {
		t.Fatalf("%+v", local)
	}
	refs := []NativeTraceRef{{TraceID: "t", ParentSegmentID: "p", ParentSpanID: 1, Type: "CROSS_PROCESS"}, {TraceID: "t", ParentSegmentID: "q", ParentSpanID: 2, Type: "CROSS_THREAD"}}
	odd := NativeSpan{TraceID: "t", SegmentID: "s", ParentSpanID: -1, Type: "Odd", StartTime: 10, EndTime: 5, Refs: refs}.ToSpan()
	if odd.Kind != SpanKindUnspecified || odd.ParentSpanID != "p-1" || len(odd.Links) != 1 || odd.Links[0].SpanID != "q-2" ||
		odd.Links[0].Attributes["sw.ref_type"] != "CROSS_THREAD" || odd.DurationMicros != 0 || odd.EndMicros != 10000 {
		t.Fatalf("%+v", odd)
	}
	if inner := (NativeSpan{SegmentID: "s", SpanID: 1, ParentSpanID: 0, Refs: refs[:1]}).ToSpan(); inner.ParentSpanID != "s-0" || len(inner.Links) != 1 {
		t.Fatalf("%+v", inner)
	}
	if got := TraceFromNative(NativeTrace{Spans: []NativeSpan{{TraceID: "x", SegmentID: "s"}}}); got.TraceID != "x" {
		t.Fatal(got)
	}
}

func TestGetTraceBackends(t *testing.T) {
	m := newMockOAP(t).
		route("GET /zipkin/api/v2/trace/"+otlpTrace, "getTrace.zipkin").
		route("GET /zipkin/api/v2/trace/0000000000000000000000000000beef", "getTrace.zipkin-404").
		on("queryTrace", "getTrace.native")
	tq := m.client().Traces()
	if tq.Backend() != TraceBackendAuto || tq.WithBackend("").Backend() != TraceBackendAuto {
		t.Fatal("default backend")
	}
	// auto: hex → zipkin
	tr, err := tq.GetTrace(t.Context(), otlpTrace)
	if err != nil || tr.Spans[0].ServiceName != "sw-otlp-1fddf66a" || m.count("queryTrace(") != 0 {
		t.Fatal(tr, err)
	}
	// auto: native id → GraphQL only
	tr, err = tq.GetTrace(t.Context(), nativeTraceID)
	if err != nil || tr == nil || tr.TraceID != nativeTraceID {
		t.Fatal(tr, err)
	}
	// auto: hex id missing in zipkin falls back to native (lower-cased)
	m.on("queryTrace", "getTrace.native-missing")
	tr, err = tq.GetTrace(t.Context(), "0000000000000000000000000000BEEF")
	if err != nil || tr != nil || m.count("queryTrace(") != 2 || m.last("queryTrace(").GQL.Variables["id"] != "0000000000000000000000000000beef" {
		t.Fatal(tr, err)
	}
	// auto: a zipkin failure is returned
	m.route("GET /zipkin/api/v2/trace/0000000000000000000000000000beef", jsonBody(502, "{}"))
	_, err = tq.GetTrace(t.Context(), "0000000000000000000000000000beef")
	mustCode(t, err, ErrorCodeUnavailable)
	// zipkin backend: the server's 400 for non-hex ids is VALIDATION with its text
	m.route("GET /zipkin/api/v2/trace/not-hex", "getTrace.zipkin-400")
	_, err = tq.WithBackend(TraceBackendZipkin).GetTrace(t.Context(), "not-hex")
	mustCode(t, err, ErrorCodeValidation)
	if !strings.Contains(err.Error(), "lower-hex") {
		t.Fatal(err)
	}
	_, err = tq.GetTrace(t.Context(), " ")
	mustCode(t, err, ErrorCodeValidation)
	_, err = tq.WithBackend("bogus").GetTrace(t.Context(), otlpTrace)
	mustCode(t, err, ErrorCodeValidation)
	ctx, cancel := context.WithCancel(t.Context())
	cancel()
	_, err = tq.GetTrace(ctx, otlpTrace)
	mustCode(t, err, ErrorCodeCancelled)
}

func TestWaitForTrace(t *testing.T) {
	m := newMockOAP(t).on("queryTrace", "getTrace.native").route("GET /zipkin/api/v2/trace/"+otlpTrace, "getTrace.zipkin")
	tq := m.client().Traces()
	tr, err := tq.WaitForTrace(t.Context(), nativeTraceID, WaitForTraceOptions{MinSpans: 3, Interval: time.Millisecond})
	if err != nil || len(tr.Spans) != 3 {
		t.Fatal(tr, err)
	}
	tr, err = tq.WaitForTrace(t.Context(), otlpTrace, WaitForTraceOptions{Interval: time.Millisecond})
	if err != nil || len(tr.Spans) != 2 {
		t.Fatal(tr, err)
	}
	_, err = tq.WaitForTrace(t.Context(), nativeTraceID, WaitForTraceOptions{MinSpans: 9, Timeout: 20 * time.Millisecond, Interval: 5 * time.Millisecond})
	mustCode(t, err, ErrorCodeTimeout)
	_, err = tq.WaitForTrace(t.Context(), "", WaitForTraceOptions{})
	mustCode(t, err, ErrorCodeValidation)
	m.on("queryTrace", "graphql.validation-error")
	_, err = tq.WaitForTrace(t.Context(), nativeTraceID, WaitForTraceOptions{})
	mustCode(t, err, ErrorCodeValidation)
}

func TestSearchTraces(t *testing.T) {
	m := newMockOAP(t).route("GET /zipkin/api/v2/traces", "searchTraces.by-service").on("queryTraces", "queryTraces.by-service")
	tq := m.client().Traces()
	sums, err := tq.SearchTraces(t.Context(), TraceSearchQuery{
		Service: "sw-otlp-1fddf66a", Operation: "SELECT orders", Tags: map[string]string{"run_id": "1fddf66a", "http.method": "GET"},
		MinDuration: 40 * time.Millisecond, MaxDuration: time.Second, Start: fixedNow.Add(-time.Minute), Limit: 5,
	})
	if err != nil || len(sums) != 2 || m.count("queryTraces") != 0 {
		t.Fatal(sums, err)
	}
	if sums[0].TraceID != "81ddcc24662938bf018aede480ded424" || sums[1].RootName != "get /checkout" || sums[1].SpanCount != 2 || sums[1].DurationMicros != 50000 {
		t.Fatalf("%+v", sums)
	}
	q := m.last("/zipkin/api/v2/traces").Query
	if q["spanName"][0] != "select orders" || q["annotationQuery"][0] != "http.method=GET and run_id=1fddf66a" || q["minDuration"][0] != "40000" ||
		q["maxDuration"][0] != "1000000" || q["lookback"][0] != "60000" || q["limit"][0] != "5" || q["endTs"][0] != "1795036500000" {
		t.Fatal(q)
	}
	if _, err := tq.WithBackend(TraceBackendZipkin).SearchTraces(t.Context(), TraceSearchQuery{}); err != nil {
		t.Fatal(err)
	}
	q = m.last("/zipkin/api/v2/traces").Query
	if q["lookback"] != nil || q["limit"][0] != "20" || q["serviceName"] != nil {
		t.Fatal(q)
	}
	nat, err := tq.WithBackend(TraceBackendNative).SearchTraces(t.Context(), TraceSearchQuery{Service: "sw-native-b-1fddf66a", Operation: "/native/downstream", Tags: map[string]string{"run_id": "x"}})
	if err != nil || len(nat) != 1 || nat[0].TraceID != nativeTraceID || nat[0].RootName != "/native/entry" || nat[0].RootServiceName != "sw-native-a-1fddf66a" {
		t.Fatalf("%+v %v", nat, err)
	}
	c := m.last("queryTraces").GQL.Variables["c"].(map[string]any)
	if c["endpointId"] == nil || c["tags"] == nil || c["queryDuration"].(map[string]any)["start"] != "2026-11-18 2015" {
		t.Fatal(c)
	}
	_, err = tq.WithBackend(TraceBackendNative).SearchTraces(t.Context(), TraceSearchQuery{Operation: "/nope"})
	mustCode(t, err, ErrorCodeValidation)
	m.on("queryTraces", jsonBody(500, "{}"))
	_, err = tq.WithBackend(TraceBackendNative).SearchTraces(t.Context(), TraceSearchQuery{})
	mustCode(t, err, ErrorCodeUnavailable)
	m.route("GET /zipkin/api/v2/traces", jsonBody(503, "{}"))
	_, err = tq.SearchTraces(t.Context(), TraceSearchQuery{})
	mustCode(t, err, ErrorCodeUnavailable)
	for _, bad := range []TraceSearchQuery{{MinDuration: -1}, {Limit: -1}, {Start: fixedNow.Add(time.Hour)}, {Tags: map[string]string{"a": "x and y"}}} {
		_, err := tq.SearchTraces(t.Context(), bad)
		mustCode(t, err, ErrorCodeValidation)
	}
	_, err = tq.WithBackend("x").SearchTraces(t.Context(), TraceSearchQuery{})
	mustCode(t, err, ErrorCodeValidation)
	if s := Summarize(Trace{TraceID: "x"}); s.SpanCount != 0 {
		t.Fatal(s)
	}
	// a parent outside the trace makes the span a root
	s := Summarize(Trace{TraceID: "x", Spans: []Span{{SpanID: "b", ParentSpanID: "missing", Name: "b", StartMicros: 2, EndMicros: 3}, {SpanID: "c", ParentSpanID: "b", StartMicros: 1, EndMicros: 9}}})
	if s.RootName != "b" || s.StartMicros != 1 || s.DurationMicros != 8 {
		t.Fatalf("%+v", s)
	}
}

func TestListServicesAndOperations(t *testing.T) {
	m := newMockOAP(t).route("GET /zipkin/api/v2/services", "listServices.zipkin").on("listServices", "listServices.all").
		route("GET /zipkin/api/v2/spans", "listOperations.zipkin").on("findEndpoint", "listEndpoints.ok")
	tq := m.client().Traces()
	svcs, err := tq.ListServices(t.Context())
	if err != nil || strings.Join(svcs, ",") != "sw-native-a-1fddf66a,sw-native-b-1fddf66a,sw-otlp-1fddf66a,sw-otlp-traceonly-1fddf66a" {
		t.Fatal(svcs, err)
	}
	svcs, _ = tq.WithBackend(TraceBackendZipkin).ListServices(t.Context())
	if len(svcs) != 2 {
		t.Fatal(svcs)
	}
	m.on("listServices", jsonBody(200, `{"data":{"listServices":[{"id":"a","name":"real","normal":true},{"id":"b","name":"virtual","normal":false}]}}`))
	svcs, _ = tq.WithBackend(TraceBackendNative).ListServices(t.Context())
	if len(svcs) != 1 || svcs[0] != "real" {
		t.Fatal(svcs)
	}
	ops, err := tq.ListOperations(t.Context(), "sw-otlp-1fddf66a")
	if err != nil || strings.Join(ops, ",") != "/native/entry,get /checkout,gzip-span,select orders" {
		t.Fatal(ops, err)
	}
	if m.last("/zipkin/api/v2/spans").Query["serviceName"][0] != "sw-otlp-1fddf66a" || m.last("findEndpoint").GQL.Variables["n"] != float64(DefaultEndpointLimit) {
		t.Fatal("operation requests")
	}
	ops, _ = tq.WithBackend(TraceBackendNative).ListOperations(t.Context(), "x")
	if len(ops) != 1 {
		t.Fatal(ops)
	}
	_, err = tq.ListOperations(t.Context(), "")
	mustCode(t, err, ErrorCodeValidation)
	_, err = tq.WithBackend("x").ListOperations(t.Context(), "svc")
	mustCode(t, err, ErrorCodeValidation)
	_, err = tq.WithBackend("x").ListServices(t.Context())
	mustCode(t, err, ErrorCodeValidation)
	m.on("listServices", jsonBody(500, "{}")).on("findEndpoint", jsonBody(500, "{}"))
	_, err = tq.ListServices(t.Context())
	mustCode(t, err, ErrorCodeUnavailable)
	_, err = tq.ListOperations(t.Context(), "svc")
	mustCode(t, err, ErrorCodeUnavailable)
	m.route("GET /zipkin/api/v2/services", jsonBody(500, "{}")).route("GET /zipkin/api/v2/spans", jsonBody(500, "{}"))
	_, err = tq.WithBackend(TraceBackendZipkin).ListServices(t.Context())
	mustCode(t, err, ErrorCodeUnavailable)
	_, err = tq.WithBackend(TraceBackendZipkin).ListOperations(t.Context(), "svc")
	mustCode(t, err, ErrorCodeUnavailable)
}

func TestGetDependencies(t *testing.T) {
	m := newMockOAP(t).on("getGlobalTopology", "topology.global")
	tq := m.client().Traces()
	_, err := tq.WithBackend(TraceBackendZipkin).GetDependencies(t.Context(), DependencyOptions{})
	mustCode(t, err, ErrorCodeUnsupported)
	links, err := tq.GetDependencies(t.Context(), DependencyOptions{Start: fixedNow.Add(-time.Hour)})
	if err != nil || len(links) == 0 {
		t.Fatal(links, err)
	}
	found := false
	for _, l := range links {
		if l.Parent == "sw-native-a-1fddf66a" && l.Child == "sw-native-b-1fddf66a" && l.CallCount == 0 {
			found = true
		}
	}
	if !found {
		t.Fatalf("%+v", links)
	}
	_, err = tq.GetDependencies(t.Context(), DependencyOptions{Start: fixedNow.Add(time.Hour)})
	mustCode(t, err, ErrorCodeValidation)
	_, err = tq.WithBackend("x").GetDependencies(t.Context(), DependencyOptions{})
	mustCode(t, err, ErrorCodeValidation)
	m.on("getGlobalTopology", jsonBody(500, "{}"))
	_, err = tq.GetDependencies(t.Context(), DependencyOptions{})
	mustCode(t, err, ErrorCodeUnavailable)
	deps := DependenciesFromTopology(Topology{Calls: []TopologyCall{{Source: "x", Target: "z"}, {Source: "x", Target: "y"}, {Source: "a", Target: "b"}}})
	if len(deps) != 3 || deps[0].Parent != "a" || deps[1].Child != "y" {
		t.Fatal(deps)
	}
}
