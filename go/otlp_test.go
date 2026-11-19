package skywalking

import (
	"context"
	"errors"
	"net"
	"strings"
	"sync"
	"testing"
	"time"

	collogs "go.opentelemetry.io/proto/otlp/collector/logs/v1"
	coltrace "go.opentelemetry.io/proto/otlp/collector/trace/v1"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/metadata"
	grpcstatus "google.golang.org/grpc/status"
)

// fakeOTLP is an in-process OAP gRPC receiver (logs + traces).
type fakeOTLP struct {
	collogs.UnimplementedLogsServiceServer
	mu       sync.Mutex
	logs     []*collogs.ExportLogsServiceRequest
	traces   []*coltrace.ExportTraceServiceRequest
	md       []metadata.MD
	logErr   error
	rejected int64
	addr     string
}

func (f *fakeOTLP) Export(ctx context.Context, req *collogs.ExportLogsServiceRequest) (*collogs.ExportLogsServiceResponse, error) {
	md, _ := metadata.FromIncomingContext(ctx)
	f.mu.Lock()
	defer f.mu.Unlock()
	f.md = append(f.md, md)
	if f.logErr != nil {
		return nil, f.logErr
	}
	f.logs = append(f.logs, req)
	res := &collogs.ExportLogsServiceResponse{}
	if f.rejected > 0 {
		res.PartialSuccess = &collogs.ExportLogsPartialSuccess{RejectedLogRecords: f.rejected}
	}
	return res, nil
}

type traceSrv struct {
	coltrace.UnimplementedTraceServiceServer
	f *fakeOTLP
}

func (s traceSrv) Export(ctx context.Context, req *coltrace.ExportTraceServiceRequest) (*coltrace.ExportTraceServiceResponse, error) {
	md, _ := metadata.FromIncomingContext(ctx)
	s.f.mu.Lock()
	defer s.f.mu.Unlock()
	s.f.md = append(s.f.md, md)
	s.f.traces = append(s.f.traces, req)
	return &coltrace.ExportTraceServiceResponse{}, nil
}

func newFakeOTLP(t *testing.T) *fakeOTLP {
	t.Helper()
	lis, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	f := &fakeOTLP{addr: "http://" + lis.Addr().String()}
	s := grpc.NewServer()
	collogs.RegisterLogsServiceServer(s, f)
	coltrace.RegisterTraceServiceServer(s, traceSrv{f: f})
	go func() { _ = s.Serve(lis) }()
	t.Cleanup(s.Stop)
	return f
}

func (f *fakeOTLP) lastMD() metadata.MD {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.md[len(f.md)-1]
}

func TestOTLPLogExporter(t *testing.T) {
	f := newFakeOTLP(t)
	m := newMockOAP(t)
	c := m.client(func(cfg *Config) {
		cfg.OTLPEndpoint = f.addr
		cfg.Authentication = NewSecret("sw-grpc-token")
		cfg.OTLPHeaders = map[string]string{"X-Tenant": "t1"}
	})
	exp, err := c.NewOTLPLogExporter()
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = exp.Shutdown(t.Context()) }()
	flags := uint8(1)
	recs := []LogRecord{
		{Timestamp: time.Unix(10, 0), ObservedTimestamp: time.Unix(11, 0), Severity: SeverityWarn, Body: "a", TraceID: otlpTrace, SpanID: "00f067aa0ba902b7", TraceFlags: &flags,
			Attributes: map[string]AttrValue{"n": 1, "f": 1.5, "b": true, "l": []any{"x", int64(2)}, "m": map[string]any{"k": "v"}, "nil": nil},
			Resource:   map[string]AttrValue{"service.name": "svc-a"}},
		{Body: "b", SeverityText: "NOTICE", TraceID: "not-hex", Resource: map[string]AttrValue{"service.name": "svc-b"}},
		{Body: "c", Resource: map[string]AttrValue{"service.name": "svc-a"}},
	}
	res, err := exp.Export(t.Context(), recs, ExportOptions{Gzip: true})
	if err != nil || res.Accepted != 3 {
		t.Fatal(res, err)
	}
	md := f.lastMD()
	if md.Get("authentication")[0] != "sw-grpc-token" || md.Get("x-tenant")[0] != "t1" {
		t.Fatal(md)
	}
	req := f.logs[0]
	if len(req.ResourceLogs) != 2 || len(req.ResourceLogs[0].ScopeLogs[0].LogRecords) != 2 {
		t.Fatal(req)
	}
	lr := req.ResourceLogs[0].ScopeLogs[0].LogRecords[0]
	if lr.SeverityText != "WARN" || len(lr.TraceId) != 16 || len(lr.SpanId) != 8 || lr.Flags != 1 || lr.TimeUnixNano != 10e9 || lr.ObservedTimeUnixNano != 11e9 {
		t.Fatal(lr)
	}
	attrs := map[string]string{}
	for _, kv := range lr.Attributes {
		attrs[kv.Key] = kv.Value.String()
	}
	if !strings.Contains(attrs["trace_id"], otlpTrace) || !strings.Contains(attrs["span_id"], "00f067aa0ba902b7") || attrs["l"] == "" || attrs["m"] == "" {
		t.Fatal(attrs)
	}
	lr2 := req.ResourceLogs[1].ScopeLogs[0].LogRecords[0]
	if lr2.TraceId != nil || lr2.SeverityText != "NOTICE" {
		t.Fatal(lr2)
	}
	if res, err := exp.Export(t.Context(), nil, ExportOptions{}); err != nil || res.Accepted != 0 {
		t.Fatal(res, err)
	}

	f.rejected = 1
	res, err = exp.Export(t.Context(), recs[:1], ExportOptions{})
	if err != nil || res.Accepted != 0 || res.Rejected != 1 {
		t.Fatal(res, err)
	}
	f.rejected = 0
	f.logErr = grpcstatus.Error(codes.PermissionDenied, "bad token sw-grpc-token")
	_, err = exp.Export(t.Context(), recs[:1], ExportOptions{})
	mustCode(t, err, ErrorCodePermissionDenied)
	if strings.Contains(err.Error(), "sw-grpc-token") {
		t.Fatal(err)
	}
	first, second := exp.Shutdown(t.Context()), exp.Shutdown(t.Context())
	if first != nil || second != nil {
		t.Fatal("shutdown")
	}
}

func TestOTLPLogExporterConfig(t *testing.T) {
	m := newMockOAP(t)
	_, err := m.client(func(cfg *Config) { cfg.OTLPProtocol = ProtocolHTTPProtobuf }).NewOTLPLogExporter()
	mustCode(t, err, ErrorCodeConfig)
	exp, err := m.client(func(cfg *Config) { cfg.OTLPEndpoint = "https://127.0.0.1:1"; cfg.TLS.InsecureSkipVerify = true }).NewOTLPLogExporter()
	if err != nil {
		t.Fatal(err)
	}
	_ = exp.Shutdown(t.Context())
	_, err = (&Client{cfg: Config{OTLPProtocol: ProtocolGRPC, OTLPEndpoint: "https://127.0.0.1:1", CommonConfig: CommonConfig{TLS: TLSConfig{CACertPEM: "bad"}}}}).NewOTLPLogExporter()
	mustCode(t, err, ErrorCodeConfig)
	bad := &Client{cfg: Config{OTLPProtocol: ProtocolGRPC, OTLPEndpoint: "http://"}}
	_, err = bad.NewOTLPLogExporter()
	mustCode(t, err, ErrorCodeConfig)
}

func TestGRPCErrorMapping(t *testing.T) {
	cases := map[codes.Code]ErrorCode{
		codes.Unauthenticated: ErrorCodeAuthentication, codes.PermissionDenied: ErrorCodePermissionDenied,
		codes.InvalidArgument: ErrorCodeValidation, codes.NotFound: ErrorCodeNotFound, codes.AlreadyExists: ErrorCodeConflict,
		codes.ResourceExhausted: ErrorCodeRateLimited, codes.DeadlineExceeded: ErrorCodeTimeout, codes.Unimplemented: ErrorCodeUnsupported,
		codes.Canceled: ErrorCodeCancelled, codes.Unknown: ErrorCodeUnavailable,
	}
	for gc, want := range cases {
		e := grpcError("op", grpcstatus.Error(gc, "m"), nil)
		if e.Code != want || e.Retryable != want.Retryable() {
			t.Errorf("%s: %v", gc, e)
		}
	}
	if grpcError("op", errors.New("plain"), nil).Code != ErrorCodeUnavailable {
		t.Fatal("plain error")
	}
}
