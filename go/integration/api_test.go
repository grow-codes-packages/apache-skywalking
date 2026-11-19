package integration

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"os"
	"slices"
	"strings"
	"testing"
	"time"

	"go.opentelemetry.io/otel/codes"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/trace"

	skywalking "github.com/grow-codes-packages/apache-skywalking/go"
)

// Polling budgets (measured on OAP 10.4 + BanyanDB: traces/logs a few seconds, Zipkin
// ≤ 15 s, metadata ~35 s, topology ~40 s).
const (
	dataTimeout     = 60 * time.Second
	metadataTimeout = 120 * time.Second
	poll            = time.Second
)

func randHex(n int) string {
	b := make([]byte, n)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

// until polls fn until it reports done or the timeout passes.
func until[T any](t *testing.T, what string, timeout time.Duration, fn func(ctx context.Context) (T, bool, error)) T {
	t.Helper()
	v, err := skywalking.PollUntil(t.Context(), what, timeout, poll, fn)
	if err != nil {
		t.Fatalf("%s: %v", what, err)
	}
	return v
}

// seed posts native SkyWalking segments A (entry + exit) → B (entry). B's parent ref is
// built by decoding the sw8 header that A's exit span would send.
type seed struct {
	run, traceID, segA, segB, svcA, svcB, instA, instB, peer string
	sw8                                                      string
}

func newSeed() seed {
	run := randHex(4)
	now := time.Now().UnixMilli()
	s := seed{
		run: run, traceID: fmt.Sprintf("%s%s.1.%d", run, randHex(12), now),
		segA: fmt.Sprintf("%s%s.1.%d", run, randHex(12), now), segB: fmt.Sprintf("%s%s.2.%d", run, randHex(12), now),
		svcA: "go-native-a-" + run, svcB: "go-native-b-" + run, instA: "inst-a-" + run, instB: "inst-b-" + run,
	}
	s.peer = s.svcB + ":8080"
	s.sw8 = skywalking.SW8Context{
		Sample: true, TraceID: s.traceID, ParentTraceSegmentID: s.segA, ParentSpanID: 1, ParentService: s.svcA,
		ParentServiceInstance: s.instA, ParentEndpoint: "/native/entry", NetworkAddressUsedAtPeer: s.peer,
	}.Encode()
	return s
}

func (s seed) post(t *testing.T, baseURL string) {
	t.Helper()
	// B's side: an incoming request carries sw8; the propagator extracts the parent.
	ctx := skywalking.SW8Propagator{}.Extract(context.Background(), propagation.MapCarrier{"sw8": s.sw8})
	parent, ok := skywalking.SW8FromContext(ctx)
	if !ok {
		t.Fatal("sw8 not extracted")
	}
	now := time.Now().UnixMilli()
	tags := func(kv ...string) []map[string]string {
		out := []map[string]string{}
		for i := 0; i < len(kv); i += 2 {
			out = append(out, map[string]string{"key": kv[i], "value": kv[i+1]})
		}
		return out
	}
	segments := []map[string]any{
		{"traceId": s.traceID, "traceSegmentId": s.segA, "service": s.svcA, "serviceInstance": s.instA, "spans": []map[string]any{
			{"spanId": 1, "parentSpanId": 0, "startTime": now - 80, "endTime": now - 20, "operationName": "/native/downstream", "peer": s.peer,
				"spanType": "Exit", "spanLayer": "Http", "componentId": 2, "isError": false, "tags": tags("http.method", "GET")},
			{"spanId": 0, "parentSpanId": -1, "startTime": now - 100, "endTime": now, "operationName": "/native/entry", "peer": "",
				"spanType": "Entry", "spanLayer": "Http", "componentId": 1, "isError": false, "tags": tags("http.method", "GET", "run_id", s.run)},
		}},
		{"traceId": parent.TraceID, "traceSegmentId": s.segB, "service": s.svcB, "serviceInstance": s.instB, "spans": []map[string]any{
			{"spanId": 0, "parentSpanId": -1, "startTime": now - 70, "endTime": now - 30, "operationName": "/native/downstream", "peer": "",
				"spanType": "Entry", "spanLayer": "Http", "componentId": 1, "isError": true, "tags": tags("http.method", "GET", "http.status_code", "500"),
				"refs": []map[string]any{{"refType": "CrossProcess", "traceId": parent.TraceID, "parentTraceSegmentId": parent.ParentTraceSegmentID,
					"parentSpanId": parent.ParentSpanID, "parentService": parent.ParentService, "parentServiceInstance": parent.ParentServiceInstance,
					"parentEndpoint": parent.ParentEndpoint, "networkAddressUsedAtPeer": parent.NetworkAddressUsedAtPeer}},
				"logs": []map[string]any{{"time": now - 40, "data": tags("event", "error", "message", "downstream failed")}}},
		}},
	}
	body, _ := json.Marshal(segments)
	res, err := http.Post(strings.TrimRight(baseURL, "/")+"/v3/segments", "application/json", bytes.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	_ = res.Body.Close()
	if res.StatusCode != http.StatusOK {
		t.Fatalf("seed segments: HTTP %d", res.StatusCode)
	}
}

func TestAPI(t *testing.T) {
	bootstrap := os.Getenv("GROW_BOOTSTRAP_ENV")
	if bootstrap == "" {
		bootstrap = "/bootstrap/env"
	}
	loadBootstrapEnv(t, bootstrap)
	if os.Getenv("GROW_APACHE_SKYWALKING_URL") == "" {
		t.Skip("GROW_APACHE_SKYWALKING_URL is not set; run through docker/compose.yml")
	}
	client, err := skywalking.NewClientFromEnv()
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = client.Close() }()
	ctx := t.Context()
	q := client.Query()
	s := newSeed()
	s.post(t, client.Config().URL)
	otlpSvc := "go-otlp-" + s.run
	logSvc := "go-logs-" + s.run

	// --- emit OTLP spans (package-level InitTracing from the environment)
	h, err := skywalking.InitTracing(ctx, skywalking.TracingConfig{ServiceName: otlpSvc, DisableSetGlobal: true})
	if err != nil {
		t.Fatal(err)
	}
	tracer := h.Tracer("integration")
	sctx, root := tracer.Start(ctx, "GET /Checkout", trace.WithSpanKind(trace.SpanKindServer))
	root.SetAttributes(skywalking.OTelAttributes(map[string]any{"run_id": s.run})...)
	root.AddEvent("cache.miss")
	_, child := tracer.Start(sctx, "select orders", trace.WithSpanKind(trace.SpanKindClient))
	child.SetStatus(codes.Error, "boom")
	child.End()
	root.End()
	otlpTrace := root.SpanContext().TraceID().String()
	if err := h.ForceFlush(ctx); err != nil {
		t.Fatal(err)
	}
	_ = h.Shutdown(ctx)

	// --- emit logs: native exporter (with an OTel trace id) and OTLP exporter
	lc, err := client.NewLogClient(skywalking.LogClientOptions{Resource: map[string]skywalking.AttrValue{"service.name": logSvc, "service.instance.id": "inst-" + s.run}})
	if err != nil {
		t.Fatal(err)
	}
	lc.Send(ctx, skywalking.LogInput{Message: "native order " + s.run, Severity: skywalking.SeverityWarn, TraceID: otlpTrace, SpanID: "00f067aa0ba902b7",
		Attributes: map[string]skywalking.AttrValue{"run_id": s.run, "password": "hunter2-" + s.run}})
	lc.Logger("api", nil).Error(ctx, "logger facade "+s.run, "run_id", s.run)
	if err := lc.Flush(ctx); err != nil {
		t.Fatal(err)
	}
	otlpExp, err := client.NewOTLPLogExporter()
	if err != nil {
		t.Fatal(err)
	}
	res, err := otlpExp.Export(ctx, []skywalking.LogRecord{{Timestamp: time.Now(), Severity: skywalking.SeverityInfo, Body: "otlp log " + s.run, TraceID: otlpTrace,
		Attributes: map[string]skywalking.AttrValue{"run_id": s.run}, Resource: map[string]skywalking.AttrValue{"service.name": logSvc}}}, skywalking.ExportOptions{Gzip: true})
	if err != nil || res.Accepted != 1 {
		t.Fatal(res, err)
	}
	_ = otlpExp.Shutdown(ctx)
	native := client.NewLogExporter(skywalking.LogExporterOptions{Service: s.svcA, ServiceInstance: s.instA})
	if _, err := native.Export(ctx, []skywalking.LogRecord{{Timestamp: time.Now(), Body: "segment log " + s.run, TraceID: s.traceID, SeverityText: "INFO"}}, skywalking.ExportOptions{}); err != nil {
		t.Fatal(err)
	}

	t.Run("core", func(t *testing.T) {
		hs, err := client.Health(ctx)
		if err != nil || !hs.Healthy {
			t.Fatal(hs, err)
		}
		if _, err := client.Ping(ctx); err != nil {
			t.Fatal(err)
		}
		v, err := client.ServerVersion(ctx)
		if err != nil || (os.Getenv("SW_SERVER_VERSION") != "" && v != os.Getenv("SW_SERVER_VERSION")) {
			t.Fatal(v, err)
		}
		ti, err := client.TimeInfo(ctx)
		if err != nil || ti.Timezone != os.Getenv("SW_SERVER_TIMEZONE") || time.Since(ti.CurrentTime).Abs() > time.Hour {
			t.Fatal(ti, err)
		}
		caps, err := client.Capabilities(ctx)
		if err != nil || !caps.QueryTracesV2 {
			t.Fatal(caps, err)
		}
		raw, err := client.GraphQL(ctx, "{version}", nil)
		if err != nil || raw.Err("graphql") != nil {
			t.Fatal(raw, err)
		}
		raw, err = q.GraphQL(ctx, "query($n:String!){findService(serviceName:$n){id}}", map[string]any{"n": "nope-" + s.run})
		if err != nil || string(raw.Data) != `{"findService":null}` {
			t.Fatal(string(raw.Data), err)
		}
		_, err = q.GraphQL(ctx, "{nope}", nil)
		if !errors.Is(err, skywalking.ErrValidation) {
			t.Fatal(err)
		}
	})

	t.Run("native traces", func(t *testing.T) {
		tr := until(t, "getTrace", dataTimeout, func(ctx context.Context) (*skywalking.NativeTrace, bool, error) {
			tr, err := q.GetTrace(ctx, s.traceID)
			return tr, tr != nil && len(tr.Spans) == 3, err
		})
		for _, sp := range tr.Spans {
			if sp.ServiceCode == s.svcB && (len(sp.Refs) != 1 || sp.Refs[0].ParentSegmentID != s.segA || sp.Refs[0].ParentSpanID != 1 || !sp.IsError) {
				t.Fatalf("sw8 parent not linked: %+v", sp)
			}
		}
		page, err := q.QueryTraces(ctx, skywalking.QueryTracesOptions{Service: s.svcA, Tags: map[string]string{"run_id": s.run}})
		if err != nil || len(page.Traces) != 1 || page.Traces[0].TraceID != s.traceID {
			t.Fatal(page, err)
		}
		page, err = q.QueryTraces(ctx, skywalking.QueryTracesOptions{Service: s.svcB, TraceState: skywalking.TraceStateError})
		if err != nil || len(page.Traces) != 1 {
			t.Fatal(page, err)
		}
		nt := client.Traces().WithBackend(skywalking.TraceBackendNative)
		norm, err := nt.WaitForTrace(ctx, s.traceID, skywalking.WaitForTraceOptions{MinSpans: 3})
		if err != nil {
			t.Fatal(err)
		}
		tree := norm.Tree()
		if len(tree) != 1 || tree[0].Span.Name != "/native/entry" || len(tree[0].Children) != 1 || len(tree[0].Children[0].Children) != 1 {
			t.Fatalf("native tree: %+v", tree)
		}
		auto, err := client.Traces().GetTrace(ctx, s.traceID)
		if err != nil || auto == nil || len(auto.Spans) != 3 {
			t.Fatal(auto, err)
		}
		sums, err := nt.SearchTraces(ctx, skywalking.TraceSearchQuery{Service: s.svcA, Tags: map[string]string{"run_id": s.run}})
		if err != nil || len(sums) != 1 || sums[0].RootName != "/native/entry" || sums[0].SpanCount != 3 {
			t.Fatal(sums, err)
		}
	})

	t.Run("metadata", func(t *testing.T) {
		until(t, "listServices", metadataTimeout, func(ctx context.Context) ([]skywalking.Service, bool, error) {
			svcs, err := q.ListServices(ctx, "")
			names := map[string]bool{}
			for _, sv := range svcs {
				names[sv.Name] = true
			}
			return svcs, names[s.svcA] && names[s.svcB], err
		})
		general, err := q.ListServices(ctx, "GENERAL")
		if err != nil || len(general) == 0 {
			t.Fatal(general, err)
		}
		svc, err := q.FindService(ctx, s.svcA)
		if err != nil || svc == nil || svc.ID != skywalking.ServiceID(s.svcA) {
			t.Fatal(svc, err)
		}
		eps := until(t, "listEndpoints", metadataTimeout, func(ctx context.Context) ([]skywalking.Endpoint, bool, error) {
			eps, err := q.ListEndpoints(ctx, s.svcB, "downstream", 10)
			return eps, len(eps) == 1, err
		})
		if eps[0].Name != "/native/downstream" || eps[0].ID != skywalking.EndpointID(s.svcB, "/native/downstream") {
			t.Fatal(eps)
		}
		ins := until(t, "listInstances", metadataTimeout, func(ctx context.Context) ([]skywalking.Instance, bool, error) {
			ins, err := q.ListInstances(ctx, s.svcA, skywalking.TimeRange{})
			return ins, len(ins) == 1, err
		})
		if ins[0].Name != s.instA {
			t.Fatal(ins)
		}
		layers, err := q.ListLayers(ctx)
		if err != nil || !slices.Contains(layers, "GENERAL") {
			t.Fatal(layers, err)
		}
		keys, err := q.ListTraceTagKeys(ctx, skywalking.TimeRange{})
		if err != nil || !slices.Contains(keys, "http.method") {
			t.Fatal(keys, err)
		}
		ops, err := client.Traces().WithBackend(skywalking.TraceBackendNative).ListOperations(ctx, s.svcA)
		if err != nil || !slices.Contains(ops, "/native/entry") {
			t.Fatal(ops, err)
		}
		svcs, err := client.Traces().WithBackend(skywalking.TraceBackendNative).ListServices(ctx)
		if err != nil || !slices.Contains(svcs, s.svcB) {
			t.Fatal(svcs, err)
		}
	})

	t.Run("topology", func(t *testing.T) {
		until(t, "topology", metadataTimeout, func(ctx context.Context) (skywalking.Topology, bool, error) {
			topo, err := q.Topology(ctx, skywalking.TimeRange{}, skywalking.TopologyOptions{})
			for _, c := range topo.Calls {
				if topo.NodeName(c.Source) == s.svcA && topo.NodeName(c.Target) == s.svcB {
					return topo, true, nil
				}
			}
			return topo, false, err
		})
		st, err := q.Topology(ctx, skywalking.TimeRange{}, skywalking.TopologyOptions{Service: s.svcB})
		if err != nil || len(st.Calls) == 0 {
			t.Fatal(st, err)
		}
		links, err := client.Traces().GetDependencies(ctx, skywalking.DependencyOptions{})
		if err != nil || !slices.Contains(links, skywalking.DependencyLink{Parent: s.svcA, Child: s.svcB}) {
			t.Fatal(links, err)
		}
	})

	t.Run("otlp traces", func(t *testing.T) {
		zt := client.Traces().WithBackend(skywalking.TraceBackendZipkin)
		tr, err := zt.WaitForTrace(ctx, otlpTrace, skywalking.WaitForTraceOptions{MinSpans: 2, Timeout: dataTimeout})
		if err != nil {
			t.Fatal(err)
		}
		var rootSpan, childSpan skywalking.Span
		for _, sp := range tr.Spans {
			if sp.ParentSpanID == "" {
				rootSpan = sp
			} else {
				childSpan = sp
			}
		}
		if rootSpan.Name != "get /checkout" || rootSpan.Kind != skywalking.SpanKindServer || rootSpan.Attributes["run_id"] != s.run ||
			len(rootSpan.Events) != 1 || rootSpan.Events[0].Name != "cache.miss" || rootSpan.ServiceName != otlpSvc {
			t.Fatalf("%+v", rootSpan)
		}
		if childSpan.ParentSpanID != rootSpan.SpanID || childSpan.Status.Code != skywalking.SpanStatusError || childSpan.Status.Message != "boom" {
			t.Fatalf("%+v", childSpan)
		}
		got, err := client.Traces().GetTrace(ctx, otlpTrace)
		if err != nil || got == nil || len(got.Spans) != 2 {
			t.Fatal(got, err)
		}
		sums, err := client.Traces().SearchTraces(ctx, skywalking.TraceSearchQuery{Service: otlpSvc, Tags: map[string]string{"run_id": s.run}})
		if err != nil || len(sums) != 1 || sums[0].TraceID != otlpTrace || sums[0].SpanCount != 2 {
			t.Fatal(sums, err)
		}
		sums, err = zt.SearchTraces(ctx, skywalking.TraceSearchQuery{Service: otlpSvc, Operation: "SELECT orders"})
		if err != nil || len(sums) != 1 {
			t.Fatal(sums, err)
		}
		until(t, "zipkin listServices", metadataTimeout, func(ctx context.Context) ([]string, bool, error) {
			svcs, err := client.Traces().ListServices(ctx)
			return svcs, slices.Contains(svcs, otlpSvc) && slices.Contains(svcs, s.svcA), err
		})
		ops := until(t, "zipkin listOperations", metadataTimeout, func(ctx context.Context) ([]string, bool, error) {
			ops, err := zt.ListOperations(ctx, otlpSvc)
			return ops, len(ops) == 2, err
		})
		if !slices.Equal(ops, []string{"get /checkout", "select orders"}) {
			t.Fatal(ops)
		}
		if _, err := zt.GetDependencies(ctx, skywalking.DependencyOptions{}); !errors.Is(err, skywalking.ErrUnsupported) {
			t.Fatalf("zipkin dependencies: %v", err)
		}
	})

	t.Run("logs", func(t *testing.T) {
		lq := client.Logs()
		recs := until(t, "search logs", dataTimeout, func(ctx context.Context) ([]skywalking.LogRecord, bool, error) {
			recs, err := skywalking.SearchAll(ctx, lq, "service="+logSvc+" "+s.run, skywalking.SearchOptions{}, 0)
			return recs, len(recs) == 3, err
		})
		var nativeRec, otlpRec skywalking.LogRecord
		for _, r := range recs {
			switch {
			case strings.HasPrefix(r.Body, "native order"):
				nativeRec = r
			case strings.HasPrefix(r.Body, "otlp log"):
				otlpRec = r
			}
		}
		if nativeRec.TraceID != otlpTrace || nativeRec.SpanID != "00f067aa0ba902b7" || nativeRec.Severity != skywalking.SeverityWarn ||
			nativeRec.Attributes["run_id"] != s.run || nativeRec.Attributes["password"] != "[REDACTED]" || nativeRec.Resource["service.instance.id"] != "inst-"+s.run {
			t.Fatalf("%+v", nativeRec)
		}
		if otlpRec.Attributes["trace_id"] != otlpTrace || otlpRec.Resource["service.name"] != logSvc {
			t.Fatalf("OAP drops the OTLP trace id; it must come back as the trace_id attribute: %+v", otlpRec)
		}
		byTrace, err := lq.Search(ctx, "trace_id="+otlpTrace, skywalking.SearchOptions{})
		if err != nil || len(byTrace.Records) != 1 || !strings.HasPrefix(byTrace.Records[0].Body, "native order") {
			t.Fatal(byTrace, err)
		}
		rec, err := lq.WaitForLog(ctx, "logger facade "+s.run, skywalking.WaitForLogOptions{Timeout: dataTimeout})
		if err != nil || rec.SeverityText != "ERROR" {
			t.Fatal(rec, err)
		}
		page, err := q.QueryLogs(ctx, skywalking.QueryLogsOptions{TraceID: s.traceID})
		if err != nil || len(page.Logs) != 1 || page.Logs[0].ServiceName != s.svcA {
			t.Fatal(page, err)
		}
		page, err = q.QueryLogs(ctx, skywalking.QueryLogsOptions{Service: logSvc, Tags: map[string]string{"run_id": s.run}, Order: skywalking.OrderAsc})
		if err != nil || len(page.Logs) != 3 || !page.Logs[0].Timestamp.Before(page.Logs[2].Timestamp.Add(time.Millisecond)) {
			t.Fatal(page, err)
		}
		until(t, "listStreams", metadataTimeout, func(ctx context.Context) ([]string, bool, error) {
			st, err := lq.ListStreams(ctx)
			return st, slices.Contains(st, logSvc), err
		})
		labels, err := lq.ListLabels(ctx)
		if err != nil || !slices.Contains(labels, "run_id") {
			t.Fatal(labels, err)
		}
		keys, err := q.ListLogTagKeys(ctx, skywalking.TimeRange{})
		if err != nil || !slices.Contains(keys, "level") {
			t.Fatal(keys, err)
		}
		got := make(chan string, 4)
		th, err := lq.Tail(ctx, "service="+logSvc+" tail-"+s.run, func(r skywalking.LogRecord) { got <- r.Body }, skywalking.TailOptions{Start: time.Now().Add(-time.Second)})
		if err != nil {
			t.Fatal(err)
		}
		defer th.Stop()
		lc.Send(ctx, skywalking.LogInput{Message: "tail-" + s.run})
		if err := lc.Close(ctx); err != nil {
			t.Fatal(err)
		}
		select {
		case b := <-got:
			if b != "tail-"+s.run {
				t.Fatal(b)
			}
		case <-time.After(dataTimeout):
			t.Fatal("tail delivered nothing")
		}
		if lc.Stats().Sent != 3 || lc.Stats().Dropped != 0 {
			t.Fatal(lc.Stats())
		}
	})
}
