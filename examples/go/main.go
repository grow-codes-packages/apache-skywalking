// Command example shows the Apache SkyWalking Go SDK against a real OAP: health and
// version, OpenTelemetry tracing over OTLP gRPC, reading the trace back, native logs,
// a log search and the GraphQL query API.
//
//	GROW_APACHE_SKYWALKING_URL=http://127.0.0.1:12800 go run .
//
// OTLP spans are read back through OAP's Zipkin query API (SW_QUERY_ZIPKIN=default,
// port 9412); set GROW_APACHE_SKYWALKING_ZIPKIN_URL when it is not on the OAP host.
package main

import (
	"context"
	"errors"
	"fmt"
	"os"
	"time"

	skywalking "github.com/grow-codes-packages/apache-skywalking/go"
)

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}
}

func run() error {
	client, err := skywalking.NewClientFromEnv()
	if err != nil {
		return err // CONFIG error naming the variable, never its value
	}
	defer func() { _ = client.Close() }()
	fmt.Println("config:", client.Config()) // credentials print as [REDACTED]

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()

	health, err := client.Health(ctx)
	if err != nil {
		return err
	}
	version, err := client.ServerVersion(ctx)
	if err != nil {
		return err
	}
	fmt.Printf("health: %s, OAP %s\n", health.Status, version)

	// Tracing: OTLP gRPC to OAP (port 11800), W3C + sw8 propagation.
	tracing, err := client.InitTracing(ctx, skywalking.TracingConfig{ServiceName: "example-go", DisableSetGlobal: true})
	if err != nil {
		return err
	}
	spanCtx, span := tracing.Tracer("example").Start(ctx, "checkout")
	traceID, _ := skywalking.CurrentTraceID(spanCtx)
	span.End()
	if err := tracing.Shutdown(ctx); err != nil {
		return err
	}

	// Logs: native /v3/logs exporter, correlated with the trace.
	logs, err := client.NewLogClient(skywalking.LogClientOptions{Resource: map[string]skywalking.AttrValue{"service.name": "example-go"}})
	if err != nil {
		return err
	}
	logs.Send(ctx, skywalking.LogInput{Message: "order accepted", Severity: skywalking.SeverityInfo, TraceID: traceID})
	if err := logs.Close(ctx); err != nil {
		return err
	}

	// Read the trace back (OTLP spans live in OAP's Zipkin store).
	trace, err := client.Traces().WaitForTrace(ctx, traceID, skywalking.WaitForTraceOptions{Timeout: time.Minute})
	switch {
	case errors.Is(err, skywalking.ErrTimeout):
		fmt.Println("trace not readable yet (is SW_QUERY_ZIPKIN enabled?)")
	case err != nil:
		return err
	default:
		fmt.Printf("trace %s: %d span(s), root %q\n", trace.TraceID, len(trace.Spans), trace.Spans[0].Name)
	}

	rec, err := client.Logs().WaitForLog(ctx, "service=example-go order accepted", skywalking.WaitForLogOptions{Timeout: time.Minute})
	if err != nil {
		return err
	}
	fmt.Printf("log: %s trace=%s\n", rec.Body, rec.TraceID)

	// Native GraphQL query API.
	services, err := client.Query().ListServices(ctx, "")
	if err != nil {
		return err
	}
	fmt.Printf("%d service(s) with native metadata\n", len(services))
	topo, err := client.Query().Topology(ctx, skywalking.TimeRange{}, skywalking.TopologyOptions{})
	if err != nil {
		return err
	}
	fmt.Printf("topology: %d node(s), %d call(s)\n", len(topo.Nodes), len(topo.Calls))
	return nil
}
