// Created by sdk-kit 0.1.0; owned by this repository.
package codes.grow.packages.skywalking.examples;

import codes.grow.packages.skywalking.QueryLogsOptions;
import codes.grow.packages.skywalking.SkyWalkingClient;
import codes.grow.packages.skywalking.SkyWalkingService;
import codes.grow.packages.skywalking.Topology;
import codes.grow.packages.skywalking.core.HealthStatus;
import codes.grow.packages.skywalking.core.SkyWalkingException;
import codes.grow.packages.skywalking.logging.BatchingLogClient;
import codes.grow.packages.skywalking.logging.LogRecord;
import codes.grow.packages.skywalking.tracing.Spans;
import codes.grow.packages.skywalking.tracing.TracingConfig;
import codes.grow.packages.skywalking.tracing.TracingHandle;
import codes.grow.packages.skywalking.tracing.query.Span;
import codes.grow.packages.skywalking.tracing.query.Trace;
import codes.grow.packages.skywalking.tracing.query.WaitForTraceOptions;
import java.time.Duration;
import java.util.Map;

/**
 * Apache SkyWalking Java SDK tour: configuration from the environment, health and version,
 * OpenTelemetry tracing over OTLP/gRPC, native logs correlated with the trace, and reading
 * everything back (Zipkin query API for OTLP spans, GraphQL for logs, services and topology).
 *
 * <p>OAP needs {@code SW_RECEIVER_ZIPKIN=default} and {@code SW_QUERY_ZIPKIN=default} (port 9412)
 * for OTLP spans to be readable; the OTLP receiver listens on gRPC port 11800.
 */
public final class BasicExample {
  private BasicExample() {}

  /**
   * Runs the example.
   *
   * @param args unused
   * @throws Exception on interruption
   */
  public static void main(String[] args) throws Exception {
    // Reads GROW_APACHE_SKYWALKING_URL (or SW_GRAPHQL_URL / SW_OAP_ADDRESS), OTLP and Zipkin
    // endpoints (derived from the URL by default), SW_AUTHENTICATION, timeouts, TLS and proxy.
    try (var client = SkyWalkingClient.fromEnv()) {
      System.out.println("config: " + client.config()); // the OAP token prints as [REDACTED]
      HealthStatus health = client.health(); // never throws for an unreachable service
      System.out.println("health: " + health);
      if (!health.healthy()) {
        return;
      }
      System.out.println("server version: " + client.serverVersion());

      // 1. Traces: OTLP/gRPC to the OAP receiver (11800). close() of the client flushes them.
      TracingHandle tracing =
          client.initTracing(TracingConfig.builder("example-checkout").environment("dev").build());
      BatchingLogClient logs = client.logClient("example-checkout");
      String traceId =
          Spans.withSpan(
              "GET /checkout",
              () -> {
                Spans.currentSpan().setAttribute("http.method", "GET");
                Spans.withSpan("select orders", () -> Thread.sleep(20));
                // 2. Logs: native /v3/logs, correlated with the active span (trace + span id).
                logs.logger("checkout").info("checkout finished", Map.of("items", 3));
                return Spans.currentTraceId().orElseThrow();
              });
      tracing.forceFlush();
      logs.flush();

      // 3. Read back. OAP stores span names lower-cased; services appear ~30 s after first data.
      Trace trace =
          client
              .traces()
              .waitForTrace(
                  traceId, new WaitForTraceOptions(Duration.ofSeconds(60), Duration.ofSeconds(1), 2));
      for (Span span : trace.spans()) {
        System.out.println("span " + span.name() + " " + span.durationMicros() + " µs");
      }
      for (LogRecord r : client.logs().search("trace_id=" + traceId).records()) {
        System.out.println("correlated log: " + r.severityText() + " " + r.body());
      }
      int warnings =
          client
              .query()
              .queryLogs(
                  QueryLogsOptions.builder().service("example-checkout").tag("level", "WARN").build())
              .logs()
              .size();
      System.out.println("warnings in the last hour: " + warnings);
      System.out.println(
          "services: "
              + client.query().listServices().stream().map(SkyWalkingService::name).toList());
      Topology topology = client.query().topology();
      System.out.println(
          "topology: " + topology.nodes().size() + " nodes, " + topology.calls().size() + " calls");
    } catch (SkyWalkingException e) {
      // Canonical code + HTTP status; the message never contains credentials.
      System.err.println(e.code() + ": " + e.getMessage() + " " + e.httpStatus());
    }
  }
}
