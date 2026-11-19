package codes.grow.packages.skywalking;

import codes.grow.packages.skywalking.core.Json;
import codes.grow.packages.skywalking.core.MockServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;

/** Recorded OAP exchanges (from the real 10.4.0 container) served by the mock server. */
final class Fixtures {
  private Fixtures() {}

  static JsonNode load(String name) {
    try (InputStream in = Fixtures.class.getResourceAsStream("/fixtures/" + name + ".json")) {
      if (in == null) {
        throw new IllegalArgumentException("no fixture " + name);
      }
      return Json.tree(in.readAllBytes());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The recorded response body as text. */
  static String body(String name) {
    JsonNode b = load(name).path("response").path("body");
    return b.isTextual() ? b.asText() : b.toString();
  }

  /** Queues the recorded response of a fixture. */
  static void enqueue(MockServer server, String name) {
    enqueue(server, name, Map.of());
  }

  /**
   * Queues the recorded response, renaming top-level {@code data} fields (fixtures recorded with
   * the {@code …ByName} variants of a query).
   */
  static void enqueue(MockServer server, String name, Map<String, String> rename) {
    JsonNode res = load(name).path("response");
    JsonNode b = res.path("body");
    if (!rename.isEmpty() && b.path("data").isObject()) {
      ObjectNode data = (ObjectNode) b.get("data");
      rename.forEach((from, to) -> data.set(to, data.remove(from)));
    }
    String text = b.isTextual() ? b.asText() : b.toString();
    String type = res.path("headers").path("content-type").asText("application/json");
    server.enqueue(res.path("status").asInt(200), text, "Content-Type", type);
  }

  /**
   * Queues {@code count} responders that answer by path: requests below {@code /zipkin} get the
   * {@code zipkin} fixture, everything else the {@code graphql} fixture (for calls that query both
   * stores in parallel).
   */
  static void enqueueByPath(MockServer server, String zipkin, String graphql, int count) {
    for (int i = 0; i < count; i++) {
      server.enqueue(
          ex -> {
            String name = ex.getRequestURI().getPath().startsWith("/zipkin") ? zipkin : graphql;
            JsonNode res = load(name).path("response");
            JsonNode b = res.path("body");
            MockServer.send(
                ex, res.path("status").asInt(200), b.isTextual() ? b.asText() : b.toString());
          });
    }
  }

  static JsonNode request(MockServer.Recorded r) {
    return Json.tree(r.body().getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }
}
