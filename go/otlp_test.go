package skywalking

import (
	"context"
	"net"
	"sync"
	"testing"

	coltrace "go.opentelemetry.io/proto/otlp/collector/trace/v1"
	"google.golang.org/grpc"
	"google.golang.org/grpc/metadata"
)

// fakeOTLP is an in-process OAP OTLP/gRPC trace receiver.
type fakeOTLP struct {
	mu     sync.Mutex
	traces []*coltrace.ExportTraceServiceRequest
	md     []metadata.MD
	addr   string
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
