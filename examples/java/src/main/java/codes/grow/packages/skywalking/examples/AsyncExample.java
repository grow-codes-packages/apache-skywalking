// Created by sdk-kit 0.1.0; owned by this repository.
package codes.grow.packages.skywalking.examples;

import codes.grow.packages.skywalking.SkyWalkingClient;
import codes.grow.packages.skywalking.SkyWalkingService;
import codes.grow.packages.skywalking.TimeRange;
import codes.grow.packages.skywalking.TopologyOptions;
import codes.grow.packages.skywalking.core.HealthStatus;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** The {@code …Async} methods: run calls concurrently and cancel a slow one. */
public final class AsyncExample {
  private AsyncExample() {}

  /**
   * Runs the example.
   *
   * @param args unused
   */
  public static void main(String[] args) {
    try (var client = SkyWalkingClient.fromEnv()) {
      CompletableFuture<HealthStatus> health = client.healthAsync();
      CompletableFuture<Duration> ping = client.pingAsync();
      CompletableFuture<List<SkyWalkingService>> services =
          client.query().listServicesAsync(null, null);
      CompletableFuture<List<String>> traced = client.traces().listServicesAsync();
      CompletableFuture.allOf(
              health,
              ping.exceptionally(e -> null),
              services.exceptionally(e -> null),
              traced.exceptionally(e -> null))
          .join();
      System.out.println("health: " + health.join());
      ping.whenComplete(
          (latency, error) ->
              System.out.println(error == null ? "ping: " + latency : "ping failed: " + error));
      System.out.println(
          "native services: "
              + services.handle((s, e) -> e == null ? s.size() + "" : "failed: " + e).join());
      System.out.println(
          "services with traces: "
              + traced.handle((s, e) -> e == null ? s.toString() : "failed: " + e).join());

      // Cancelling a future aborts the HTTP exchange and any pending retry.
      var topology =
          client.query().topologyAsync(TimeRange.last(Duration.ofHours(1)), TopologyOptions.GLOBAL);
      topology.cancel(true);
      System.out.println("topology cancelled: " + topology.isCancelled());
    }
  }
}
