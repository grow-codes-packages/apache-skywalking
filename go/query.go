package skywalking

import (
	"context"
	"encoding/base64"
	"sort"
	"strings"
	"time"
)

// QueryClient is SkyWalking's native GraphQL query API (the ticket's
// SkyWalkingQueryClient): metadata, native traces, logs, topology and raw GraphQL.
// Get it from Client.Query. It is safe for concurrent use.
type QueryClient struct{ c *Client }

// Query returns the GraphQL query client.
func (c *Client) Query() *QueryClient { return &QueryClient{c: c} }

// ServiceID returns the OAP id of a normal (real) service: base64(name) + ".1".
func ServiceID(service string) string {
	return base64.StdEncoding.EncodeToString([]byte(service)) + ".1"
}

// InstanceID returns the OAP id of a service instance: ServiceID + "_" + base64(instance).
func InstanceID(service, instance string) string {
	return ServiceID(service) + "_" + base64.StdEncoding.EncodeToString([]byte(instance))
}

// EndpointID returns the OAP id of an endpoint: ServiceID + "_" + base64(endpoint).
func EndpointID(service, endpoint string) string {
	return ServiceID(service) + "_" + base64.StdEncoding.EncodeToString([]byte(endpoint))
}

// KeyValue is a SkyWalking tag (order and duplicates are kept).
type KeyValue struct {
	Key   string `json:"key"`
	Value string `json:"value"`
}

// Service is an OAP service (metadata).
type Service struct {
	ID        string   `json:"id"`
	Name      string   `json:"name"`
	Group     string   `json:"group,omitempty"`
	ShortName string   `json:"shortName,omitempty"`
	Layers    []string `json:"layers"`
	// Normal is false for virtual (conjectured) services.
	Normal bool `json:"normal"`
}

// Endpoint is an OAP endpoint (an operation of a native service).
type Endpoint struct {
	ID   string `json:"id"`
	Name string `json:"name"`
}

// Instance is a service instance.
type Instance struct {
	ID           string            `json:"id"`
	Name         string            `json:"name"`
	Language     string            `json:"language,omitempty"`
	InstanceUUID string            `json:"instanceUUID,omitempty"`
	Attributes   map[string]string `json:"attributes"`
}

const serviceFields = "id name group shortName layers normal"

// ListServices lists the services OAP knows (GraphQL listServices), optionally of one
// layer ("GENERAL", …; empty = all layers; an unknown layer returns an empty list).
// OAP has no time range for this call. Services that only sent OTLP spans are not
// native metadata (use Traces().ListServices); new services appear about 30 s after
// their first data.
func (q *QueryClient) ListServices(ctx context.Context, layer string) ([]Service, error) {
	var l any
	if layer != "" {
		l = layer
	}
	var out []Service
	err := q.c.gql(ctx, "query.listServices", "query($l:String){listServices(layer:$l){"+serviceFields+"}}",
		map[string]any{"l": l}, "listServices", &out)
	if err != nil {
		return nil, err
	}
	return nonNilSlice(out), nil
}

// FindService returns one service by name, or nil when OAP does not know it.
func (q *QueryClient) FindService(ctx context.Context, name string) (*Service, error) {
	const op = "query.findService"
	if err := required(op, "service", name); err != nil {
		return nil, err
	}
	var out *Service
	if err := q.c.gql(ctx, op, "query($n:String!){findService(serviceName:$n){"+serviceFields+"}}",
		map[string]any{"n": name}, "findService", &out); err != nil {
		return nil, err
	}
	return out, nil
}

// DefaultEndpointLimit is the number of endpoints ListEndpoints returns.
const DefaultEndpointLimit = 100

// ListEndpoints lists the endpoints of a service (GraphQL findEndpoint), at most limit
// (0 = DefaultEndpointLimit). keyword is OAP's token match, not a substring match:
// "downstream" finds "/native/downstream", "down" does not; empty lists all. Only
// native services (agents, /v3/segments) have endpoints; OTLP spans create none.
func (q *QueryClient) ListEndpoints(ctx context.Context, service, keyword string, limit int) ([]Endpoint, error) {
	const op = "query.listEndpoints"
	if err := required(op, "service", service); err != nil {
		return nil, err
	}
	if limit < 0 {
		return nil, ValidationError(op, "limit must not be negative")
	}
	if limit == 0 {
		limit = DefaultEndpointLimit
	}
	var kw any
	if keyword != "" {
		kw = keyword
	}
	var out []Endpoint
	err := q.c.gql(ctx, op, "query($s:ID!,$k:String,$n:Int!){findEndpoint(serviceId:$s,keyword:$k,limit:$n){id name}}",
		map[string]any{"s": ServiceID(service), "k": kw, "n": limit}, "findEndpoint", &out)
	if err != nil {
		return nil, err
	}
	return nonNilSlice(out), nil
}

// ListInstances lists the instances of a service seen in the time range (GraphQL
// listInstances; zero range = the last hour).
func (q *QueryClient) ListInstances(ctx context.Context, service string, r TimeRange) ([]Instance, error) {
	const op = "query.listInstances"
	if err := required(op, "service", service); err != nil {
		return nil, err
	}
	d, err := q.c.duration(ctx, op, r)
	if err != nil {
		return nil, err
	}
	var raw []struct {
		ID           string `json:"id"`
		Name         string `json:"name"`
		Language     string `json:"language"`
		InstanceUUID string `json:"instanceUUID"`
		Attributes   []struct {
			Name  string `json:"name"`
			Value string `json:"value"`
		} `json:"attributes"`
	}
	err = q.c.gql(ctx, op, "query($s:ID!,$d:Duration!){listInstances(serviceId:$s,duration:$d){id name language instanceUUID attributes{name value}}}",
		map[string]any{"s": ServiceID(service), "d": d}, "listInstances", &raw)
	if err != nil {
		return nil, err
	}
	out := make([]Instance, len(raw))
	for i, in := range raw {
		attrs := make(map[string]string, len(in.Attributes))
		for _, a := range in.Attributes {
			attrs[a.Name] = a.Value
		}
		out[i] = Instance{ID: in.ID, Name: in.Name, Language: in.Language, InstanceUUID: in.InstanceUUID, Attributes: attrs}
	}
	return out, nil
}

// TraceState filters native traces by outcome.
type TraceState string

// Trace states (empty = TraceStateAll).
const (
	TraceStateAll     TraceState = "ALL"
	TraceStateSuccess TraceState = "SUCCESS"
	TraceStateError   TraceState = "ERROR"
)

// TraceOrder orders native trace results.
type TraceOrder string

// Trace orders (empty = TraceOrderStartTime).
const (
	TraceOrderStartTime TraceOrder = "BY_START_TIME"
	TraceOrderDuration  TraceOrder = "BY_DURATION"
)

// DefaultPageSize is the page size of QueryTraces and QueryLogs.
const DefaultPageSize = 20

// QueryTracesOptions filter QueryTraces. Zero values mean "not set".
type QueryTracesOptions struct {
	// Service is the service name (converted to its id with ServiceID).
	Service string
	// Instance is an instance name of Service.
	Instance string
	// Endpoint is an endpoint name of Service.
	Endpoint string
	// TraceID is a native trace id.
	TraceID string
	// TraceState is ALL (default), SUCCESS or ERROR.
	TraceState TraceState
	// MinDuration and MaxDuration bound the trace duration (millisecond precision).
	MinDuration time.Duration
	MaxDuration time.Duration
	// Range is the time window (zero = the last hour).
	Range TimeRange
	// Tags are exact tag matches; OAP only filters keys in SW_SEARCHABLE_TAG_KEYS
	// (other keys silently match nothing).
	Tags map[string]string
	// Order is BY_START_TIME (default) or BY_DURATION.
	Order TraceOrder
	// PageNum is the 1-based page (default 1); PageSize defaults to DefaultPageSize.
	PageNum  int
	PageSize int
}

// NativeTraceRef is the cross-segment parent of a native span.
type NativeTraceRef struct {
	TraceID         string `json:"traceId"`
	ParentSegmentID string `json:"parentSegmentId"`
	ParentSpanID    int    `json:"parentSpanId"`
	// Type is CROSS_PROCESS or CROSS_THREAD.
	Type string `json:"type"`
}

// NativeLogEntry is a log attached to a native span.
type NativeLogEntry struct {
	// Time is the epoch time in milliseconds.
	Time int64      `json:"time"`
	Data []KeyValue `json:"data"`
}

// NativeSpan is a SkyWalking span as GraphQL returns it. Ids are opaque strings; a span
// is identified by (SegmentID, SpanID); ParentSpanID -1 marks the segment root whose
// parent, if any, is Refs[0]. Times are epoch milliseconds.
type NativeSpan struct {
	TraceID             string           `json:"traceId"`
	SegmentID           string           `json:"segmentId"`
	SpanID              int              `json:"spanId"`
	ParentSpanID        int              `json:"parentSpanId"`
	Refs                []NativeTraceRef `json:"refs"`
	ServiceCode         string           `json:"serviceCode"`
	ServiceInstanceName string           `json:"serviceInstanceName"`
	StartTime           int64            `json:"startTime"`
	EndTime             int64            `json:"endTime"`
	EndpointName        string           `json:"endpointName"`
	// Type is Entry, Exit or Local.
	Type      string           `json:"type"`
	Peer      string           `json:"peer"`
	Component string           `json:"component"`
	IsError   bool             `json:"isError"`
	Layer     string           `json:"layer"`
	Tags      []KeyValue       `json:"tags"`
	Logs      []NativeLogEntry `json:"logs"`
}

// NativeTrace is a native SkyWalking trace (all segments).
type NativeTrace struct {
	TraceID string       `json:"traceId"`
	Spans   []NativeSpan `json:"spans"`
}

// TracePage is one page of QueryTraces. OAP returns no total: HasMore is true when the
// page came back full.
type TracePage struct {
	Traces []NativeTrace `json:"traces"`
	// RetrievedStart and RetrievedEnd are the window the server searched (zero when not
	// reported).
	RetrievedStart time.Time `json:"retrievedStart"`
	RetrievedEnd   time.Time `json:"retrievedEnd"`
	PageNum        int       `json:"pageNum"`
	HasMore        bool      `json:"hasMore"`
	PageSize       int       `json:"pageSize"`
}

const nativeSpanFields = "traceId segmentId spanId parentSpanId refs{traceId parentSegmentId parentSpanId type} " +
	"serviceCode serviceInstanceName startTime endTime endpointName type peer component isError layer " +
	"tags{key value} logs{time data{key value}}"

// QueryTraces searches native traces (GraphQL queryTraces, whole traces). Traces sent
// over OTLP are not stored here (use Traces with the zipkin backend).
func (q *QueryClient) QueryTraces(ctx context.Context, opts QueryTracesOptions) (TracePage, error) {
	const op = "query.queryTraces"
	if (opts.Instance != "" || opts.Endpoint != "") && opts.Service == "" {
		return TracePage{}, ValidationError(op, "instance and endpoint need service")
	}
	if opts.MinDuration < 0 || opts.MaxDuration < 0 || opts.PageNum < 0 || opts.PageSize < 0 {
		return TracePage{}, ValidationError(op, "durations and paging must not be negative")
	}
	page, size := max(opts.PageNum, 1), opts.PageSize
	if size == 0 {
		size = DefaultPageSize
	}
	d, err := q.c.duration(ctx, op, opts.Range)
	if err != nil {
		return TracePage{}, err
	}
	state, order := opts.TraceState, opts.Order
	if state == "" {
		state = TraceStateAll
	}
	if order == "" {
		order = TraceOrderStartTime
	}
	cond := map[string]any{
		"queryDuration": d,
		"traceState":    string(state),
		"queryOrder":    string(order),
		"paging":        map[string]any{"pageNum": page, "pageSize": size},
	}
	if opts.Service != "" {
		cond["serviceId"] = ServiceID(opts.Service)
	}
	if opts.Instance != "" {
		cond["serviceInstanceId"] = InstanceID(opts.Service, opts.Instance)
	}
	if opts.Endpoint != "" {
		cond["endpointId"] = EndpointID(opts.Service, opts.Endpoint)
	}
	if opts.TraceID != "" {
		cond["traceId"] = opts.TraceID
	}
	if opts.MinDuration > 0 {
		cond["minTraceDuration"] = opts.MinDuration.Milliseconds()
	}
	if opts.MaxDuration > 0 {
		cond["maxTraceDuration"] = opts.MaxDuration.Milliseconds()
	}
	if len(opts.Tags) > 0 {
		cond["tags"] = tagList(opts.Tags)
	}
	var out struct {
		Traces []struct {
			Spans []NativeSpan `json:"spans"`
		} `json:"traces"`
		Retrieved *struct {
			StartTime int64 `json:"startTime"`
			EndTime   int64 `json:"endTime"`
		} `json:"retrievedTimeRange"`
	}
	err = q.c.gql(ctx, op, "query($c:TraceQueryCondition){queryTraces(condition:$c){traces{spans{"+nativeSpanFields+"}} retrievedTimeRange{startTime endTime}}}",
		map[string]any{"c": cond}, "queryTraces", &out)
	if err != nil {
		return TracePage{}, err
	}
	res := TracePage{Traces: []NativeTrace{}, PageNum: page, PageSize: size, HasMore: len(out.Traces) >= size}
	if out.Retrieved != nil {
		res.RetrievedStart, res.RetrievedEnd = time.UnixMilli(out.Retrieved.StartTime), time.UnixMilli(out.Retrieved.EndTime)
	}
	for _, t := range out.Traces {
		if len(t.Spans) == 0 {
			continue
		}
		res.Traces = append(res.Traces, NativeTrace{TraceID: t.Spans[0].TraceID, Spans: normalizeNativeSpans(t.Spans)})
	}
	return res, nil
}

// GetTrace returns a native trace by id (GraphQL queryTrace), or nil when OAP has no
// such trace. The id is kept verbatim ("<hex>.<n>.<ms>").
func (q *QueryClient) GetTrace(ctx context.Context, traceID string) (*NativeTrace, error) {
	const op = "query.getTrace"
	if err := required(op, "traceId", traceID); err != nil {
		return nil, err
	}
	var out struct {
		Spans []NativeSpan `json:"spans"`
	}
	if err := q.c.gql(ctx, op, "query($id:ID!){queryTrace(traceId:$id){spans{"+nativeSpanFields+"}}}",
		map[string]any{"id": strings.TrimSpace(traceID)}, "queryTrace", &out); err != nil {
		return nil, err
	}
	if len(out.Spans) == 0 {
		return nil, nil
	}
	return &NativeTrace{TraceID: out.Spans[0].TraceID, Spans: normalizeNativeSpans(out.Spans)}, nil
}

func normalizeNativeSpans(spans []NativeSpan) []NativeSpan {
	for i := range spans {
		spans[i].Refs = nonNilSlice(spans[i].Refs)
		spans[i].Tags = nonNilSlice(spans[i].Tags)
		spans[i].Logs = nonNilSlice(spans[i].Logs)
	}
	return spans
}

// QueryLogsOptions filter QueryLogs. Either a time range (default: the last hour) or
// TraceID is used.
type QueryLogsOptions struct {
	// Service, Instance and Endpoint are names (converted to ids).
	Service  string
	Instance string
	Endpoint string
	// TraceID (with optional SegmentID and SpanID) selects logs of one trace
	// (relatedTrace). OTLP logs keep their trace id only in the trace_id tag.
	TraceID   string
	SegmentID string
	SpanID    *int
	// Tags are exact tag matches; OAP only filters keys in SW_SEARCHABLE_LOGS_TAG_KEYS.
	Tags map[string]string
	// Keywords / ExcludingKeywords filter content on storages that support it
	// (Client.Capabilities().LogsByKeywords; ignored on BanyanDB).
	Keywords          []string
	ExcludingKeywords []string
	// Range is the time window (zero = the last hour, or none when TraceID is set).
	Range TimeRange
	// Order is OrderDesc (default) or OrderAsc.
	Order SortOrder
	// PageNum is the 1-based page (default 1); PageSize defaults to DefaultPageSize
	// (OAP accepts up to 1000).
	PageNum  int
	PageSize int
}

// NativeLog is a log record as OAP stores it.
type NativeLog struct {
	ServiceName         string    `json:"serviceName"`
	ServiceID           string    `json:"serviceId"`
	ServiceInstanceName string    `json:"serviceInstanceName,omitempty"`
	EndpointName        string    `json:"endpointName,omitempty"`
	TraceID             string    `json:"traceId,omitempty"`
	Timestamp           time.Time `json:"timestamp"`
	// ContentType is TEXT, JSON or YAML.
	ContentType string     `json:"contentType"`
	Content     string     `json:"content"`
	Tags        []KeyValue `json:"tags"`
}

// Tag returns the first value of a tag.
func (l NativeLog) Tag(key string) (string, bool) {
	for _, t := range l.Tags {
		if t.Key == key {
			return t.Value, true
		}
	}
	return "", false
}

// LogPage is one page of QueryLogs. HasMore is true when the page came back full.
type LogPage struct {
	Logs []NativeLog `json:"logs"`
	// ErrorReason is OAP's explanation when the query could not run.
	ErrorReason string `json:"errorReason,omitempty"`
	PageNum     int    `json:"pageNum"`
	PageSize    int    `json:"pageSize"`
	HasMore     bool   `json:"hasMore"`
}

const logFields = "errorReason logs{serviceName serviceId serviceInstanceName endpointName traceId timestamp contentType content tags{key value}}"

// QueryLogs searches stored logs (GraphQL queryLogs).
func (q *QueryClient) QueryLogs(ctx context.Context, opts QueryLogsOptions) (LogPage, error) {
	const op = "query.queryLogs"
	if (opts.Instance != "" || opts.Endpoint != "") && opts.Service == "" {
		return LogPage{}, ValidationError(op, "instance and endpoint need service")
	}
	if opts.PageNum < 0 || opts.PageSize < 0 {
		return LogPage{}, ValidationError(op, "paging must not be negative")
	}
	if (opts.SegmentID != "" || opts.SpanID != nil) && opts.TraceID == "" {
		return LogPage{}, ValidationError(op, "segmentId and spanId need traceId")
	}
	page, size := max(opts.PageNum, 1), opts.PageSize
	if size == 0 {
		size = DefaultPageSize
	}
	cond := map[string]any{"paging": map[string]any{"pageNum": page, "pageSize": size}}
	if opts.TraceID == "" || !opts.Range.IsZero() {
		d, err := q.c.duration(ctx, op, opts.Range)
		if err != nil {
			return LogPage{}, err
		}
		cond["queryDuration"] = d
	}
	if opts.Service != "" {
		cond["serviceId"] = ServiceID(opts.Service)
	}
	if opts.Instance != "" {
		cond["serviceInstanceId"] = InstanceID(opts.Service, opts.Instance)
	}
	if opts.Endpoint != "" {
		cond["endpointId"] = EndpointID(opts.Service, opts.Endpoint)
	}
	if opts.TraceID != "" {
		rt := map[string]any{"traceId": opts.TraceID}
		if opts.SegmentID != "" {
			rt["segmentId"] = opts.SegmentID
		}
		if opts.SpanID != nil {
			rt["spanId"] = *opts.SpanID
		}
		cond["relatedTrace"] = rt
	}
	if len(opts.Tags) > 0 {
		cond["tags"] = tagList(opts.Tags)
	}
	if len(opts.Keywords) > 0 {
		cond["keywordsOfContent"] = opts.Keywords
	}
	if len(opts.ExcludingKeywords) > 0 {
		cond["excludingKeywordsOfContent"] = opts.ExcludingKeywords
	}
	switch opts.Order {
	case "", OrderDesc:
		cond["queryOrder"] = "DES"
	case OrderAsc:
		cond["queryOrder"] = "ASC"
	default:
		return LogPage{}, ValidationError(op, "order must be desc or asc")
	}
	var out struct {
		ErrorReason *string `json:"errorReason"`
		Logs        []struct {
			ServiceName         string     `json:"serviceName"`
			ServiceID           string     `json:"serviceId"`
			ServiceInstanceName *string    `json:"serviceInstanceName"`
			EndpointName        *string    `json:"endpointName"`
			TraceID             *string    `json:"traceId"`
			Timestamp           int64      `json:"timestamp"`
			ContentType         string     `json:"contentType"`
			Content             string     `json:"content"`
			Tags                []KeyValue `json:"tags"`
		} `json:"logs"`
	}
	if err := q.c.gql(ctx, op, "query($c:LogQueryCondition){queryLogs(condition:$c){"+logFields+"}}",
		map[string]any{"c": cond}, "queryLogs", &out); err != nil {
		return LogPage{}, err
	}
	res := LogPage{Logs: make([]NativeLog, len(out.Logs)), PageNum: page, PageSize: size, HasMore: len(out.Logs) >= size}
	if out.ErrorReason != nil {
		res.ErrorReason = *out.ErrorReason
	}
	for i, l := range out.Logs {
		res.Logs[i] = NativeLog{
			ServiceName: l.ServiceName, ServiceID: l.ServiceID, ServiceInstanceName: deref(l.ServiceInstanceName),
			EndpointName: deref(l.EndpointName), TraceID: deref(l.TraceID), Timestamp: time.UnixMilli(l.Timestamp),
			ContentType: l.ContentType, Content: l.Content, Tags: nonNilSlice(l.Tags),
		}
	}
	return res, nil
}

// TopologyNode is a service in the topology. The virtual "User" node has IsReal false.
type TopologyNode struct {
	ID     string   `json:"id"`
	Name   string   `json:"name"`
	Type   string   `json:"type,omitempty"`
	IsReal bool     `json:"isReal"`
	Layers []string `json:"layers"`
}

// TopologyCall is an edge between two node ids.
type TopologyCall struct {
	ID               string   `json:"id"`
	Source           string   `json:"source"`
	Target           string   `json:"target"`
	DetectPoints     []string `json:"detectPoints"`
	SourceComponents []string `json:"sourceComponents"`
	TargetComponents []string `json:"targetComponents"`
}

// Topology is a service graph. OAP reports no call counts here.
type Topology struct {
	Nodes []TopologyNode `json:"nodes"`
	Calls []TopologyCall `json:"calls"`
}

// NodeName returns the name of a node id ("" when unknown).
func (t Topology) NodeName(id string) string {
	for _, n := range t.Nodes {
		if n.ID == id {
			return n.Name
		}
	}
	return ""
}

const topologyFields = "nodes{id name type isReal layers} calls{id source target detectPoints sourceComponents targetComponents}"

// TopologyOptions narrow Topology.
type TopologyOptions struct {
	// Service limits the graph to one service's neighbourhood (getServiceTopology).
	Service string
	// Layer limits the global graph to one layer.
	Layer string
}

// Topology returns the service topology of the time range (zero range = the last
// hour): GraphQL getGlobalTopology, or getServiceTopology when opts.Service is set.
// Only native traffic creates edges; the virtual "User" node is included.
func (q *QueryClient) Topology(ctx context.Context, r TimeRange, opts TopologyOptions) (Topology, error) {
	const op = "query.topology"
	d, err := q.c.duration(ctx, op, r)
	if err != nil {
		return Topology{}, err
	}
	var out Topology
	if opts.Service != "" {
		err = q.c.gql(ctx, op, "query($s:ID!,$d:Duration!){getServiceTopology(serviceId:$s,duration:$d){"+topologyFields+"}}",
			map[string]any{"s": ServiceID(opts.Service), "d": d}, "getServiceTopology", &out)
	} else {
		var layer any
		if opts.Layer != "" {
			layer = opts.Layer
		}
		err = q.c.gql(ctx, op, "query($d:Duration!,$l:String){getGlobalTopology(duration:$d,layer:$l){"+topologyFields+"}}",
			map[string]any{"d": d, "l": layer}, "getGlobalTopology", &out)
	}
	if err != nil {
		return Topology{}, err
	}
	return normalizeTopology(out), nil
}

func normalizeTopology(t Topology) Topology {
	t.Nodes = nonNilSlice(t.Nodes)
	t.Calls = nonNilSlice(t.Calls)
	for i := range t.Nodes {
		t.Nodes[i].Layers = nonNilSlice(t.Nodes[i].Layers)
	}
	for i := range t.Calls {
		t.Calls[i].DetectPoints = nonNilSlice(t.Calls[i].DetectPoints)
		t.Calls[i].SourceComponents = nonNilSlice(t.Calls[i].SourceComponents)
		t.Calls[i].TargetComponents = nonNilSlice(t.Calls[i].TargetComponents)
	}
	return t
}

// ListLayers returns the layers OAP knows (GraphQL listLayers), sorted.
func (q *QueryClient) ListLayers(ctx context.Context) ([]string, error) {
	var out []string
	if err := q.c.gql(ctx, "query.listLayers", "{listLayers}", nil, "listLayers", &out); err != nil {
		return nil, err
	}
	sort.Strings(out)
	return nonNilSlice(out), nil
}

// ListLogTagKeys returns the searchable log tag keys seen in the range (GraphQL
// queryLogTagAutocompleteKeys), sorted.
func (q *QueryClient) ListLogTagKeys(ctx context.Context, r TimeRange) ([]string, error) {
	return q.tagKeys(ctx, "query.listLogTagKeys", "queryLogTagAutocompleteKeys", r)
}

// ListTraceTagKeys returns the searchable native trace tag keys seen in the range (GraphQL
// queryTraceTagAutocompleteKeys), sorted.
func (q *QueryClient) ListTraceTagKeys(ctx context.Context, r TimeRange) ([]string, error) {
	return q.tagKeys(ctx, "query.listTraceTagKeys", "queryTraceTagAutocompleteKeys", r)
}

func (q *QueryClient) tagKeys(ctx context.Context, op, field string, r TimeRange) ([]string, error) {
	d, err := q.c.duration(ctx, op, r)
	if err != nil {
		return nil, err
	}
	var out []string
	if err := q.c.gql(ctx, op, "query($d:Duration!){"+field+"(duration:$d)}", map[string]any{"d": d}, field, &out); err != nil {
		return nil, err
	}
	sort.Strings(out)
	return nonNilSlice(out), nil
}

// GraphQL runs a raw GraphQL query. HTTP failures are errors (a query OAP cannot
// parse is a VALIDATION error); a 200 response is returned as is, including GraphQL
// errors and partial data (see GraphQLResponse.Err).
func (q *QueryClient) GraphQL(ctx context.Context, query string, variables map[string]any) (*GraphQLResponse, error) {
	const op = "query.graphql"
	if err := required(op, "query", query); err != nil {
		return nil, err
	}
	return q.c.graphql(ctx, op, query, variables)
}

func tagList(tags map[string]string) []map[string]any {
	keys := make([]string, 0, len(tags))
	for k := range tags {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	out := make([]map[string]any, len(keys))
	for i, k := range keys {
		out[i] = map[string]any{"key": k, "value": tags[k]}
	}
	return out
}

func required(op, what, value string) error {
	if strings.TrimSpace(value) == "" {
		return ValidationError(op, what+" is required")
	}
	return nil
}

func nonNilSlice[T any](s []T) []T {
	if s == nil {
		return []T{}
	}
	return s
}

func deref(s *string) string {
	if s == nil {
		return ""
	}
	return *s
}
