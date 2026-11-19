package skywalking

import (
	"context"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/url"
	"sort"
	"strings"
	"sync"
	"time"

	collogs "go.opentelemetry.io/proto/otlp/collector/logs/v1"
	commonpb "go.opentelemetry.io/proto/otlp/common/v1"
	logspb "go.opentelemetry.io/proto/otlp/logs/v1"
	resourcepb "go.opentelemetry.io/proto/otlp/resource/v1"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/credentials"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/encoding/gzip"
	"google.golang.org/grpc/metadata"
	grpcstatus "google.golang.org/grpc/status"
)

// OTLPLogExporter sends logs to OAP's OTLP logs receiver over gRPC
// (LogsService/Export on Config.OTLPEndpoint, port 11800). OAP stores them as native
// logs: service.name → service, service.instance.id → instance, body → content,
// attributes → tags. OAP drops the top-level OTLP trace and span ids, so the exporter
// also copies them into the attributes "trace_id" and "span_id" (searchable when the
// server lists them in SW_SEARCHABLE_LOGS_TAG_KEYS). Prefer NativeLogExporter unless
// logs must go through OTLP. Config.Authentication is sent as the "authentication"
// metadata and Config.OTLPHeaders as metadata.
type OTLPLogExporter struct {
	conn     *grpc.ClientConn
	client   collogs.LogsServiceClient
	md       metadata.MD
	secrets  []string
	timeout  time.Duration
	shutOnce sync.Once
}

var _ LogExporter = (*OTLPLogExporter)(nil)

// NewOTLPLogExporter dials Config.OTLPEndpoint lazily (http:// = plaintext, https:// =
// TLS with Config.TLS). The protocol must be gRPC.
func (c *Client) NewOTLPLogExporter() (*OTLPLogExporter, error) {
	if c.cfg.OTLPProtocol != ProtocolGRPC {
		return nil, ConfigError("otlp log exporter supports grpc only")
	}
	u, err := url.Parse(c.cfg.OTLPEndpoint)
	if err != nil || u.Host == "" {
		return nil, ConfigError("otlpEndpoint is invalid")
	}
	creds := insecure.NewCredentials()
	if u.Scheme == "https" {
		tlsCfg, err := buildTLS(c.cfg.TLS)
		if err != nil {
			return nil, err
		}
		creds = credentials.NewTLS(tlsCfg)
	}
	conn, err := grpc.NewClient(u.Host, grpc.WithTransportCredentials(creds), grpc.WithUserAgent(UserAgent))
	if err != nil {
		return nil, ConfigError("otlpEndpoint cannot be dialed")
	}
	md := metadata.MD{}
	var secrets []string
	for k, v := range c.cfg.OTLPHeaders {
		md.Set(strings.ToLower(k), v)
		secrets = append(secrets, v)
	}
	if !c.cfg.Authentication.IsZero() {
		md.Set("authentication", c.cfg.Authentication.Reveal())
		secrets = append(secrets, c.cfg.Authentication.Reveal())
	}
	return &OTLPLogExporter{conn: conn, client: collogs.NewLogsServiceClient(conn), md: md, secrets: secrets, timeout: c.cfg.RequestTimeout}, nil
}

// Export sends the batch in one ExportLogsServiceRequest (one ResourceLogs per distinct
// resource). Rejected records reported by the server are counted in Rejected.
func (e *OTLPLogExporter) Export(ctx context.Context, records []LogRecord, opts ExportOptions) (ExportResult, error) {
	if len(records) == 0 {
		return ExportResult{}, nil
	}
	ctx = metadata.NewOutgoingContext(ctx, e.md)
	if e.timeout > 0 {
		var cancel context.CancelFunc
		ctx, cancel = context.WithTimeout(ctx, e.timeout)
		defer cancel()
	}
	var callOpts []grpc.CallOption
	if opts.Gzip {
		callOpts = append(callOpts, grpc.UseCompressor(gzip.Name))
	}
	res, err := e.client.Export(ctx, OTLPLogsRequest(records), callOpts...)
	if err != nil {
		return ExportResult{}, grpcError("logs.exportOTLP", err, e.secrets)
	}
	rejected := 0
	if ps := res.GetPartialSuccess(); ps != nil {
		rejected = int(ps.GetRejectedLogRecords())
	}
	return ExportResult{Accepted: max(len(records)-rejected, 0), Rejected: rejected}, nil
}

// Shutdown closes the gRPC connection. It is idempotent.
func (e *OTLPLogExporter) Shutdown(context.Context) error {
	e.shutOnce.Do(func() { _ = e.conn.Close() })
	return nil
}

// grpcError maps a gRPC status to an *Error.
func grpcError(op string, err error, secrets []string) *Error {
	st, _ := grpcstatus.FromError(err)
	var code ErrorCode
	switch st.Code() {
	case codes.Unauthenticated:
		code = ErrorCodeAuthentication
	case codes.PermissionDenied:
		code = ErrorCodePermissionDenied
	case codes.InvalidArgument, codes.FailedPrecondition, codes.OutOfRange:
		code = ErrorCodeValidation
	case codes.NotFound:
		code = ErrorCodeNotFound
	case codes.AlreadyExists, codes.Aborted:
		code = ErrorCodeConflict
	case codes.ResourceExhausted:
		code = ErrorCodeRateLimited
	case codes.DeadlineExceeded:
		code = ErrorCodeTimeout
	case codes.Unimplemented:
		code = ErrorCodeUnsupported
	case codes.Canceled:
		code = ErrorCodeCancelled
	default:
		code = ErrorCodeUnavailable
	}
	msg := fmt.Sprintf("%s: %s (gRPC %s)", op, truncate(RedactText(st.Message(), secrets), maxVendorText), st.Code())
	return &Error{Code: code, Message: msg, Retryable: code.Retryable()}
}

// OTLPLogsRequest builds the OTLP request for records (exported for tests and custom
// transports). TraceID/SpanID are set as OTLP ids when they are valid hex and are
// always copied to the attributes trace_id/span_id.
func OTLPLogsRequest(records []LogRecord) *collogs.ExportLogsServiceRequest {
	type group struct {
		resource map[string]AttrValue
		logs     []*logspb.LogRecord
	}
	var order []string
	groups := map[string]*group{}
	for _, r := range records {
		key := resourceKey(r.Resource)
		g, ok := groups[key]
		if !ok {
			g = &group{resource: r.Resource}
			groups[key] = g
			order = append(order, key)
		}
		g.logs = append(g.logs, otlpLogRecord(r))
	}
	req := &collogs.ExportLogsServiceRequest{}
	for _, key := range order {
		g := groups[key]
		req.ResourceLogs = append(req.ResourceLogs, &logspb.ResourceLogs{
			Resource:  &resourcepb.Resource{Attributes: otlpKeyValues(g.resource)},
			ScopeLogs: []*logspb.ScopeLogs{{Scope: &commonpb.InstrumentationScope{Name: InstrumentationName, Version: Version}, LogRecords: g.logs}},
		})
	}
	return req
}

func resourceKey(m map[string]AttrValue) string {
	b, _ := json.Marshal(m)
	return string(b)
}

func otlpLogRecord(r LogRecord) *logspb.LogRecord {
	attrs := cloneAttrs(r.Attributes)
	if attrs == nil {
		attrs = map[string]AttrValue{}
	}
	out := &logspb.LogRecord{
		SeverityNumber: logspb.SeverityNumber(min(max(int32(r.Severity), 0), 24)), //nolint:gosec // clamped to the OTLP range
		SeverityText:   r.SeverityText,
		Body:           &commonpb.AnyValue{Value: &commonpb.AnyValue_StringValue{StringValue: r.Body}},
	}
	if out.SeverityText == "" && r.Severity != SeverityUnspecified {
		out.SeverityText = r.Severity.String()
	}
	if !r.Timestamp.IsZero() {
		out.TimeUnixNano = uint64(r.Timestamp.UnixNano())
	}
	if !r.ObservedTimestamp.IsZero() {
		out.ObservedTimeUnixNano = uint64(r.ObservedTimestamp.UnixNano())
	}
	if r.TraceID != "" {
		attrs["trace_id"] = r.TraceID
		if b, err := hex.DecodeString(r.TraceID); err == nil && len(b) == 16 {
			out.TraceId = b
		}
	}
	if r.SpanID != "" {
		attrs["span_id"] = r.SpanID
		if b, err := hex.DecodeString(r.SpanID); err == nil && len(b) == 8 {
			out.SpanId = b
		}
	}
	if r.TraceFlags != nil {
		out.Flags = uint32(*r.TraceFlags)
	}
	out.Attributes = otlpKeyValues(attrs)
	return out
}

func otlpKeyValues(m map[string]AttrValue) []*commonpb.KeyValue {
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	out := make([]*commonpb.KeyValue, 0, len(keys))
	for _, k := range keys {
		out = append(out, &commonpb.KeyValue{Key: k, Value: otlpAnyValue(NormalizeAttrValue(m[k]))})
	}
	return out
}

func otlpAnyValue(v AttrValue) *commonpb.AnyValue {
	switch x := v.(type) {
	case string:
		return &commonpb.AnyValue{Value: &commonpb.AnyValue_StringValue{StringValue: x}}
	case bool:
		return &commonpb.AnyValue{Value: &commonpb.AnyValue_BoolValue{BoolValue: x}}
	case int64:
		return &commonpb.AnyValue{Value: &commonpb.AnyValue_IntValue{IntValue: x}}
	case float64:
		return &commonpb.AnyValue{Value: &commonpb.AnyValue_DoubleValue{DoubleValue: x}}
	case []any:
		vals := make([]*commonpb.AnyValue, len(x))
		for i, e := range x {
			vals[i] = otlpAnyValue(e)
		}
		return &commonpb.AnyValue{Value: &commonpb.AnyValue_ArrayValue{ArrayValue: &commonpb.ArrayValue{Values: vals}}}
	case map[string]any:
		return &commonpb.AnyValue{Value: &commonpb.AnyValue_KvlistValue{KvlistValue: &commonpb.KeyValueList{Values: otlpKeyValues(x)}}}
	case nil:
		return &commonpb.AnyValue{}
	}
	return &commonpb.AnyValue{Value: &commonpb.AnyValue_StringValue{StringValue: fmt.Sprint(v)}}
}
