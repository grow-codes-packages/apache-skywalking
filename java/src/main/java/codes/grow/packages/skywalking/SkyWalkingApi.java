package codes.grow.packages.skywalking;

import codes.grow.packages.skywalking.core.HttpTransport;
import codes.grow.packages.skywalking.core.Redaction;
import codes.grow.packages.skywalking.core.TransportRequest;
import codes.grow.packages.skywalking.core.TransportResponse;
import codes.grow.packages.skywalking.core.ValidationException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Shared OAP access of the client's modules: GraphQL, the Zipkin query API and time windows. */
final class SkyWalkingApi {
  private static final Pattern MUTATION = Pattern.compile("(?is)^\\s*mutation\\b.*");

  final HttpTransport transport;
  final SkyWalkingConfig config;
  final Clock clock;
  private final Object lock = new Object();
  private CompletableFuture<Integer> offset;
  private CompletableFuture<SkyWalkingCapabilities> capabilities;

  SkyWalkingApi(HttpTransport transport, SkyWalkingConfig config, Clock clock) {
    this.transport = transport;
    this.config = config;
    this.clock = clock;
  }

  Instant now() {
    return clock.instant();
  }

  /** Runs {@code body}; a synchronous exception becomes a failed future. */
  static <T> CompletableFuture<T> guard(Supplier<CompletableFuture<T>> body) {
    try {
      return body.get();
    } catch (RuntimeException e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  static String required(String value, String what, String operation) {
    if (value == null || value.isBlank()) {
      throw new ValidationException(operation + ": " + what + " is required");
    }
    return value;
  }

  String redact(String text) {
    return Redaction.redactText(text, config.knownSecrets());
  }

  /**
   * Raw GraphQL call: {@code POST <url><graphqlPath>}. HTTP errors fail the future (400 →
   * VALIDATION); resolver errors come back in {@code errors} with HTTP 200.
   */
  CompletableFuture<GraphQLResponse> graphql(
      String query, Map<String, ?> variables, String operation) {
    return guard(
        () -> {
          required(query, "query", operation);
          Map<String, Object> body = new LinkedHashMap<>();
          body.put("query", query);
          body.put("variables", variables == null ? Map.of() : variables);
          TransportRequest req =
              TransportRequest.post(config.graphqlPath())
                  .operation(operation)
                  .json(body)
                  .idempotent(!MUTATION.matcher(query).matches())
                  .build();
          return transport
              .sendAsync(req)
              .thenApply(
                  r -> {
                    GraphQLResponse res = GraphQLResponse.fromBody(r.jsonNode());
                    return new GraphQLResponse(
                        res.data(),
                        res.errors().stream()
                            .map(
                                e ->
                                    new GraphQLErrorInfo(
                                        redact(e.message()), e.path(), e.classification()))
                            .toList());
                  });
        });
  }

  /**
   * Runs a query and returns {@code data[field]} ({@link NullNode} when absent); any error fails.
   */
  CompletableFuture<JsonNode> field(
      String query, Map<String, ?> variables, String field, String operation) {
    return graphql(query, variables, operation)
        .thenApply(
            res -> {
              if (res.hasErrors()) {
                throw GraphQLResponse.toException(operation, res.errors(), this::redact);
              }
              return res.field(field).orElse(NullNode.getInstance());
            });
  }

  /** {@code getTimeInfo}; caches the time zone used to format query windows. */
  CompletableFuture<TimeInfo> timeInfo() {
    return field("{getTimeInfo{timezone currentTimestamp}}", Map.of(), "getTimeInfo", "timeInfo")
        .thenApply(
            n -> {
              String tz = n.path("timezone").isTextual() ? n.get("timezone").asText() : "+0000";
              long ts = JsonFields.num(n, "currentTimestamp", now().toEpochMilli());
              synchronized (lock) {
                if (offset == null) {
                  offset = CompletableFuture.completedFuture(GraphQLDuration.parseTimezone(tz));
                }
              }
              return new TimeInfo(tz, ts);
            });
  }

  /** OAP offset in minutes (fetched once, cached; a failure is not cached). */
  CompletableFuture<Integer> offsetMinutes() {
    synchronized (lock) {
      if (offset != null) {
        return offset;
      }
    }
    CompletableFuture<Integer> p =
        timeInfo().thenApply(t -> GraphQLDuration.parseTimezone(t.timezone()));
    p.whenComplete(
        (v, e) -> {
          if (e != null) {
            synchronized (lock) {
              if (offset == p) {
                offset = null;
              }
            }
          }
        });
    return p;
  }

  /** GraphQL {@code Duration} variable for a window, in the OAP time zone. */
  CompletableFuture<Map<String, Object>> duration(TimeRange range, String operation) {
    return guard(
        () -> {
          GraphQLDuration.Resolved resolved =
              (range == null ? TimeRange.DEFAULT : range).resolve(now(), operation);
          return offsetMinutes().thenApply(o -> GraphQLDuration.of(resolved, o).toVariable());
        });
  }

  /** Server feature probe (cached; a failure is not cached). */
  CompletableFuture<SkyWalkingCapabilities> capabilities() {
    CompletableFuture<SkyWalkingCapabilities> p;
    synchronized (lock) {
      if (capabilities != null) {
        return capabilities;
      }
      p =
          graphql("{hasQueryTracesV2Support supportQueryLogsByKeywords}", Map.of(), "capabilities")
              .thenApply(
                  r ->
                      new SkyWalkingCapabilities(
                          r.field("hasQueryTracesV2Support").map(JsonNode::asBoolean).orElse(false),
                          r.field("supportQueryLogsByKeywords")
                              .map(JsonNode::asBoolean)
                              .orElse(false)));
      capabilities = p;
    }
    p.whenComplete(
        (v, e) -> {
          if (e != null) {
            synchronized (lock) {
              if (capabilities == p) {
                capabilities = null;
              }
            }
          }
        });
    return p;
  }

  /**
   * GET on the Zipkin query API ({@code <zipkinUrl><path>}); null query values are skipped. 404 is
   * returned when accepted; 400 becomes a VALIDATION error with the server text.
   */
  CompletableFuture<TransportResponse> zipkinGet(
      String path, String operation, Map<String, ?> query, int... accept) {
    TransportRequest.Builder b =
        TransportRequest.get(config.zipkinUrl() + path).operation(operation).acceptStatus(400);
    if (accept.length > 0) {
      b.acceptStatus(accept);
    }
    query.forEach(
        (k, v) -> {
          if (v != null) {
            b.query(k, v);
          }
        });
    return transport
        .sendAsync(b.build())
        .thenApply(
            r -> {
              if (r.status() == 400) {
                String text = new String(r.body(), StandardCharsets.UTF_8).replaceAll("\\s+", " ");
                throw new ValidationException(
                    operation
                        + ": bad request: "
                        + Redaction.truncate(redact(text.trim()), Redaction.MAX_VENDOR_TEXT)
                        + " (HTTP 400)",
                    400,
                    r.requestId().orElse(null),
                    null,
                    null);
              }
              return r;
            });
  }
}
