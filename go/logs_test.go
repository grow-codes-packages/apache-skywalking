package skywalking

import (
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestParseLogQuery(t *testing.T) {
	q := parseLogQuery(`service=svc instance=i endpoint=/e traceId=abc run_id=7 msg="a b" Hello =x k= "Big Phrase" world`)
	if q.service != "svc" || q.instance != "i" || q.endpoint != "/e" || q.traceID != "abc" || q.tags["run_id"] != "7" || q.tags["msg"] != "a b" {
		t.Fatalf("%+v", q)
	}
	if strings.Join(q.text, "|") != "hello|=x|k=|big phrase|world" {
		t.Fatal(q.text)
	}
	if q := parseLogQuery(`trace_id=t ""`); q.traceID != "t" || len(q.text) != 0 {
		t.Fatalf("%+v", q)
	}
	l := NativeLog{Content: "Hello there", Tags: []KeyValue{{Key: "x", Value: "World"}}}
	if !parseLogQuery("hello world").matches(l) || parseLogQuery("hello mars").matches(l) || !parseLogQuery("").matches(l) {
		t.Fatal("matching")
	}
	for _, c := range []string{"x", "0:1:5", "1:-1:5", "1:0", "1:0:0", "1:0:10001"} {
		if _, _, _, err := parseCursor(c); err == nil {
			t.Errorf("cursor %q accepted", c)
		}
	}
}

// logPages serves queryLogs pages from logs, honouring paging and order.
func logPages(logs []string) func(recorded, http.ResponseWriter) {
	return func(r recorded, w http.ResponseWriter) {
		c := r.GQL.Variables["c"].(map[string]any)
		p := c["paging"].(map[string]any)
		num, size := int(p["pageNum"].(float64)), int(p["pageSize"].(float64))
		start := min((num-1)*size, len(logs))
		end := min(start+size, len(logs))
		out := make([]string, 0, end-start)
		for i := start; i < end; i++ {
			out = append(out, fmt.Sprintf(`{"serviceName":"svc","serviceId":"c3Zj.1","timestamp":%d,"contentType":"TEXT","content":%q,"tags":[{"key":"level","value":"WARN"},{"key":"n","value":"%d"}]}`, 1795036241000+int64(i), logs[i], i))
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = fmt.Fprintf(w, `{"data":{"queryLogs":{"errorReason":null,"logs":[%s]}}}`, strings.Join(out, ","))
	}
}

func TestLogSearch(t *testing.T) {
	logs := make([]string, 0, 25)
	for i := 0; i < 25; i++ {
		word := "other"
		if i%5 == 0 {
			word = "needle"
		}
		logs = append(logs, fmt.Sprintf("line %d %s", i, word))
	}
	m := newMockOAP(t).on("queryLogs", logPages(logs))
	lq := m.client().Logs()

	t.Run("no free text pages with limit", func(t *testing.T) {
		res, err := lq.Search(t.Context(), "service=svc level=WARN", SearchOptions{Limit: 10})
		if err != nil || len(res.Records) != 10 || res.NextCursor != "2:0:10" {
			t.Fatal(len(res.Records), res.NextCursor, err)
		}
		c := m.last("queryLogs").GQL.Variables["c"].(map[string]any)
		d := c["queryDuration"].(map[string]any)
		if c["serviceId"] != ServiceID("svc") || c["tags"] == nil || d["step"] != "MINUTE" || d["end"] != "2026-11-18 2115" || d["start"] != "2026-11-18 2015" {
			t.Fatal(c)
		}
		all, err := SearchAll(t.Context(), lq, "service=svc", SearchOptions{Limit: 10}, 0)
		if err != nil || len(all) != 25 {
			t.Fatal(len(all), err)
		}
	})
	t.Run("free text filters while scanning", func(t *testing.T) {
		res, err := lq.Search(t.Context(), "NEEDLE", SearchOptions{Limit: 2})
		if err != nil || len(res.Records) != 2 || res.NextCursor != "1:6:1000" {
			t.Fatal(res, err)
		}
		if p := m.last("queryLogs").GQL.Variables["c"].(map[string]any)["paging"].(map[string]any); p["pageSize"] != float64(LogScanPageSize) {
			t.Fatal(p)
		}
		res, err = lq.Search(t.Context(), "needle", SearchOptions{Limit: 2, Cursor: res.NextCursor})
		if err != nil || len(res.Records) != 2 || res.Records[0].Body != "line 10 needle" || res.NextCursor != "1:16:1000" {
			t.Fatal(res, err)
		}
		res, _ = lq.Search(t.Context(), "needle", SearchOptions{Limit: 2, Cursor: res.NextCursor})
		if len(res.Records) != 1 || res.NextCursor != "" {
			t.Fatal(res)
		}
		rec, err := lq.WaitForLog(t.Context(), "line 20", WaitForLogOptions{Interval: time.Millisecond})
		if err != nil || rec.Body != "line 20 needle" || rec.Severity != SeverityWarn || rec.Attributes["n"] != "20" {
			t.Fatal(rec, err)
		}
	})
	t.Run("scan bound returns a continuation cursor", func(t *testing.T) {
		small := &Logs{c: lq.c, maxPages: 1}
		big := make([]string, LogScanPageSize+5)
		for i := range big {
			big[i] = "x"
		}
		m.on("queryLogs", logPages(big))
		res, err := small.Search(t.Context(), "nomatch", SearchOptions{Limit: 5})
		if err != nil || len(res.Records) != 0 || res.NextCursor != "2:0:1000" {
			t.Fatal(res, err)
		}
		res, _ = small.Search(t.Context(), "x", SearchOptions{Limit: LogScanPageSize})
		if res.NextCursor != "2:0:1000" {
			t.Fatal(res.NextCursor)
		}
		m.on("queryLogs", logPages(logs))
	})
	t.Run("errors", func(t *testing.T) {
		for _, tc := range []struct {
			q    string
			opts SearchOptions
		}{{"x", SearchOptions{Limit: 10001}}, {"x", SearchOptions{Cursor: "bad"}}, {"instance=i", SearchOptions{}}, {"x", SearchOptions{Start: fixedNow.Add(time.Hour)}}} {
			_, err := lq.Search(t.Context(), tc.q, tc.opts)
			mustCode(t, err, ErrorCodeValidation)
		}
		m.on("queryLogs", jsonBody(500, "{}"))
		_, err := lq.Search(t.Context(), "x", SearchOptions{})
		mustCode(t, err, ErrorCodeUnavailable)
	})
}

func TestLogRecordFromNative(t *testing.T) {
	f := loadFixture(t, "queryLogs.by-service")
	var body struct {
		Data struct {
			QueryLogs struct {
				Logs []struct {
					Timestamp int64      `json:"timestamp"`
					Content   string     `json:"content"`
					TraceID   *string    `json:"traceId"`
					Tags      []KeyValue `json:"tags"`
				} `json:"logs"`
			} `json:"queryLogs"`
		} `json:"data"`
	}
	if err := json.Unmarshal(f.Response.Body, &body); err != nil {
		t.Fatal(err)
	}
	raw := body.Data.QueryLogs.Logs[0]
	rec := LogRecordFromNative(NativeLog{ServiceName: "svc", ServiceInstanceName: "inst", EndpointName: "/ep", Timestamp: time.UnixMilli(raw.Timestamp), Content: raw.Content, Tags: raw.Tags})
	if rec.TraceID != "" || rec.SpanID != "999a2365604ef4c8" || rec.SeverityText != "INFO" || rec.Severity != SeverityInfo ||
		rec.Attributes["run_id"] != "1fddf66a" || rec.Attributes["trace_id"] != "055ffba8ecc69a64950538fda698a308" ||
		rec.Resource["sw.endpoint"] != "/ep" || rec.Resource["service.name"] != "svc" || rec.Resource["service.instance.id"] != "inst" {
		t.Fatalf("%+v", rec)
	}
	rec = LogRecordFromNative(NativeLog{TraceID: "native.1.2", Tags: []KeyValue{{Key: "span_id", Value: "short"}, {Key: "level", Value: "weird"}}})
	if rec.TraceID != "" || rec.Attributes["sw.trace_id"] != "native.1.2" || rec.Attributes["span_id"] != "short" || rec.SpanID != "" ||
		rec.SeverityText != "weird" || rec.Severity != SeverityInfo || len(rec.Resource) != 1 {
		t.Fatalf("%+v", rec)
	}
	rec = LogRecordFromNative(NativeLog{TraceID: "055FFBA8ECC69A64950538FDA698A308", Tags: []KeyValue{{Key: "level", Value: "error"}}})
	if rec.TraceID != "055ffba8ecc69a64950538fda698a308" || rec.Severity != SeverityError {
		t.Fatalf("%+v", rec)
	}
}

func TestLogStreamsAndLabels(t *testing.T) {
	m := newMockOAP(t).on("listServices", "listServices.all").on("queryLogTagAutocompleteKeys", "queryLogTagAutocompleteKeys.ok")
	lq := m.client().Logs()
	streams, err := lq.ListStreams(t.Context())
	if err != nil || len(streams) != 3 || streams[0] != "sw-native-a-1fddf66a" {
		t.Fatal(streams, err)
	}
	m.on("listServices", jsonBody(200, `{"data":{"listServices":[{"id":"a","name":"real","normal":true},{"id":"b","name":"virtual","normal":false}]}}`))
	if streams, _ = lq.ListStreams(t.Context()); len(streams) != 1 || streams[0] != "real" {
		t.Fatal(streams)
	}
	labels, err := lq.ListLabels(t.Context())
	if err != nil || len(labels) != 4 {
		t.Fatal(labels, err)
	}
	if d := m.last("queryLogTagAutocompleteKeys").GQL.Variables["d"].(map[string]any); d["step"] != "MINUTE" || d["start"] != "2026-11-18 2015" {
		t.Fatal(d)
	}
	m.on("listServices", jsonBody(500, "{}"))
	_, err = lq.ListStreams(t.Context())
	mustCode(t, err, ErrorCodeUnavailable)
}

func TestLogTail(t *testing.T) {
	var mu sync.Mutex
	logs := []string{"old"}
	m := newMockOAP(t)
	m.on("queryLogs", func(r recorded, w http.ResponseWriter) {
		mu.Lock()
		cur := append([]string(nil), logs...)
		mu.Unlock()
		logPages(cur)(r, w)
	})
	c := m.client()
	c.now = func() time.Time { return time.UnixMilli(1795036241000) }
	got := make(chan string, 10)
	h, err := c.Logs().Tail(t.Context(), "", func(r LogRecord) { got <- r.Body }, TailOptions{Interval: 5 * time.Millisecond})
	if err != nil {
		t.Fatal(err)
	}
	if b := <-got; b != "old" {
		t.Fatal(b)
	}
	mu.Lock()
	logs = append(logs, "new")
	mu.Unlock()
	if b := <-got; b != "new" {
		t.Fatal(b)
	}
	h.Stop()
	h.Stop()
	select {
	case b := <-got:
		t.Fatalf("duplicate delivery %q", b)
	default:
	}
	_, err = c.Logs().Tail(t.Context(), "", nil, TailOptions{})
	mustCode(t, err, ErrorCodeValidation)
	// start in the future skips everything; failing polls keep running
	h, _ = c.Logs().Tail(t.Context(), "", func(r LogRecord) { got <- r.Body }, TailOptions{Start: time.UnixMilli(1795036249000), Interval: time.Millisecond})
	time.Sleep(20 * time.Millisecond)
	m.on("queryLogs", jsonBody(500, "{}"))
	time.Sleep(20 * time.Millisecond)
	h.Stop()
	if len(got) != 0 {
		t.Fatal("records before Start delivered")
	}
	// default start = now
	h, _ = c.Logs().Tail(t.Context(), "", func(LogRecord) {}, TailOptions{})
	h.Stop()
	m.on("queryLogs", logPages(nil))
	tl := &tailer{cursor: time.Unix(100, 0), seen: map[[32]byte]time.Time{{1}: time.Unix(1, 0)}, l: c.Logs(), onRecord: func(LogRecord) {}}
	tl.poll(t.Context())
	if len(tl.seen) != 0 {
		t.Fatal("old keys must be pruned")
	}
}

func TestNativeLogExporter(t *testing.T) {
	var bodies [][]map[string]any
	var mu sync.Mutex
	m := newMockOAP(t).route("POST /v3/logs", func(r recorded, w http.ResponseWriter) {
		var b []map[string]any
		_ = json.Unmarshal(r.Body, &b)
		mu.Lock()
		bodies = append(bodies, b)
		mu.Unlock()
		loadFixture(t, "reportLogs.ok").write(w)
	})
	c := m.client()
	exp := c.NewLogExporter(LogExporterOptions{})
	flags := uint8(1)
	rec := LogRecord{
		Timestamp: time.UnixMilli(1795036241725), Severity: SeverityError, Body: "boom",
		Attributes: map[string]AttrValue{"n": int64(2), "nested": map[string]any{"a": true}, "list": []any{"x"}, "ok": true, "f": 1.5, "nil": nil, "level": "x", "sw.endpoint": "/ep", "s": "v"},
		Resource:   map[string]AttrValue{"service.name": "svc", "service.instance.id": "inst"},
		TraceID:    otlpTrace, SpanID: "00f067aa0ba902b7", TraceFlags: &flags,
	}
	d := exp.LogData(rec)
	if d["service"] != "svc" || d["serviceInstance"] != "inst" || d["timestamp"] != int64(1795036241725) || d["layer"] != "GENERAL" || d["endpoint"] != nil {
		t.Fatal(d)
	}
	if d["traceContext"].(map[string]any)["traceId"] != otlpTrace {
		t.Fatal(d["traceContext"])
	}
	tags := map[string]string{}
	for _, kv := range d["tags"].(map[string]any)["data"].([]map[string]string) {
		tags[kv["key"]] = kv["value"]
	}
	if tags["level"] != "ERROR" || tags["n"] != "2" || tags["nested"] != `{"a":true}` || tags["list"] != `["x"]` || tags["ok"] != "true" ||
		tags["f"] != "1.5" || tags["nil"] != "" || tags["trace_id"] != "" || tags["span_id"] != "00f067aa0ba902b7" || tags["s"] != "v" || tags["sw.endpoint"] != "/ep" {
		t.Fatal(tags)
	}
	if lv := exp.LogData(LogRecord{Severity: Severity(10)})["tags"].(map[string]any)["data"].([]map[string]string)[0]["value"]; lv != "INFO" {
		t.Fatal(lv)
	}
	d = exp.LogData(LogRecord{Body: "x"})
	if d["service"] != "unknown_service" || d["serviceInstance"] != nil || d["traceContext"] != nil || d["timestamp"] != fixedNow.UnixMilli() {
		t.Fatal(d)
	}
	if tagValue(func() {}) == "" {
		t.Fatal("unencodable values fall back to fmt")
	}
	res, err := exp.Export(t.Context(), []LogRecord{rec, {Body: "second", SeverityText: "NOTICE"}}, ExportOptions{Gzip: true})
	if err != nil || res.Accepted != 2 || len(bodies) != 1 || len(bodies[0]) != 2 {
		t.Fatal(res, err, bodies)
	}
	if res, err := exp.Export(t.Context(), nil, ExportOptions{}); err != nil || res.Accepted != 0 {
		t.Fatal(res, err)
	}
	if exp.Shutdown(t.Context()) != nil {
		t.Fatal("shutdown")
	}
	m.route("POST /v3/logs", "reportLogs.bad-json")
	_, err = exp.Export(t.Context(), []LogRecord{rec}, ExportOptions{})
	mustCode(t, err, ErrorCodeUnavailable)

	// NewLogClient end to end
	m.route("POST /v3/logs", "reportLogs.ok")
	lc, err := c.NewLogClient(LogClientOptions{Resource: map[string]AttrValue{"service.name": "app", "service.instance.id": "i-1"}})
	if err != nil {
		t.Fatal(err)
	}
	lc.Send(t.Context(), LogInput{Message: "hello", Attributes: map[string]AttrValue{"password": "hunter2"}})
	if err := lc.Close(t.Context()); err != nil {
		t.Fatal(err)
	}
	last := m.last("/v3/logs")
	if !strings.Contains(string(last.Body), `"service":"app"`) || strings.Contains(string(last.Body), "hunter2") {
		t.Fatal(string(last.Body))
	}
	if lc.Stats().Sent != 1 {
		t.Fatal(lc.Stats())
	}
}
