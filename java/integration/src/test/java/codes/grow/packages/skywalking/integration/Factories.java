package codes.grow.packages.skywalking.integration;

import codes.grow.packages.skywalking.SkyWalkingClient;
import codes.grow.packages.skywalking.SkyWalkingLogClientOptions;
import codes.grow.packages.skywalking.core.EnvReader;
import codes.grow.packages.skywalking.core.Poll;
import codes.grow.packages.skywalking.logging.BatchOptions;
import codes.grow.packages.skywalking.testing.ConformanceRunner;
import codes.grow.packages.skywalking.testing.logging.LoggingTarget;
import codes.grow.packages.skywalking.testing.tracing.TracingTarget;
import codes.grow.packages.skywalking.tracing.TracingConfig;
import codes.grow.packages.skywalking.tracing.TracingHandle;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Conformance targets of this service: {@code core} (the client), {@code tracing} (OTLP/gRPC to OAP,
 * read back through the Zipkin query API) and {@code logging} (native {@code /v3/logs}, read back
 * through GraphQL with client-side text filtering).
 */
final class Factories {
  /** Capabilities the deployment under test offers (see the scenario {@code requires}). */
  static final String[] CAPABILITIES = {"version", "query", "services"};

  /** OAP lists a new service only after its periodic metadata flush (~30-35 s). */
  static final Duration METADATA_DELAY = Duration.ofSeconds(120);

  private static final String SERVICE =
      "grow-conformance-java-" + UUID.randomUUID().toString().substring(0, 8);

  private static volatile boolean warmedUp;

  private Factories() {}

  static void register(ConformanceRunner.Builder runner, EnvReader env) {
    runner.serviceName(SERVICE);
    runner.target("core", scenario -> SkyWalkingClient.builder().fromEnv(env).build());
    runner.target(
        "tracing",
        scenario -> {
          SkyWalkingClient client = SkyWalkingClient.builder().fromEnv(env).build();
          warmUp(client);
          TracingHandle handle =
              client.initTracing(
                  TracingConfig.builder(SERVICE)
                      .setGlobal(false)
                      .batch(
                          new TracingConfig.BatchSettings(
                              2048, 512, Duration.ofMillis(200), Duration.ofSeconds(30)))
                      .build());
          return TracingTarget.of(handle, client.traces());
        });
    runner.target(
        "logging",
        scenario -> {
          SkyWalkingClient client = SkyWalkingClient.builder().fromEnv(env).build();
          BatchOptions batch =
              LoggingTarget.batchOptions(
                  scenario, BatchOptions.builder().flushInterval(Duration.ofMillis(200)).build());
          return LoggingTarget.of(
              client.logClient(
                  SkyWalkingLogClientOptions.builder()
                      .serviceName("grow-conformance-java-logs")
                      .batchOptions(batch)
                      .build()),
              client.logs());
        });
  }

  /**
   * Sends one span for the conformance service and waits until OAP lists it, so the {@code
   * tracing.services} scenario (one {@code listServices} call) does not race the metadata flush.
   */
  private static synchronized void warmUp(SkyWalkingClient client) {
    if (warmedUp) {
      return;
    }
    warmedUp = true;
    try (TracingHandle h =
        client.initTracing(TracingConfig.builder(SERVICE).setGlobal(false).build())) {
      h.tracer("warm-up").spanBuilder("warm-up").startSpan().end();
      h.forceFlush(Duration.ofSeconds(30));
    }
    Poll.until(
        "warm-up listServices",
        METADATA_DELAY,
        Duration.ofSeconds(2),
        () ->
            client.traces().listServices().contains(SERVICE)
                ? Optional.of(true)
                : Optional.<Boolean>empty());
  }
}
