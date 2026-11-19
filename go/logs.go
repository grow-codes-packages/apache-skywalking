package skywalking

import (
	"context"
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Log search defaults.
const (
	// DefaultLogSearchWindow is the Search window when SearchOptions.Start is unset.
	DefaultLogSearchWindow = time.Hour
	// LogScanPageSize is the GraphQL page size used while filtering free text client side.
	LogScanPageSize = 1000
	// DefaultLogScanPages bounds the server pages one Search call reads.
	DefaultLogScanPages = 10
	// TailOverlap is how far back each Tail poll re-reads to catch late records.
	TailOverlap = 5 * time.Second
)

// MaxLogSearchLimit is the largest SearchOptions.Limit (and cursor page size).
const MaxLogSearchLimit = 10000

// Logs implements LogQueryClient over GraphQL queryLogs. Get it from Client.Logs. It
// is safe for concurrent use.
//
// Query syntax: space-separated terms. "key=value" terms (value optionally "quoted")
// filter: service=, instance= (needs service), endpoint= (needs service), trace_id= or
// traceId= (relatedTrace); any other key is a server tag filter (only keys in the
// server's SW_SEARCHABLE_LOGS_TAG_KEYS match). Every other term ("quoted phrases"
// allowed) is free text that must occur (case-insensitive) in the content or a tag
// value. OAP on BanyanDB ignores content keywords, so free text is always filtered
// client side: Search reads raw pages of LogScanPageSize logs and keeps the matching
// ones until Limit matches or DefaultLogScanPages pages; without free text the page size
// is Limit. The cursor is "page:offset:size" and continues where the scan stopped.
// Records outside [Start, End] are dropped (the server window has minute precision).
type Logs struct {
	c        *Client
	maxPages int
}

var _ LogQueryClient = (*Logs)(nil)

// Logs returns the log query client.
func (c *Client) Logs() *Logs { return &Logs{c: c, maxPages: DefaultLogScanPages} }

type logQuery struct {
	service, instance, endpoint, traceID string
	tags                                 map[string]string
	text                                 []string
}

var logToken = regexp.MustCompile(`([A-Za-z_][\w.-]*)=(?:"([^"]*)"|(\S+))|"([^"]*)"|(\S+)`)

// parseLogQuery splits a query into filters and free-text terms (lower-cased).
func parseLogQuery(query string) logQuery {
	q := logQuery{tags: map[string]string{}}
	for _, m := range logToken.FindAllStringSubmatchIndex(query, -1) {
		group := func(i int) (string, bool) {
			if m[2*i] < 0 {
				return "", false
			}
			return query[m[2*i]:m[2*i+1]], true
		}
		if key, ok := group(1); ok {
			v, quoted := group(2)
			if !quoted {
				v, _ = group(3)
			}
			switch key {
			case "service":
				q.service = v
			case "instance":
				q.instance = v
			case "endpoint":
				q.endpoint = v
			case "trace_id", "traceId":
				q.traceID = v
			default:
				q.tags[key] = v
			}
			continue
		}
		text, quoted := group(4)
		if !quoted {
			text, _ = group(5)
		}
		if text != "" {
			q.text = append(q.text, strings.ToLower(text))
		}
	}
	return q
}

func (q logQuery) matches(l NativeLog) bool {
	if len(q.text) == 0 {
		return true
	}
	var b strings.Builder
	b.WriteString(strings.ToLower(l.Content))
	for _, t := range l.Tags {
		b.WriteByte('\n')
		b.WriteString(strings.ToLower(t.Value))
	}
	text := b.String()
	for _, t := range q.text {
		if !strings.Contains(text, t) {
			return false
		}
	}
	return true
}

var cursorPattern = regexp.MustCompile(`^(\d+):(\d+):(\d+)$`)

// parseCursor reads "<page>:<offset>:<size>".
func parseCursor(cursor string) (page, offset, size int, err error) {
	m := cursorPattern.FindStringSubmatch(cursor)
	if m != nil {
		page, _ = strconv.Atoi(m[1])
		offset, _ = strconv.Atoi(m[2])
		size, _ = strconv.Atoi(m[3])
	}
	if m == nil || page < 1 || size < 1 || size > MaxLogSearchLimit {
		return 0, 0, 0, ValidationError("logs.search", "invalid cursor")
	}
	return page, offset, size, nil
}

// Search returns one page of matching records (newest first unless Order is asc)
// within [Start, End] (default: the last hour). See Logs for the query syntax.
func (l *Logs) Search(ctx context.Context, query string, opts SearchOptions) (LogSearchResult, error) {
	const op = "logs.search"
	opts = opts.WithDefaults()
	if opts.Limit > MaxLogSearchLimit {
		return LogSearchResult{}, ValidationError(op, "limit must be 1..10000")
	}
	q := parseLogQuery(query)
	if (q.instance != "" || q.endpoint != "") && q.service == "" {
		return LogSearchResult{}, ValidationError(op, "instance= and endpoint= need service=")
	}
	end := opts.End
	if end.IsZero() {
		end = l.c.now()
	}
	start := opts.Start
	if start.IsZero() {
		start = end.Add(-DefaultLogSearchWindow)
	}
	if start.After(end) {
		return LogSearchResult{}, ValidationError(op, "invalid time window")
	}
	page, offset, size := 1, 0, opts.Limit
	if len(q.text) > 0 {
		size = LogScanPageSize
	}
	if opts.Cursor != "" {
		var err error
		if page, offset, size, err = parseCursor(opts.Cursor); err != nil {
			return LogSearchResult{}, err
		}
	}
	order := OrderDesc
	if opts.Order == OrderAsc {
		order = OrderAsc
	}
	base := QueryLogsOptions{
		Service: q.service, Instance: q.instance, Endpoint: q.endpoint, TraceID: q.traceID,
		Tags: q.tags, Range: TimeRange{Start: start, End: end, Step: StepMinute}, Order: order, PageSize: size,
	}
	res := LogSearchResult{Records: []LogRecord{}}
	for scanned := 0; scanned < l.maxPages; scanned++ {
		qo := base
		qo.PageNum = page
		lp, err := l.c.Query().QueryLogs(ctx, qo)
		if err != nil {
			return LogSearchResult{}, err
		}
		for i := offset; i < len(lp.Logs); i++ {
			lg := lp.Logs[i]
			if lg.Timestamp.Before(start) || lg.Timestamp.After(end) || !q.matches(lg) {
				continue
			}
			res.Records = append(res.Records, LogRecordFromNative(lg))
			if len(res.Records) >= opts.Limit {
				switch {
				case i+1 < len(lp.Logs):
					res.NextCursor = fmt.Sprintf("%d:%d:%d", page, i+1, size)
				case len(lp.Logs) >= size:
					res.NextCursor = fmt.Sprintf("%d:0:%d", page+1, size)
				}
				return res, nil
			}
		}
		if len(lp.Logs) < size {
			return res, nil
		}
		page, offset = page+1, 0
	}
	res.NextCursor = fmt.Sprintf("%d:0:%d", page, size)
	return res, nil
}

// LogRecordFromNative converts a stored log to the SPEC record: content → Body, tag
// "level" → SeverityText/Severity (default INFO), tag "span_id" with 16 hex characters
// → SpanID, a 32-hex stored trace id → TraceID (a native trace id goes to attribute
// "sw.trace_id"), other tags → Attributes (string values), service / instance /
// endpoint → Resource service.name / service.instance.id / sw.endpoint.
func LogRecordFromNative(n NativeLog) LogRecord {
	rec := LogRecord{
		Timestamp:  n.Timestamp,
		Body:       n.Content,
		Attributes: map[string]AttrValue{},
		Resource:   map[string]AttrValue{"service.name": n.ServiceName},
	}
	level := ""
	hasLevel := false
	for _, t := range n.Tags {
		switch {
		case t.Key == "level":
			level, hasLevel = t.Value, true
		case t.Key == "span_id" && isLowerHexN(t.Value, 16):
			rec.SpanID = t.Value
		default:
			rec.Attributes[t.Key] = t.Value
		}
	}
	rec.Severity, rec.SeverityText = SeverityInfo, "INFO"
	if hasLevel {
		rec.SeverityText = level
		if sev, ok := ParseSeverity(level); ok {
			rec.Severity = sev
		}
	}
	if tid := strings.ToLower(n.TraceID); isLowerHexN(tid, 32) {
		rec.TraceID = tid
	} else if n.TraceID != "" {
		rec.Attributes["sw.trace_id"] = n.TraceID
	}
	if n.ServiceInstanceName != "" {
		rec.Resource["service.instance.id"] = n.ServiceInstanceName
	}
	if n.EndpointName != "" {
		rec.Resource["sw.endpoint"] = n.EndpointName
	}
	return rec
}

func isLowerHexN(s string, n int) bool {
	if len(s) != n {
		return false
	}
	for _, c := range s {
		if (c < '0' || c > '9') && (c < 'a' || c > 'f') {
			return false
		}
	}
	return true
}

// ListStreams lists the normal services with native metadata (log senders and
// agents), sorted: OAP stores logs per service, there are no streams.
func (l *Logs) ListStreams(ctx context.Context) ([]string, error) {
	svcs, err := l.c.Query().ListServices(ctx, "")
	if err != nil {
		return nil, err
	}
	names := make([]string, 0, len(svcs))
	for _, s := range svcs {
		if s.Normal {
			names = append(names, s.Name)
		}
	}
	return uniqueSorted(names), nil
}

// ListLabels lists the searchable log tag keys seen in the last hour (GraphQL
// queryLogTagAutocompleteKeys), sorted.
func (l *Logs) ListLabels(ctx context.Context) ([]string, error) {
	return l.c.Query().ListLogTagKeys(ctx, TimeRange{})
}

// WaitForLog polls Search until a record matches (test helper).
func (l *Logs) WaitForLog(ctx context.Context, query string, opts WaitForLogOptions) (LogRecord, error) {
	return WaitForLog(ctx, l, query, opts)
}

// Tail polls Search (oldest first) every Interval (default 1 s) and calls onRecord for
// each new matching record at or after Start (default now). Records are de-duplicated
// across polls. Stop the handle or cancel ctx to end it.
func (l *Logs) Tail(ctx context.Context, query string, onRecord func(LogRecord), opts TailOptions) (TailHandle, error) {
	if onRecord == nil {
		return nil, ValidationError("logs.tail", "onRecord is required")
	}
	interval := opts.Interval
	if interval <= 0 {
		interval = time.Second
	}
	floor := opts.Start
	if floor.IsZero() {
		floor = l.c.now()
	}
	tctx, cancel := context.WithCancel(ctx)
	h := &tailHandle{cancel: cancel, done: make(chan struct{})}
	t := &tailer{l: l, query: query, onRecord: onRecord, cursor: floor, floor: floor, seen: map[[32]byte]time.Time{}}
	go func() {
		defer close(h.done)
		defer cancel()
		for {
			t.poll(tctx)
			if sleepContext(tctx, interval) != nil {
				return
			}
		}
	}()
	return h, nil
}

type tailHandle struct {
	once   sync.Once
	cancel context.CancelFunc
	done   chan struct{}
}

// Stop ends the tail and waits for the poller to exit.
func (h *tailHandle) Stop() {
	h.once.Do(h.cancel)
	<-h.done
}

type tailer struct {
	l        *Logs
	query    string
	onRecord func(LogRecord)
	cursor   time.Time
	floor    time.Time
	seen     map[[32]byte]time.Time
}

// tailMaxRecords bounds one tail poll.
const tailMaxRecords = 5000

func (t *tailer) poll(ctx context.Context) {
	start := t.cursor.Add(-TailOverlap)
	recs, err := SearchAll(ctx, t.l, t.query, SearchOptions{Start: start, End: t.l.c.now().Add(time.Second), Order: OrderAsc, Limit: 500}, tailMaxRecords)
	if err != nil {
		if ctx.Err() == nil {
			t.l.c.transport.log(slog.LevelDebug, "tail poll failed", slog.String("error", err.Error()))
		}
		return
	}
	for _, r := range recs {
		if ctx.Err() != nil {
			return
		}
		if r.Timestamp.Before(t.floor) {
			continue
		}
		key := recordKey(r)
		if _, dup := t.seen[key]; dup {
			continue
		}
		t.seen[key] = r.Timestamp
		if r.Timestamp.After(t.cursor) {
			t.cursor = r.Timestamp
		}
		t.onRecord(r)
	}
	limit := t.cursor.Add(-2 * TailOverlap)
	for k, ts := range t.seen {
		if ts.Before(limit) {
			delete(t.seen, k)
		}
	}
}

func recordKey(r LogRecord) [32]byte {
	b, _ := json.Marshal(r)
	return sha256.Sum256(b)
}

// LogExporterOptions configure NativeLogExporter.
type LogExporterOptions struct {
	// Service is the service name for records without a resource service.name
	// (default "unknown_service").
	Service string
	// ServiceInstance is the instance for records without service.instance.id.
	ServiceInstance string
	// Layer is the OAP layer (default "GENERAL").
	Layer string
}

// NativeLogExporter sends logs to OAP's native HTTP log API (POST /v3/logs, a JSON
// array of LogData). It is the recommended LogExporter: it keeps the trace context
// (an OpenTelemetry trace id becomes the log's traceId, so relatedTrace finds it) and
// needs no gRPC. Mapping: resource service.name → service, service.instance.id →
// serviceInstance, Body → text body, SeverityText (else the severity's level name) →
// tag "level", attributes → tags (nested values JSON-encoded), SpanID → tag "span_id",
// TraceID → traceContext.traceId. Logs are sent uncompressed (OAP 10.4 /v3 does not
// document compressed bodies).
type NativeLogExporter struct {
	c    *Client
	opts LogExporterOptions
}

var _ LogExporter = (*NativeLogExporter)(nil)

// NewLogExporter returns the native /v3/logs exporter.
func (c *Client) NewLogExporter(opts LogExporterOptions) *NativeLogExporter {
	if opts.Service == "" {
		opts.Service = "unknown_service"
	}
	if opts.Layer == "" {
		opts.Layer = "GENERAL"
	}
	return &NativeLogExporter{c: c, opts: opts}
}

// NewLogClient returns a batching LogClient over the native exporter. The service name
// comes from opts.Resource["service.name"].
func (c *Client) NewLogClient(opts LogClientOptions) (*BatchingLogClient, error) {
	exp := LogExporterOptions{}
	if s, ok := opts.Resource["service.name"].(string); ok {
		exp.Service = s
	}
	if s, ok := opts.Resource["service.instance.id"].(string); ok {
		exp.ServiceInstance = s
	}
	return NewBatchingLogClient(c.NewLogExporter(exp), opts)
}

// LogData converts one record to the /v3/logs JSON object.
func (e *NativeLogExporter) LogData(r LogRecord) map[string]any {
	service, _ := r.Resource["service.name"].(string)
	if service == "" {
		service = e.opts.Service
	}
	instance, _ := r.Resource["service.instance.id"].(string)
	if instance == "" {
		instance = e.opts.ServiceInstance
	}
	ts := r.Timestamp
	if ts.IsZero() {
		ts = e.c.now()
	}
	level := r.SeverityText
	if level == "" {
		level = levelName(r.Severity)
	}
	tags := []map[string]string{{"key": "level", "value": level}}
	keys := make([]string, 0, len(r.Attributes))
	for k := range r.Attributes {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	for _, k := range keys {
		if k != "level" {
			tags = append(tags, map[string]string{"key": k, "value": tagValue(r.Attributes[k])})
		}
	}
	if r.SpanID != "" {
		tags = append(tags, map[string]string{"key": "span_id", "value": r.SpanID})
	}
	out := map[string]any{
		"timestamp": ts.UnixMilli(),
		"service":   service,
		"body":      map[string]any{"text": map[string]any{"text": r.Body}},
		"tags":      map[string]any{"data": tags},
		"layer":     e.opts.Layer,
	}
	if instance != "" {
		out["serviceInstance"] = instance
	}
	if r.TraceID != "" {
		out["traceContext"] = map[string]any{"traceId": r.TraceID}
	}
	return out
}

// levelName is the named level whose range contains s (TRACE 1-4, DEBUG 5-8, …);
// out-of-range values are clamped to 1..24.
func levelName(s Severity) string {
	n := min(max(int(s), 1), 24)
	return severityNames[(n-1)/4]
}

func tagValue(v AttrValue) string {
	switch x := v.(type) {
	case string:
		return x
	case nil:
		return ""
	case bool, int, int64, float64:
		return fmt.Sprint(x)
	}
	b, err := json.Marshal(v)
	if err != nil {
		return fmt.Sprint(v)
	}
	return string(b)
}

// Export posts the batch as one JSON array.
func (e *NativeLogExporter) Export(ctx context.Context, records []LogRecord, _ ExportOptions) (ExportResult, error) {
	if len(records) == 0 {
		return ExportResult{}, nil
	}
	body := make([]map[string]any, len(records))
	for i, r := range records {
		body[i] = e.LogData(r)
	}
	if _, err := e.c.transport.do(ctx, &request{Method: http.MethodPost, Path: "/v3/logs", JSON: body, Operation: "logs.export"}); err != nil {
		return ExportResult{}, err
	}
	return ExportResult{Accepted: len(records)}, nil
}

// Shutdown is a no-op (the client owns the connections).
func (e *NativeLogExporter) Shutdown(context.Context) error { return nil }
