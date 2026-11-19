package codes.grow.packages.skywalking;

import static codes.grow.packages.skywalking.SkyWalkingApi.guard;

import codes.grow.packages.skywalking.core.HttpTransport;
import codes.grow.packages.skywalking.core.Poll;
import codes.grow.packages.skywalking.core.UnsupportedException;
import codes.grow.packages.skywalking.core.ValidationException;
import codes.grow.packages.skywalking.tracing.query.DependencyLink;
import codes.grow.packages.skywalking.tracing.query.Trace;
import codes.grow.packages.skywalking.tracing.query.TraceQueryClient;
import codes.grow.packages.skywalking.tracing.query.TraceSearchQuery;
import codes.grow.packages.skywalking.tracing.query.TraceSummary;
import codes.grow.packages.skywalking.tracing.query.Traces;
import codes.grow.packages.skywalking.tracing.query.WaitForTraceOptions;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;

/**
 * {@link TraceQueryClient} for SkyWalking (SPEC section 5) over OAP's two trace stores. OTLP spans
 * (what {@link SkyWalkingTracing#initTracing} sends) are stored in OAP's Zipkin model and read via
 * the Zipkin query API ({@code :9412/zipkin}, needs {@code SW_QUERY_ZIPKIN}); SkyWalking agent
 * segments are read via GraphQL. The backend comes from {@link SkyWalkingConfig#traceBackend()}:
 *
 * <table>
 *   <caption>Store per method and backend</caption>
 *   <tr><th>method</th><th>zipkin</th><th>native</th><th>auto (default)</th></tr>
 *   <tr><td>getTrace</td><td>Zipkin</td><td>GraphQL</td>
 *       <td>hex id → Zipkin, then GraphQL; other ids → GraphQL</td></tr>
 *   <tr><td>searchTraces</td><td>Zipkin</td><td>GraphQL queryTraces</td><td>Zipkin</td></tr>
 *   <tr><td>listServices, listOperations</td><td>Zipkin</td><td>GraphQL</td>
 *       <td>union of both</td></tr>
 *   <tr><td>getDependencies</td><td>UnsupportedException</td><td>topology</td>
 *       <td>topology</td></tr>
 * </table>
 *
 * <p>Native traces keep their opaque ids ({@code <hex>.<n>.<ms>}); native span ids are {@code
 * <segmentId>-<spanId>} (see {@link NativeSpan#toSpan()}). OAP lower-cases OTLP span names ({@code
 * GET /checkout} → {@code get /checkout}). Obtain it from {@link SkyWalkingClient#traces()}.
 */
public final class SkyWalkingTraceQueryClient implements TraceQueryClient {
  /** Default {@code limit} of {@link #searchTraces} (SPEC section 5). */
  public static final int DEFAULT_SEARCH_LIMIT = 20;

  private final SkyWalkingApi api;
  private final SkyWalkingQueryClient query;
  private final TraceBackend backend;

  SkyWalkingTraceQueryClient(SkyWalkingApi api, SkyWalkingQueryClient query, TraceBackend backend) {
    this.api = api;
    this.query = query;
    this.backend = backend;
  }

  /**
   * The configured backend.
   *
   * @return the backend
   */
  public TraceBackend backend() {
    return backend;
  }

  /**
   * Returns a client reading from another backend.
   *
   * @param backend backend
   * @return a new client sharing this client's connections
   */
  public SkyWalkingTraceQueryClient withBackend(TraceBackend backend) {
    return new SkyWalkingTraceQueryClient(api, query, Objects.requireNonNull(backend, "backend"));
  }

  private CompletableFuture<Optional<Trace>> zipkinTrace(String traceId) {
    String op = "traces.getTrace";
    return guard(
        () -> {
          String id = ZipkinSpan.zipkinTraceId(traceId, op);
          return api.zipkinGet("/api/v2/trace/" + id, op, Map.of(), 404)
              .thenApply(
                  r ->
                      r.status() == 404
                          ? Optional.<Trace>empty()
                          : ZipkinSpan.toTrace(ZipkinSpan.listFrom(r.jsonNode())));
        });
  }

  private CompletableFuture<Optional<Trace>> nativeTrace(String traceId) {
    return query.getTraceAsync(traceId).thenApply(t -> t.flatMap(NativeTrace::toTrace));
  }

  /**
   * One trace in the SPEC model. Zipkin rejects non-hex ids with {@link ValidationException}.
   *
   * @param traceId hex (OTLP) or native trace id
   * @return the trace, or empty when it does not exist
   */
  @Override
  public Optional<Trace> getTrace(String traceId) {
    return HttpTransport.await(getTraceAsync(traceId), "traces.getTrace");
  }

  @Override
  public CompletableFuture<Optional<Trace>> getTraceAsync(String traceId) {
    return guard(
        () -> {
          if (traceId == null || traceId.isBlank()) {
            throw new ValidationException("traces.getTrace: traceId is required");
          }
          if (backend == TraceBackend.ZIPKIN) {
            return zipkinTrace(traceId);
          }
          if (backend == TraceBackend.NATIVE || !ZipkinSpan.isHexTraceId(traceId)) {
            return nativeTrace(traceId.trim());
          }
          return zipkinTrace(traceId)
              .thenCompose(
                  t ->
                      t.isPresent()
                          ? CompletableFuture.completedFuture(t)
                          : nativeTrace(traceId.trim().toLowerCase(Locale.ROOT)));
        });
  }

  /**
   * SPEC search. Zipkin and auto: {@code service} → {@code serviceName}, {@code operation} → {@code
   * spanName} (lower-cased like OAP stores names), {@code tags} → {@code annotationQuery} ({@code
   * k=v and …}; keys must be in {@code SW_ZIPKIN_SEARCHABLE_TAG_KEYS}), durations in µs, {@code
   * endTs} = end (default now), {@code lookback} = end − start when a start is set. Native: {@code
   * queryTraces} with {@code operation} as endpoint name (needs a service), durations in ms, window
   * default the last hour.
   *
   * @param query search
   * @return trace summaries
   */
  @Override
  public List<TraceSummary> searchTraces(TraceSearchQuery query) {
    return HttpTransport.await(searchTracesAsync(query), "traces.searchTraces");
  }

  @Override
  public CompletableFuture<List<TraceSummary>> searchTracesAsync(TraceSearchQuery search) {
    String op = "traces.searchTraces";
    return guard(
        () -> {
          TraceSearchQuery q = search == null ? TraceSearchQuery.builder().build() : search;
          Instant end = q.end().orElse(api.now());
          Optional<Instant> start = q.start();
          if (start.isPresent() && start.get().isAfter(end)) {
            throw new ValidationException(op + ": start must not be after end");
          }
          if (backend == TraceBackend.NATIVE) {
            return nativeSearch(q, start.orElse(end.minus(TimeRange.DEFAULT_WINDOW)), end);
          }
          return zipkinSearch(q, start, end);
        });
  }

  private CompletableFuture<List<TraceSummary>> nativeSearch(
      TraceSearchQuery q, Instant start, Instant end) {
    if (q.operation().isPresent() && q.service().isEmpty()) {
      throw new ValidationException(
          "traces.searchTraces: operation needs service on the native backend");
    }
    QueryTracesOptions.Builder b =
        QueryTracesOptions.builder()
            .service(q.service().orElse(null))
            .endpoint(q.operation().orElse(null))
            .tags(q.tags())
            .range(TimeRange.of(start, end))
            .paging(Paging.of(1, q.limit()));
    if (q.minDurationMicros().isPresent()) {
      b.minDurationMs(q.minDurationMicros().getAsLong() / 1000);
    }
    if (q.maxDurationMicros().isPresent()) {
      b.maxDurationMs((q.maxDurationMicros().getAsLong() + 999) / 1000);
    }
    return query
        .queryTracesAsync(b.build())
        .thenApply(
            res -> {
              List<TraceSummary> out = new ArrayList<>();
              for (NativeTrace t : res.traces()) {
                t.toTrace().map(Traces::summarize).ifPresent(out::add);
              }
              return out;
            });
  }

  private CompletableFuture<List<TraceSummary>> zipkinSearch(
      TraceSearchQuery q, Optional<Instant> start, Instant end) {
    String op = "traces.searchTraces";
    List<String> terms = new ArrayList<>();
    q.tags().forEach((k, v) -> terms.add(k + "=" + v));
    if (terms.stream().anyMatch(t -> t.contains(" and "))) {
      throw new ValidationException(op + ": tags must not contain \" and \"");
    }
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("serviceName", q.service().orElse(null));
    params.put("spanName", q.operation().map(o -> o.toLowerCase(Locale.ROOT)).orElse(null));
    params.put("annotationQuery", terms.isEmpty() ? null : String.join(" and ", terms));
    params.put(
        "minDuration",
        q.minDurationMicros().isPresent() ? q.minDurationMicros().getAsLong() : null);
    params.put(
        "maxDuration",
        q.maxDurationMicros().isPresent() ? q.maxDurationMicros().getAsLong() : null);
    params.put("endTs", end.toEpochMilli());
    params.put(
        "lookback",
        start.map(s -> Math.max(1, end.toEpochMilli() - s.toEpochMilli())).orElse(null));
    params.put("limit", q.limit());
    return api.zipkinGet("/api/v2/traces", op, params)
        .thenApply(
            r -> {
              List<TraceSummary> out = new ArrayList<>();
              JsonNode body = r.jsonNode();
              if (body.isArray()) {
                for (JsonNode trace : body) {
                  ZipkinSpan.toTrace(ZipkinSpan.listFrom(trace))
                      .map(Traces::summarize)
                      .ifPresent(out::add);
                }
              }
              return out;
            });
  }

  private static List<String> uniqueSorted(Collection<String> values) {
    TreeSet<String> set = new TreeSet<>();
    for (String v : values) {
      if (v != null && !v.isEmpty()) {
        set.add(v);
      }
    }
    return List.copyOf(set);
  }

  /**
   * Service names, sorted and unique. zipkin: {@code GET /api/v2/services} (OTLP senders); native:
   * the normal services of GraphQL {@code listServices} (agents and log senders); auto: the union.
   * New services appear about 30–35 s after their first data (OAP flushes its name index
   * periodically).
   *
   * @return the names
   */
  @Override
  public List<String> listServices() {
    return HttpTransport.await(listServicesAsync(), "traces.listServices");
  }

  @Override
  public CompletableFuture<List<String>> listServicesAsync() {
    String op = "traces.listServices";
    CompletableFuture<List<String>> zipkin =
        backend == TraceBackend.NATIVE
            ? CompletableFuture.completedFuture(List.of())
            : api.zipkinGet("/api/v2/services", op, Map.of())
                .thenApply(r -> JsonFields.strings(r.jsonNode()));
    CompletableFuture<List<String>> nativeNames =
        backend == TraceBackend.ZIPKIN
            ? CompletableFuture.completedFuture(List.of())
            : query
                .listServicesAsync(null, null)
                .thenApply(
                    s ->
                        s.stream()
                            .filter(SkyWalkingService::normal)
                            .map(SkyWalkingService::name)
                            .toList());
    return zipkin.thenCombine(
        nativeNames,
        (a, b) -> {
          List<String> all = new ArrayList<>(a);
          all.addAll(b);
          return uniqueSorted(all);
        });
  }

  /**
   * Operation names of a service, sorted and unique. zipkin: {@code GET /api/v2/spans?serviceName=}
   * (lower-cased span names); native: the service's endpoints (at most {@value
   * SkyWalkingQueryClient#DEFAULT_ENDPOINT_LIMIT}); auto: the union.
   *
   * @param service service name
   * @return the names
   */
  @Override
  public List<String> listOperations(String service) {
    return HttpTransport.await(listOperationsAsync(service), "traces.listOperations");
  }

  @Override
  public CompletableFuture<List<String>> listOperationsAsync(String service) {
    String op = "traces.listOperations";
    return guard(
        () -> {
          SkyWalkingApi.required(service, "service", op);
          CompletableFuture<List<String>> zipkin =
              backend == TraceBackend.NATIVE
                  ? CompletableFuture.completedFuture(List.of())
                  : api.zipkinGet("/api/v2/spans", op, Map.of("serviceName", service))
                      .thenApply(r -> JsonFields.strings(r.jsonNode()));
          CompletableFuture<List<String>> endpoints =
              backend == TraceBackend.ZIPKIN
                  ? CompletableFuture.completedFuture(List.of())
                  : query
                      .listEndpointsAsync(
                          service, null, SkyWalkingQueryClient.DEFAULT_ENDPOINT_LIMIT)
                      .thenApply(e -> e.stream().map(SkyWalkingEndpoint::name).toList());
          return zipkin.thenCombine(
              endpoints,
              (a, b) -> {
                List<String> all = new ArrayList<>(a);
                all.addAll(b);
                return uniqueSorted(all);
              });
        });
  }

  /**
   * Service dependencies from the native topology of the window (default the last hour): parent =
   * source service, child = target service, {@code callCount} 0 (the topology carries no counts).
   * OAP 10.4 does not implement Zipkin {@code /dependencies}, so the zipkin backend throws {@link
   * UnsupportedException}; OTLP spans create no topology edges.
   *
   * @param start window start, or null
   * @param end window end, or null
   * @return the links
   */
  @Override
  public List<DependencyLink> getDependencies(Instant start, Instant end) {
    return HttpTransport.await(getDependenciesAsync(start, end), "traces.getDependencies");
  }

  @Override
  public CompletableFuture<List<DependencyLink>> getDependenciesAsync(Instant start, Instant end) {
    if (backend == TraceBackend.ZIPKIN) {
      return CompletableFuture.failedFuture(
          new UnsupportedException(
              "traces.getDependencies: OAP does not implement the Zipkin dependencies API"));
    }
    return query
        .topologyAsync(TimeRange.of(start, end), TopologyOptions.GLOBAL)
        .thenApply(Topology::toDependencies);
  }

  /**
   * Polls {@link #getTrace} until the trace has {@code minSpans} spans ({@code TimeoutException}
   * otherwise). Native ids are used verbatim; hex ids are normalised.
   *
   * @param traceId trace id
   * @param options timeout, interval and minimum span count
   * @return the trace
   */
  @Override
  public Trace waitForTrace(String traceId, WaitForTraceOptions options) {
    String id = waitId(traceId);
    return Poll.until(
        "traces.waitForTrace " + id,
        options.timeout(),
        options.interval(),
        () -> getTrace(id).filter(t -> t.spans().size() >= options.minSpans()));
  }

  @Override
  public CompletableFuture<Trace> waitForTraceAsync(String traceId, WaitForTraceOptions options) {
    return guard(
        () -> {
          String id = waitId(traceId);
          return Poll.untilAsync(
              "traces.waitForTrace " + id,
              options.timeout(),
              options.interval(),
              () ->
                  getTraceAsync(id)
                      .thenApply(t -> t.filter(x -> x.spans().size() >= options.minSpans())));
        });
  }

  private static String waitId(String traceId) {
    if (traceId == null || traceId.isBlank()) {
      throw new ValidationException("traces.waitForTrace: traceId is required");
    }
    String t = traceId.trim();
    return ZipkinSpan.isHexTraceId(t)
        ? codes.grow.packages.skywalking.tracing.query.TraceIds.normalizeTraceId(t)
        : t;
  }
}
