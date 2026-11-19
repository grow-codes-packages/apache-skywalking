package skywalking

import (
	"strings"
	"testing"
	"time"
)

func TestIDs(t *testing.T) {
	if ServiceID("sw-native-a-1fddf66a") != "c3ctbmF0aXZlLWEtMWZkZGY2NmE=.1" {
		t.Fatal(ServiceID("sw-native-a-1fddf66a"))
	}
	if InstanceID("sw-native-a-1fddf66a", "inst-a-1fddf66a") != "c3ctbmF0aXZlLWEtMWZkZGY2NmE=.1_aW5zdC1hLTFmZGRmNjZh" {
		t.Fatal("instance id")
	}
	if EndpointID("sw-native-b-1fddf66a", "/native/downstream") != "c3ctbmF0aXZlLWItMWZkZGY2NmE=.1_L25hdGl2ZS9kb3duc3RyZWFt" {
		t.Fatal("endpoint id")
	}
}

func TestListAndFindServices(t *testing.T) {
	m := newMockOAP(t).on("listServices", "listServices.all").on("findService", "findService.ok")
	q := m.client().Query()
	svcs, err := q.ListServices(t.Context(), "")
	if err != nil || len(svcs) != 3 || svcs[0].Name != "sw-otlp-1fddf66a" || !svcs[0].Normal || svcs[0].Layers[0] != "GENERAL" {
		t.Fatal(svcs, err)
	}
	if v := m.last("listServices").GQL.Variables; v["l"] != nil {
		t.Fatal(v)
	}
	m.on("listServices", "listServices.layer-unknown")
	svcs, err = q.ListServices(t.Context(), "NO_SUCH_LAYER")
	if err != nil || svcs == nil || len(svcs) != 0 || m.last("listServices").GQL.Variables["l"] != "NO_SUCH_LAYER" {
		t.Fatal(svcs, err)
	}
	s, err := q.FindService(t.Context(), "sw-native-a-1fddf66a")
	if err != nil || s == nil || s.ID != ServiceID("sw-native-a-1fddf66a") {
		t.Fatal(s, err)
	}
	m.on("findService", "findService.missing")
	s, err = q.FindService(t.Context(), "no-such")
	if err != nil || s != nil {
		t.Fatal(s, err)
	}
	_, err = q.FindService(t.Context(), "")
	mustCode(t, err, ErrorCodeValidation)
	m.on("listServices", jsonBody(500, "{}"))
	_, err = q.ListServices(t.Context(), "")
	mustCode(t, err, ErrorCodeUnavailable)
	m.on("findService", jsonBody(500, "{}"))
	_, err = q.FindService(t.Context(), "x")
	mustCode(t, err, ErrorCodeUnavailable)
}

func TestListEndpointsAndInstances(t *testing.T) {
	m := newMockOAP(t).on("findEndpoint", "listEndpoints.ok").on("listInstances", "listInstances.ok")
	q := m.client().Query()
	eps, err := q.ListEndpoints(t.Context(), "sw-native-a-1fddf66a", "", 0)
	if err != nil || len(eps) != 1 || eps[0].Name != "/native/entry" {
		t.Fatal(eps, err)
	}
	v := m.last("findEndpoint").GQL.Variables
	if v["s"] != "c3ctbmF0aXZlLWEtMWZkZGY2NmE=.1" || v["k"] != nil || v["n"] != float64(100) {
		t.Fatal(v)
	}
	if strings.Contains(m.last("findEndpoint").GQL.Query, "duration") {
		t.Fatal("no duration must be sent")
	}
	if _, err := q.ListEndpoints(t.Context(), "svc", "downstream", 5); err != nil {
		t.Fatal(err)
	}
	if v := m.last("findEndpoint").GQL.Variables; v["k"] != "downstream" || v["n"] != float64(5) {
		t.Fatal(v)
	}
	_, err = q.ListEndpoints(t.Context(), "", "", 0)
	mustCode(t, err, ErrorCodeValidation)
	_, err = q.ListEndpoints(t.Context(), "svc", "", -1)
	mustCode(t, err, ErrorCodeValidation)

	ins, err := q.ListInstances(t.Context(), "sw-native-a-1fddf66a", TimeRange{})
	if err != nil || len(ins) != 1 || ins[0].Name != "inst-a-1fddf66a" || ins[0].Language != "GO" || ins[0].Attributes["ipv4s"] != "" {
		t.Fatal(ins, err)
	}
	if _, ok := ins[0].Attributes["ipv4s"]; !ok {
		t.Fatal("attributes")
	}
	d := m.last("listInstances").GQL.Variables["d"].(map[string]any)
	if d["start"] != "2026-11-18 2015" || d["end"] != "2026-11-18 2115" {
		t.Fatal(d)
	}
	_, err = q.ListInstances(t.Context(), "", TimeRange{})
	mustCode(t, err, ErrorCodeValidation)
	_, err = q.ListInstances(t.Context(), "svc", TimeRange{Step: "YEAR"})
	mustCode(t, err, ErrorCodeValidation)
	m.on("findEndpoint", jsonBody(500, "{}")).on("listInstances", jsonBody(500, "{}"))
	_, err = q.ListEndpoints(t.Context(), "svc", "", 0)
	mustCode(t, err, ErrorCodeUnavailable)
	_, err = q.ListInstances(t.Context(), "svc", TimeRange{})
	mustCode(t, err, ErrorCodeUnavailable)
}

func TestQueryTraces(t *testing.T) {
	m := newMockOAP(t).on("queryTraces", "queryTraces.by-service")
	q := m.client().Query()
	page, err := q.QueryTraces(t.Context(), QueryTracesOptions{
		Service: "sw-native-b-1fddf66a", Instance: "inst-b", Endpoint: "/native/downstream", TraceID: "t.1.2",
		TraceState: TraceStateError, MinDuration: 10 * time.Millisecond, MaxDuration: time.Second,
		Tags: map[string]string{"run_id": "1fddf66a", "a": "b"}, Order: TraceOrderDuration, PageNum: 2, PageSize: 1,
	})
	if err != nil || len(page.Traces) != 1 || page.PageNum != 2 || !page.HasMore {
		t.Fatal(page, err)
	}
	tr := page.Traces[0]
	if tr.TraceID != "1fddf66a1c64db2c1345419627769f1f.1.1795036241775" || len(tr.Spans) < 2 || tr.Spans[0].Refs == nil || tr.Spans[0].Tags == nil {
		t.Fatal(tr)
	}
	if page.RetrievedEnd.IsZero() {
		t.Fatal("retrieved time range")
	}
	c := m.last("queryTraces").GQL.Variables["c"].(map[string]any)
	if c["serviceId"] != "c3ctbmF0aXZlLWItMWZkZGY2NmE=.1" || c["serviceInstanceId"] != InstanceID("sw-native-b-1fddf66a", "inst-b") ||
		c["endpointId"] != "c3ctbmF0aXZlLWItMWZkZGY2NmE=.1_L25hdGl2ZS9kb3duc3RyZWFt" || c["traceId"] != "t.1.2" ||
		c["traceState"] != "ERROR" || c["queryOrder"] != "BY_DURATION" || c["minTraceDuration"] != float64(10) || c["maxTraceDuration"] != float64(1000) {
		t.Fatal(c)
	}
	tags := c["tags"].([]any)
	if len(tags) != 2 || tags[0].(map[string]any)["key"] != "a" {
		t.Fatal(tags)
	}
	if p := c["paging"].(map[string]any); p["pageNum"] != float64(2) || p["pageSize"] != float64(1) {
		t.Fatal(p)
	}

	m.on("queryTraces", "queryTraces.min-duration-no-match")
	page, err = q.QueryTraces(t.Context(), QueryTracesOptions{})
	if err != nil || len(page.Traces) != 0 || page.HasMore || page.PageSize != DefaultPageSize {
		t.Fatal(page, err)
	}
	c = m.last("queryTraces").GQL.Variables["c"].(map[string]any)
	if c["traceState"] != "ALL" || c["queryOrder"] != "BY_START_TIME" || c["serviceId"] != nil {
		t.Fatal(c)
	}
	m.on("queryTraces", jsonBody(200, `{"data":{"queryTraces":{"traces":[{"spans":[]}]}}}`))
	page, err = q.QueryTraces(t.Context(), QueryTracesOptions{})
	if err != nil || len(page.Traces) != 0 || !page.RetrievedEnd.IsZero() {
		t.Fatal(page, err)
	}
	for _, bad := range []QueryTracesOptions{{Endpoint: "x"}, {MinDuration: -1}, {PageSize: -1}, {Range: TimeRange{Step: "X"}}} {
		_, err := q.QueryTraces(t.Context(), bad)
		mustCode(t, err, ErrorCodeValidation)
	}
	m.on("queryTraces", "queryBasicTraces.unsupported")
	_, err = q.QueryTraces(t.Context(), QueryTracesOptions{})
	mustCode(t, err, ErrorCodeUnsupported)
}

func TestGetNativeTrace(t *testing.T) {
	m := newMockOAP(t).on("queryTrace", "getTrace.native")
	q := m.client().Query()
	tr, err := q.GetTrace(t.Context(), " 1fddf66a1c64db2c1345419627769f1f.1.1795036241775 ")
	if err != nil || tr == nil || len(tr.Spans) != 3 {
		t.Fatal(tr, err)
	}
	if m.last("queryTrace").GQL.Variables["id"] != "1fddf66a1c64db2c1345419627769f1f.1.1795036241775" {
		t.Fatal("id must be trimmed, not changed")
	}
	m.on("queryTrace", "getTrace.native-missing")
	tr, err = q.GetTrace(t.Context(), "no-such")
	if err != nil || tr != nil {
		t.Fatal(tr, err)
	}
	_, err = q.GetTrace(t.Context(), "")
	mustCode(t, err, ErrorCodeValidation)
}

func TestQueryLogs(t *testing.T) {
	m := newMockOAP(t).on("queryLogs", "queryLogs.by-service")
	q := m.client().Query()
	span := 3
	page, err := q.QueryLogs(t.Context(), QueryLogsOptions{
		Service: "sw-otlp-1fddf66a", Instance: "inst", Endpoint: "/ep", TraceID: "abc", SegmentID: "seg", SpanID: &span,
		Tags: map[string]string{"level": "INFO"}, Keywords: []string{"a"}, ExcludingKeywords: []string{"b"}, Order: OrderAsc,
	})
	if err != nil || len(page.Logs) != 2 || page.HasMore || page.ErrorReason != "" {
		t.Fatal(page, err)
	}
	l := page.Logs[0]
	if l.ServiceName != "sw-otlp-1fddf66a" || l.ServiceInstanceName != "inst-1fddf66a" || l.TraceID != "" || l.EndpointName != "" ||
		l.Timestamp.UnixMilli() != 1795036241755 || l.ContentType != "TEXT" {
		t.Fatal(l)
	}
	if v, ok := l.Tag("level"); !ok || v != "INFO" {
		t.Fatal(v)
	}
	if _, ok := l.Tag("nope"); ok {
		t.Fatal("tag")
	}
	if page.Logs[1].TraceID != "055ffba8ecc69a64950538fda698a308" {
		t.Fatal(page.Logs[1])
	}
	c := m.last("queryLogs").GQL.Variables["c"].(map[string]any)
	rt := c["relatedTrace"].(map[string]any)
	if rt["traceId"] != "abc" || rt["segmentId"] != "seg" || rt["spanId"] != float64(3) || c["queryDuration"] != nil ||
		c["queryOrder"] != "ASC" || c["serviceInstanceId"] == nil || c["endpointId"] == nil ||
		c["keywordsOfContent"] == nil || c["excludingKeywordsOfContent"] == nil || c["tags"] == nil {
		t.Fatal(c)
	}
	m.on("queryLogs", jsonBody(200, `{"data":{"queryLogs":{"errorReason":"too wide","logs":[]}}}`))
	page, err = q.QueryLogs(t.Context(), QueryLogsOptions{TraceID: "abc", Range: TimeRange{End: fixedNow}})
	if err != nil || page.ErrorReason != "too wide" || page.Logs == nil {
		t.Fatal(page, err)
	}
	c = m.last("queryLogs").GQL.Variables["c"].(map[string]any)
	if c["queryDuration"] == nil || c["queryOrder"] != "DES" {
		t.Fatal(c)
	}
	for _, bad := range []QueryLogsOptions{{Instance: "x"}, {PageNum: -1}, {SegmentID: "s"}, {Order: "up"}, {Range: TimeRange{Step: "X"}}} {
		_, err := q.QueryLogs(t.Context(), bad)
		mustCode(t, err, ErrorCodeValidation)
	}
	m.on("queryLogs", "queryLogs.no-duration-error")
	_, err = q.QueryLogs(t.Context(), QueryLogsOptions{})
	mustCode(t, err, ErrorCodeValidation)
}

func TestTopologyAndKeys(t *testing.T) {
	m := newMockOAP(t).on("getGlobalTopology", "topology.global").on("getServiceTopology", "topology.service").
		on("listLayers", jsonBody(200, `{"data":{"listLayers":["MESH","GENERAL"]}}`)).
		on("queryLogTagAutocompleteKeys", "queryLogTagAutocompleteKeys.ok")
	q := m.client().Query()
	topo, err := q.Topology(t.Context(), TimeRange{}, TopologyOptions{Layer: "GENERAL"})
	if err != nil || len(topo.Nodes) != 3 || len(topo.Calls) == 0 || topo.Nodes[0].Name != "User" || topo.Nodes[0].IsReal {
		t.Fatal(topo, err)
	}
	if m.last("getGlobalTopology").GQL.Variables["l"] != "GENERAL" {
		t.Fatal("layer")
	}
	if topo.NodeName("nope") != "" || topo.NodeName("VXNlcg==.0") != "User" {
		t.Fatal("node name")
	}
	st, err := q.Topology(t.Context(), TimeRange{}, TopologyOptions{Service: "sw-native-b-1fddf66a"})
	if err != nil || len(st.Nodes) != 2 || st.Calls[0].DetectPoints[0] != "SERVER" || st.Nodes[0].Layers == nil || st.Calls[0].SourceComponents == nil {
		t.Fatal(st, err)
	}
	if m.last("getServiceTopology").GQL.Variables["s"] != "c3ctbmF0aXZlLWItMWZkZGY2NmE=.1" {
		t.Fatal("service id")
	}
	_, err = q.Topology(t.Context(), TimeRange{Step: "X"}, TopologyOptions{})
	mustCode(t, err, ErrorCodeValidation)
	layers, err := q.ListLayers(t.Context())
	if err != nil || strings.Join(layers, ",") != "GENERAL,MESH" {
		t.Fatal(layers, err)
	}
	keys, err := q.ListLogTagKeys(t.Context(), TimeRange{})
	if err != nil || strings.Join(keys, ",") != "level,run_id,span_id,trace_id" {
		t.Fatal(keys, err)
	}
	m.on("listLayers", jsonBody(500, "{}")).on("queryLogTagAutocompleteKeys", jsonBody(500, "{}"))
	_, err = q.ListLayers(t.Context())
	mustCode(t, err, ErrorCodeUnavailable)
	_, err = q.ListLogTagKeys(t.Context(), TimeRange{})
	mustCode(t, err, ErrorCodeUnavailable)
	_, err = q.ListLogTagKeys(t.Context(), TimeRange{Step: "X"})
	mustCode(t, err, ErrorCodeValidation)
}
