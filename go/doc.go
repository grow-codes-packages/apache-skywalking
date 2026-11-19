// Package skywalking is the Grow Codes SDK for Apache SkyWalking (OAP 10.4).
//
// Create a client from the environment (GROW_APACHE_SKYWALKING_URL,
// SW_GRAPHQL_URL, SW_OAP_ADDRESS, ...) or from a Config literal:
//
//	client, err := skywalking.NewClientFromEnv()
//	if err != nil {
//		return err
//	}
//	defer client.Close()
//	status, err := client.Health(ctx)
//
// The client covers:
//
//   - Tracing: InitTracing exports OpenTelemetry spans to OAP over OTLP gRPC (port
//     11800); SW8Propagator handles SkyWalking's sw8 / sw8-correlation headers next to
//     W3C trace context.
//   - Trace queries: Client.Traces implements TraceQueryClient over both OAP trace
//     stores (OTLP spans through the Zipkin query API, native agent traces through
//     GraphQL); see TraceBackend.
//   - Logs: Client.NewLogClient sends logs to the native /v3/logs API
//     (NativeLogExporter) or, with NewOTLPLogExporter, over OTLP gRPC; Client.Logs
//     implements LogQueryClient.
//   - Native queries: Client.Query is the GraphQL API (services, endpoints, instances,
//     native traces, logs, topology, raw GraphQL).
//
// Every error is an *Error; compare codes with errors.Is against the Err*
// sentinels (errors.Is(err, skywalking.ErrNotFound)). Credentials are wrapped in
// Secret and never appear in errors, hooks or logs.
//
// Apache SkyWalking is a trademark of The Apache Software Foundation. This package is not affiliated
// with or endorsed by The Apache Software Foundation.
package skywalking
