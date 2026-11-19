package skywalking

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"time"
)

// DefaultSearchWindow is the native SearchTraces window when TraceSearchQuery.Start is
// unset.
const DefaultSearchWindow = time.Hour

// Traces implements TraceQueryClient over OAP's two trace stores: OTLP spans live in
// the Zipkin model (Zipkin query API, 32-hex ids), native SkyWalking segments in the
// native store (GraphQL, opaque ids "<hex>.<n>.<ms>"). The backend selects which store
// is read; see TraceBackend. Get it from Client.Traces. It is safe for concurrent use.
//
// Native spans are mapped to the SPEC model with these rules: TraceID is the native
// trace id verbatim; SpanID is "<segmentId>-<spanId>"; the parent is the same segment's
// parent span or, for a segment root, Refs[0]; kind Entry→server, Exit→client,
// Local→internal (other: unspecified); name = endpoint name; status error when isError;
// attributes = tags plus sw.component, sw.layer, sw.peer, sw.service_instance; events =
// span logs (name = the "event" value, else "log"); further refs become links with the
// attribute sw.ref_type.
//
// OAP lower-cases the names of OTLP spans ("GET /checkout" → "get /checkout") and
// stores attribute values as strings.
type Traces struct {
	c       *Client
	backend TraceBackend
}

var _ TraceQueryClient = (*Traces)(nil)

// Traces returns the TraceQueryClient for Config.TraceBackend.
func (c *Client) Traces() *Traces { return &Traces{c: c, backend: c.cfg.TraceBackend} }

// WithBackend returns a copy that reads from backend (empty = auto).
func (t *Traces) WithBackend(backend TraceBackend) *Traces {
	if backend == "" {
		backend = TraceBackendAuto
	}
	return &Traces{c: t.c, backend: backend}
}

// Backend returns the configured backend.
func (t *Traces) Backend() TraceBackend { return t.backend }

func (t *Traces) checkBackend(op string) error {
	switch t.backend {
	case TraceBackendAuto, TraceBackendZipkin, TraceBackendNative:
		return nil
	}
	return ValidationError(op, "unknown trace backend "+strconv.Quote(string(t.backend)))
}

// IsOTelTraceID reports whether id is a 16 or 32 character hex trace id (OpenTelemetry,
// Zipkin), as opposed to a native SkyWalking id.
func IsOTelTraceID(id string) bool {
	s := strings.ToLower(strings.TrimSpace(id))
	if len(s) != 16 && len(s) != 32 {
		return false
	}
	nonZero := false
	for _, c := range s {
		switch {
		case c == '0':
		case (c >= '1' && c <= '9') || (c >= 'a' && c <= 'f'):
			nonZero = true
		default:
			return false
		}
	}
	return nonZero
}

// GetTrace returns one trace in the normalized model, or nil when it does not exist.
// zipkin: GET /api/v2/trace/{id} (OAP rejects non-hex ids with a VALIDATION error);
// native: GraphQL queryTrace (id kept verbatim); auto: 16/32-hex ids are read from
// Zipkin and, when not found there, from the native store; other ids from the native
// store.
func (t *Traces) GetTrace(ctx context.Context, traceID string) (*Trace, error) {
	const op = "traces.getTrace"
	if err := t.checkBackend(op); err != nil {
		return nil, err
	}
	if err := required(op, "traceId", traceID); err != nil {
		return nil, err
	}
	switch {
	case t.backend == TraceBackendZipkin:
		return t.zipkinTrace(ctx, traceID)
	case t.backend == TraceBackendNative || !IsOTelTraceID(traceID):
		return t.nativeTrace(ctx, strings.TrimSpace(traceID))
	}
	tr, err := t.zipkinTrace(ctx, traceID)
	if err != nil || tr != nil {
		return tr, err
	}
	return t.nativeTrace(ctx, strings.ToLower(strings.TrimSpace(traceID)))
}

func (t *Traces) zipkinTrace(ctx context.Context, traceID string) (*Trace, error) {
	const op = "traces.getTrace"
	id := strings.ToLower(strings.TrimSpace(traceID))
	var spans []ZipkinSpan
	if err := t.zipkinGet(ctx, op, "/api/v2/trace/"+url.PathEscape(id), nil, &spans); err != nil {
		if errors.Is(err, ErrNotFound) {
			return nil, nil
		}
		return nil, err
	}
	return TraceFromZipkin(spans), nil
}

func (t *Traces) nativeTrace(ctx context.Context, traceID string) (*Trace, error) {
	nt, err := t.c.Query().GetTrace(ctx, traceID)
	if err != nil || nt == nil {
		return nil, err
	}
	return TraceFromNative(*nt), nil
}

func (t *Traces) zipkinGet(ctx context.Context, op, path string, q url.Values, out any) error {
	res, err := t.c.zipkin.do(ctx, &request{Method: http.MethodGet, Path: path, Query: q, Operation: op})
	if err != nil {
		return err
	}
	return res.decodeJSON(op, out)
}

// WaitForTrace polls GetTrace until the trace has at least MinSpans spans (test
// helper). Unlike the package-level WaitForTrace it accepts native trace ids (used
// verbatim, NormalizeID KeepTraceID); hex ids are normalized.
func (t *Traces) WaitForTrace(ctx context.Context, traceID string, opts WaitForTraceOptions) (*Trace, error) {
	if opts.NormalizeID == nil && !IsOTelTraceID(traceID) {
		opts.NormalizeID = KeepTraceID
	}
	return WaitForTrace(ctx, t, traceID, opts)
}

// SearchTraces searches traces and summarises them. zipkin and auto: GET
// /api/v2/traces (serviceName, spanName lower-cased like OAP stores it, tags as
// annotationQuery "k=v and …" — keys must be in SW_ZIPKIN_SEARCHABLE_TAG_KEYS —,
// durations in µs, endTs = End (default now), lookback = End − Start only when Start
// is set); native: GraphQL queryTraces (service, operation = endpoint name and needs
// service, tags, durations in ms, window default the last hour).
func (t *Traces) SearchTraces(ctx context.Context, query TraceSearchQuery) ([]TraceSummary, error) {
	const op = "traces.searchTraces"
	if err := t.checkBackend(op); err != nil {
		return nil, err
	}
	if query.Limit < 0 {
		return nil, ValidationError(op, "limit must be a positive integer")
	}
	if query.MinDuration < 0 || query.MaxDuration < 0 {
		return nil, ValidationError(op, "durations must be non-negative")
	}
	query = query.WithDefaults()
	if query.End.IsZero() {
		query.End = t.c.now()
	}
	if !query.Start.IsZero() && query.Start.After(query.End) {
		return nil, ValidationError(op, "start must not be after end")
	}
	if t.backend == TraceBackendNative {
		return t.nativeSearch(ctx, op, query)
	}
	return t.zipkinSearch(ctx, op, query)
}

func (t *Traces) zipkinSearch(ctx context.Context, op string, q TraceSearchQuery) ([]TraceSummary, error) {
	p := url.Values{}
	if q.Service != "" {
		p.Set("serviceName", q.Service)
	}
	if q.Operation != "" {
		p.Set("spanName", strings.ToLower(q.Operation))
	}
	if len(q.Tags) > 0 {
		terms := make([]string, 0, len(q.Tags))
		for _, kv := range tagList(q.Tags) {
			term := kv["key"].(string) + "=" + kv["value"].(string)
			if strings.Contains(term, " and ") {
				return nil, ValidationError(op, `tags must not contain " and "`)
			}
			terms = append(terms, term)
		}
		p.Set("annotationQuery", strings.Join(terms, " and "))
	}
	if q.MinDuration > 0 {
		p.Set("minDuration", strconv.FormatInt(q.MinDuration.Microseconds(), 10))
	}
	if q.MaxDuration > 0 {
		p.Set("maxDuration", strconv.FormatInt(q.MaxDuration.Microseconds(), 10))
	}
	p.Set("endTs", strconv.FormatInt(q.End.UnixMilli(), 10))
	if !q.Start.IsZero() {
		p.Set("lookback", strconv.FormatInt(max(q.End.Sub(q.Start).Milliseconds(), 1), 10))
	}
	p.Set("limit", strconv.Itoa(q.Limit))
	var traces [][]ZipkinSpan
	if err := t.zipkinGet(ctx, op, "/api/v2/traces", p, &traces); err != nil {
		return nil, err
	}
	out := make([]TraceSummary, 0, len(traces))
	for _, spans := range traces {
		if tr := TraceFromZipkin(spans); tr != nil {
			out = append(out, Summarize(*tr))
		}
	}
	return out, nil
}

func (t *Traces) nativeSearch(ctx context.Context, op string, q TraceSearchQuery) ([]TraceSummary, error) {
	if q.Operation != "" && q.Service == "" {
		return nil, ValidationError(op, "operation needs service on the native backend")
	}
	start := q.Start
	if start.IsZero() {
		start = q.End.Add(-DefaultSearchWindow)
	}
	page, err := t.c.Query().QueryTraces(ctx, QueryTracesOptions{
		Service:     q.Service,
		Endpoint:    q.Operation,
		Tags:        q.Tags,
		MinDuration: q.MinDuration,
		MaxDuration: q.MaxDuration,
		Range:       TimeRange{Start: start, End: q.End},
		PageSize:    q.Limit,
	})
	if err != nil {
		return nil, err
	}
	out := make([]TraceSummary, 0, len(page.Traces))
	for _, nt := range page.Traces {
		if tr := TraceFromNative(nt); tr != nil {
			out = append(out, Summarize(*tr))
		}
	}
	return out, nil
}

// ListServices lists service names, sorted and unique. zipkin: GET /api/v2/services
// (OTLP senders); native: the normal services of GraphQL listServices (agents and log
// senders); auto: the union of both. New services appear about 30-35 s after their
// first data.
func (t *Traces) ListServices(ctx context.Context) ([]string, error) {
	const op = "traces.listServices"
	if err := t.checkBackend(op); err != nil {
		return nil, err
	}
	var out []string
	if t.backend != TraceBackendNative {
		var names []string
		if err := t.zipkinGet(ctx, op, "/api/v2/services", nil, &names); err != nil {
			return nil, err
		}
		out = append(out, names...)
	}
	if t.backend != TraceBackendZipkin {
		svcs, err := t.c.Query().ListServices(ctx, "")
		if err != nil {
			return nil, err
		}
		for _, s := range svcs {
			if s.Normal {
				out = append(out, s.Name)
			}
		}
	}
	return uniqueSorted(out), nil
}

// ListOperations lists the operation names of a service, sorted and unique. zipkin: GET
// /api/v2/spans?serviceName= (lower-cased span names); native: the service's endpoints
// (ListEndpoints, at most DefaultEndpointLimit); auto: the union of both.
func (t *Traces) ListOperations(ctx context.Context, service string) ([]string, error) {
	const op = "traces.listOperations"
	if err := t.checkBackend(op); err != nil {
		return nil, err
	}
	if err := required(op, "service", service); err != nil {
		return nil, err
	}
	var out []string
	if t.backend != TraceBackendNative {
		var names []string
		if err := t.zipkinGet(ctx, op, "/api/v2/spans", url.Values{"serviceName": {service}}, &names); err != nil {
			return nil, err
		}
		out = append(out, names...)
	}
	if t.backend != TraceBackendZipkin {
		eps, err := t.c.Query().ListEndpoints(ctx, service, "", 0)
		if err != nil {
			return nil, err
		}
		for _, e := range eps {
			out = append(out, e.Name)
		}
	}
	return uniqueSorted(out), nil
}

// GetDependencies returns service dependency links from the native topology of the
// window (GraphQL getGlobalTopology, default the last hour) with node names as
// parent/child and CallCount 0 (the topology carries no counts). OAP 10.4 does not
// implement Zipkin /dependencies, so the zipkin backend returns UNSUPPORTED; OTLP spans
// create no topology edges.
func (t *Traces) GetDependencies(ctx context.Context, opts DependencyOptions) ([]DependencyLink, error) {
	const op = "traces.getDependencies"
	if err := t.checkBackend(op); err != nil {
		return nil, err
	}
	if t.backend == TraceBackendZipkin {
		return nil, UnsupportedError(op, "OAP does not implement the Zipkin dependencies API")
	}
	topo, err := t.c.Query().Topology(ctx, TimeRange{Start: opts.Start, End: opts.End}, TopologyOptions{})
	if err != nil {
		return nil, err
	}
	return DependenciesFromTopology(topo), nil
}

// DependenciesFromTopology converts topology calls to dependency links (node names,
// CallCount 0), sorted by parent, then child.
func DependenciesFromTopology(topo Topology) []DependencyLink {
	out := make([]DependencyLink, 0, len(topo.Calls))
	for _, c := range topo.Calls {
		p, ch := topo.NodeName(c.Source), topo.NodeName(c.Target)
		if p == "" {
			p = c.Source
		}
		if ch == "" {
			ch = c.Target
		}
		out = append(out, DependencyLink{Parent: p, Child: ch})
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].Parent != out[j].Parent {
			return out[i].Parent < out[j].Parent
		}
		return out[i].Child < out[j].Child
	})
	return out
}

func uniqueSorted(in []string) []string {
	set := make(map[string]bool, len(in))
	out := make([]string, 0, len(in))
	for _, s := range in {
		if s != "" && !set[s] {
			set[s] = true
			out = append(out, s)
		}
	}
	sort.Strings(out)
	return out
}

// Summarize builds a TraceSummary: the root is the earliest span without a parent in
// the trace (else the earliest span); the duration spans from the first start to the
// last end.
func Summarize(t Trace) TraceSummary {
	sum := TraceSummary{TraceID: t.TraceID, SpanCount: len(t.Spans)}
	if len(t.Spans) == 0 {
		return sum
	}
	ids := make(map[string]bool, len(t.Spans))
	for _, s := range t.Spans {
		ids[s.SpanID] = true
	}
	spans := SortSpans(append([]Span(nil), t.Spans...))
	root := spans[0]
	for _, s := range spans {
		if s.ParentSpanID == "" || !ids[s.ParentSpanID] {
			root = s
			break
		}
	}
	start, end := spans[0].StartMicros, spans[0].EndMicros
	for _, s := range spans {
		start = min(start, s.StartMicros)
		end = max(end, s.EndMicros)
	}
	sum.RootServiceName, sum.RootName = root.ServiceName, root.Name
	sum.StartMicros, sum.DurationMicros = start, end-start
	return sum
}

// ZipkinEndpoint is the endpoint of a Zipkin span.
type ZipkinEndpoint struct {
	ServiceName string `json:"serviceName,omitempty"`
	IPv4        string `json:"ipv4,omitempty"`
	IPv6        string `json:"ipv6,omitempty"`
	Port        int    `json:"port,omitempty"`
}

// ZipkinAnnotation is a timestamped event of a Zipkin span (epoch µs).
type ZipkinAnnotation struct {
	Timestamp int64  `json:"timestamp"`
	Value     string `json:"value"`
}

// ZipkinSpan is a Zipkin v2 span as OAP's Zipkin query API returns OTLP spans.
type ZipkinSpan struct {
	TraceID        string             `json:"traceId"`
	ParentID       string             `json:"parentId,omitempty"`
	ID             string             `json:"id"`
	Kind           string             `json:"kind,omitempty"`
	Name           string             `json:"name,omitempty"`
	Timestamp      int64              `json:"timestamp,omitempty"`
	Duration       int64              `json:"duration,omitempty"`
	LocalEndpoint  *ZipkinEndpoint    `json:"localEndpoint,omitempty"`
	RemoteEndpoint *ZipkinEndpoint    `json:"remoteEndpoint,omitempty"`
	Annotations    []ZipkinAnnotation `json:"annotations,omitempty"`
	Tags           map[string]string  `json:"tags,omitempty"`
}

// isBookkeepingTag reports tags OAP adds while converting OTLP spans (otel.*, error,
// w3c.tracestate); they become status fields or are dropped from the attributes.
func isBookkeepingTag(key string) bool {
	return strings.HasPrefix(key, "otel.") || key == "error" || key == "w3c.tracestate"
}

var zipkinKinds = map[string]SpanKind{"SERVER": SpanKindServer, "CLIENT": SpanKindClient, "PRODUCER": SpanKindProducer, "CONSUMER": SpanKindConsumer}

// ToSpan converts a Zipkin span to the SPEC model: kind lower-cased (missing =
// internal, unknown = unspecified), status from otel.status_code (STATUS_CODE_ERROR /
// ERROR → error with otel.status_description, STATUS_CODE_OK / OK → ok, an error tag
// alone → error), attributes = tags without OAP's bookkeeping tags (otel.*, error,
// w3c.tracestate), events from annotations ("name|attributes JSON|dropped").
func (z ZipkinSpan) ToSpan() Span {
	traceID := strings.ToLower(z.TraceID)
	if len(traceID) < 32 {
		traceID = strings.Repeat("0", 32-len(traceID)) + traceID
	}
	s := Span{
		TraceID:        traceID,
		SpanID:         strings.ToLower(z.ID),
		ParentSpanID:   strings.ToLower(z.ParentID),
		Name:           z.Name,
		Kind:           SpanKindInternal,
		StartMicros:    z.Timestamp,
		DurationMicros: z.Duration,
		EndMicros:      z.Timestamp + z.Duration,
		Status:         SpanStatus{Code: SpanStatusUnset},
		Attributes:     map[string]any{},
		Events:         []SpanEvent{},
		Links:          []SpanLink{},
	}
	if z.Kind != "" {
		s.Kind = zipkinKinds[strings.ToUpper(z.Kind)]
		if s.Kind == "" {
			s.Kind = SpanKindUnspecified
		}
	}
	if z.LocalEndpoint != nil {
		s.ServiceName = z.LocalEndpoint.ServiceName
	}
	_, hasError := z.Tags["error"]
	switch strings.ToUpper(z.Tags["otel.status_code"]) {
	case "STATUS_CODE_ERROR", "ERROR":
		s.Status = SpanStatus{Code: SpanStatusError, Message: z.Tags["otel.status_description"]}
	case "STATUS_CODE_OK", "OK":
		s.Status = SpanStatus{Code: SpanStatusOK}
	default:
		if hasError {
			s.Status = SpanStatus{Code: SpanStatusError}
		}
	}
	for k, v := range z.Tags {
		if !isBookkeepingTag(k) {
			s.Attributes[k] = v
		}
	}
	for _, a := range z.Annotations {
		s.Events = append(s.Events, zipkinEvent(a))
	}
	return s
}

func zipkinEvent(a ZipkinAnnotation) SpanEvent {
	ev := SpanEvent{Name: a.Value, TimeMicros: a.Timestamp, Attributes: map[string]any{}}
	first := strings.Index(a.Value, "|")
	last := strings.LastIndex(a.Value, "|")
	if first <= 0 || last <= first {
		return ev
	}
	raw := a.Value[first+1 : last]
	if raw == "" {
		raw = "{}"
	}
	dec := json.NewDecoder(strings.NewReader(raw))
	dec.UseNumber()
	var attrs map[string]any
	if dec.Decode(&attrs) != nil || attrs == nil {
		return ev
	}
	ev.Name, ev.Attributes = a.Value[:first], numbersToGo(attrs)
	return ev
}

// TraceFromZipkin converts the spans of one Zipkin trace (nil for no spans); spans are
// sorted by start time, then span id.
func TraceFromZipkin(spans []ZipkinSpan) *Trace {
	if len(spans) == 0 {
		return nil
	}
	out := make([]Span, len(spans))
	for i, z := range spans {
		out[i] = z.ToSpan()
	}
	return &Trace{TraceID: out[0].TraceID, Spans: SortSpans(out)}
}

// NativeSpanID returns the SPEC span id of a native span: "<segmentId>-<spanId>".
func NativeSpanID(segmentID string, spanID int) string {
	return segmentID + "-" + strconv.Itoa(spanID)
}

// ToSpan converts a native span to the SPEC model (rules in the Traces doc).
func (n NativeSpan) ToSpan() Span {
	s := Span{
		TraceID:        n.TraceID,
		SpanID:         NativeSpanID(n.SegmentID, n.SpanID),
		Name:           n.EndpointName,
		ServiceName:    n.ServiceCode,
		StartMicros:    n.StartTime * 1000,
		EndMicros:      max(n.EndTime, n.StartTime) * 1000,
		DurationMicros: (max(n.EndTime, n.StartTime) - n.StartTime) * 1000,
		Status:         SpanStatus{Code: SpanStatusUnset},
		Attributes:     map[string]any{},
		Events:         []SpanEvent{},
		Links:          []SpanLink{},
	}
	links := n.Refs
	switch {
	case n.ParentSpanID >= 0:
		s.ParentSpanID = NativeSpanID(n.SegmentID, n.ParentSpanID)
	case len(n.Refs) > 0:
		s.ParentSpanID = NativeSpanID(n.Refs[0].ParentSegmentID, n.Refs[0].ParentSpanID)
		links = n.Refs[1:]
	}
	for _, r := range links {
		s.Links = append(s.Links, SpanLink{TraceID: r.TraceID, SpanID: NativeSpanID(r.ParentSegmentID, r.ParentSpanID), Attributes: map[string]any{"sw.ref_type": r.Type}})
	}
	switch n.Type {
	case "Entry":
		s.Kind = SpanKindServer
	case "Exit":
		s.Kind = SpanKindClient
	case "Local":
		s.Kind = SpanKindInternal
	default:
		s.Kind = SpanKindUnspecified
	}
	if n.IsError {
		s.Status = SpanStatus{Code: SpanStatusError}
	}
	for _, t := range n.Tags {
		s.Attributes[t.Key] = t.Value
	}
	for k, v := range map[string]string{"sw.component": n.Component, "sw.layer": n.Layer, "sw.peer": n.Peer, "sw.service_instance": n.ServiceInstanceName} {
		if v != "" {
			s.Attributes[k] = v
		}
	}
	for _, l := range n.Logs {
		ev := SpanEvent{Name: "log", TimeMicros: l.Time * 1000, Attributes: map[string]any{}}
		for _, kv := range l.Data {
			ev.Attributes[kv.Key] = kv.Value
		}
		if name, ok := ev.Attributes["event"].(string); ok && name != "" {
			ev.Name = name
		}
		s.Events = append(s.Events, ev)
	}
	return s
}

// TraceFromNative converts a native trace to the SPEC model (nil for no spans).
func TraceFromNative(nt NativeTrace) *Trace {
	if len(nt.Spans) == 0 {
		return nil
	}
	out := make([]Span, len(nt.Spans))
	for i, n := range nt.Spans {
		out[i] = n.ToSpan()
	}
	id := nt.TraceID
	if id == "" {
		id = out[0].TraceID
	}
	return &Trace{TraceID: id, Spans: SortSpans(out)}
}
