// Apache SkyWalking TypeScript SDK tour: configuration from the environment, health/version,
// OpenTelemetry tracing over OTLP/gRPC, native logs correlated with the trace, and reading
// everything back (Zipkin query API for OTLP spans, GraphQL for logs, services and topology).
//
//   (cd ../../typescript && npm install && npm run build)   # or use the published package
//   npm install
//   GROW_APACHE_SKYWALKING_URL=http://127.0.0.1:12800 npm start
//
// OAP needs SW_RECEIVER_ZIPKIN=default and SW_QUERY_ZIPKIN=default (port 9412) for OTLP spans to
// be readable; the OTLP receiver listens on gRPC port 11800.

import {
  SkyWalkingClient,
  SkyWalkingError,
  withSpan,
} from "@grow-codes/apache-skywalking";

const client = SkyWalkingClient.fromEnv({
  // Warnings only; the default logger is silent.
  logger: { debug() {}, info() {}, warn: console.warn, error: console.error },
});
console.log(`config: ${client.config}`); // the OAP token prints as [REDACTED]

try {
  const health = await client.health(); // never throws for an unreachable service
  console.log("health:", health);
  if (!health.healthy) process.exit(1);
  console.log("server version:", await client.serverVersion());

  // 1. Traces: OTLP/gRPC to the OAP receiver (11800).
  const tracing = client.initTracing({
    serviceName: "example-checkout",
    environment: "dev",
  });
  const logs = client.logClient({ serviceName: "example-checkout" });
  const log = logs.logger("checkout");
  const traceId = await withSpan("GET /checkout", async (span) => {
    span.setAttribute("http.method", "GET");
    await withSpan(
      "select orders",
      () => new Promise((resolve) => setTimeout(resolve, 20)),
    );
    // 2. Logs: native /v3/logs, correlated with the active span (trace id + span id).
    log.info("checkout finished", { items: 3 });
    return span.spanContext().traceId;
  });
  await tracing.forceFlush();
  await logs.flush();

  // 3. Read back. OAP stores span names lower-cased; services are listed ~30 s after first data.
  const trace = await client.traces.waitForTrace(traceId, {
    timeoutMs: 60_000,
    minSpans: 2,
  });
  for (const span of trace.spans)
    console.log(`span ${span.name} ${span.durationMicros} µs`);
  const records = await client.logs.search(`trace_id=${traceId}`);
  console.log(
    "correlated logs:",
    records.records.map((r) => `${r.severityText} ${r.body}`),
  );
  const warnings = await client.query.queryLogs({
    service: "example-checkout",
    tags: { level: "WARN" },
  });
  console.log(`warnings in the last hour: ${warnings.logs.length}`);
  console.log(
    "services:",
    (await client.query.listServices()).map((s) => s.name),
  );
  const topology = await client.query.topology();
  console.log(
    `topology: ${topology.nodes.length} nodes, ${topology.calls.length} calls`,
  );
} catch (err) {
  if (err instanceof SkyWalkingError) {
    // Canonical code + HTTP status; the message never contains credentials.
    console.error(`${err.code}: ${err.message}`, {
      httpStatus: err.httpStatus,
    });
    process.exitCode = 1;
  } else {
    throw err;
  }
} finally {
  await client.close(); // flushes the log client and shuts tracing down
}
