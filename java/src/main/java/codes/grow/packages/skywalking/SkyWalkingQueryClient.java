package codes.grow.packages.skywalking;

import static codes.grow.packages.skywalking.SkyWalkingApi.guard;
import static codes.grow.packages.skywalking.SkyWalkingApi.required;

import codes.grow.packages.skywalking.core.HttpTransport;
import codes.grow.packages.skywalking.core.SkyWalkingException;
import codes.grow.packages.skywalking.core.ValidationException;
import codes.grow.packages.skywalking.logging.SortOrder;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The SkyWalking GraphQL query API ({@code POST /graphql} on port 12800): services, endpoints,
 * instances, native traces, logs, topology and raw GraphQL. Obtain it from {@link
 * SkyWalkingClient#query()}. Services, instances and endpoints are given by name; the SDK derives
 * the ids ({@link SkyWalkingIds}).
 *
 * <p>Every method has a blocking form and an {@code …Async} form returning {@link
 * CompletableFuture}. Errors: HTTP 400 and resolver errors → {@link ValidationException}, an {@code
 * UnsupportedOperationException} on the server → {@code UnsupportedException}, other statuses per
 * SPEC section 2.1. Thread-safe.
 */
public final class SkyWalkingQueryClient {
  /** Default number of endpoints {@link #listEndpoints} returns. */
  public static final int DEFAULT_ENDPOINT_LIMIT = 100;

  private final SkyWalkingApi api;

  SkyWalkingQueryClient(SkyWalkingApi api) {
    this.api = api;
  }

  /**
   * Raw GraphQL: returns {@code data} and {@code errors} (partial data possible). HTTP 400 (syntax
   * or validation errors) throws {@link ValidationException}.
   *
   * @param query GraphQL document
   * @param variables variables (may be null)
   * @return the response
   */
  public GraphQLResponse graphql(String query, Map<String, ?> variables) {
    return HttpTransport.await(graphqlAsync(query, variables), "graphql");
  }

  /**
   * Raw GraphQL without variables.
   *
   * @param query GraphQL document
   * @return the response
   */
  public GraphQLResponse graphql(String query) {
    return graphql(query, Map.of());
  }

  /**
   * Asynchronous {@link #graphql(String, Map)}.
   *
   * @param query GraphQL document
   * @param variables variables (may be null)
   * @return a future with the response
   */
  public CompletableFuture<GraphQLResponse> graphqlAsync(String query, Map<String, ?> variables) {
    return api.graphql(query, variables, "graphql");
  }

  /**
   * OAP clock and time zone ({@code getTimeInfo}); caches the time zone for query windows.
   *
   * @return the time info
   */
  public TimeInfo timeInfo() {
    return HttpTransport.await(timeInfoAsync(), "timeInfo");
  }

  /**
   * Asynchronous {@link #timeInfo()}.
   *
   * @return a future with the time info
   */
  public CompletableFuture<TimeInfo> timeInfoAsync() {
    return api.timeInfo();
  }

  /**
   * Server features ({@code hasQueryTracesV2Support}, {@code supportQueryLogsByKeywords}), probed
   * once and cached.
   *
   * @return the capabilities
   */
  public SkyWalkingCapabilities capabilities() {
    return HttpTransport.await(capabilitiesAsync(), "capabilities");
  }

  /**
   * Asynchronous {@link #capabilities()}.
   *
   * @return a future with the capabilities
   */
  public CompletableFuture<SkyWalkingCapabilities> capabilitiesAsync() {
    return api.capabilities();
  }

  /**
   * Every service of every layer.
   *
   * @return the services
   */
  public List<SkyWalkingService> listServices() {
    return listServices(null, null);
  }

  /**
   * Services of one layer ({@code listServices(layer)}).
   *
   * @param layer layer such as {@code GENERAL}; null = all layers; an unknown layer gives {@code
   *     []}
   * @return the services
   */
  public List<SkyWalkingService> listServices(String layer) {
    return listServices(layer, null);
  }

  /**
   * Services ({@code listServices(layer)}). OAP has no time filter for services, so {@code range}
   * is accepted for API symmetry and ignored. Services that only sent OTLP <b>traces</b> are not
   * listed (use {@link SkyWalkingTraceQueryClient#listServices()}); new services appear about 30 s
   * after their first data.
   *
   * @param layer layer, or null for all layers
   * @param range ignored
   * @return the services
   */
  public List<SkyWalkingService> listServices(String layer, TimeRange range) {
    return HttpTransport.await(listServicesAsync(layer, range), "query.listServices");
  }

  /**
   * Asynchronous {@link #listServices(String, TimeRange)}.
   *
   * @param layer layer, or null for all layers
   * @param range ignored
   * @return a future with the services
   */
  public CompletableFuture<List<SkyWalkingService>> listServicesAsync(
      String layer, TimeRange range) {
    Map<String, Object> vars = new HashMap<>();
    vars.put("l", layer);
    return api.field(
            "query($l:String){listServices(layer:$l){" + SkyWalkingService.FIELDS + "}}",
            vars,
            "listServices",
            "query.listServices")
        .thenApply(rows -> JsonFields.objects(rows, SkyWalkingService::fromJson));
  }

  /**
   * One service by name ({@code findService}).
   *
   * @param name service name
   * @return the service, or empty when OAP does not know it
   */
  public Optional<SkyWalkingService> findService(String name) {
    return HttpTransport.await(findServiceAsync(name), "query.findService");
  }

  /**
   * Asynchronous {@link #findService(String)}.
   *
   * @param name service name
   * @return a future with the service, or empty
   */
  public CompletableFuture<Optional<SkyWalkingService>> findServiceAsync(String name) {
    String op = "query.findService";
    return guard(
        () ->
            api.field(
                    "query($n:String!){findService(serviceName:$n){"
                        + SkyWalkingService.FIELDS
                        + "}}",
                    Map.of("n", required(name, "name", op)),
                    "findService",
                    op)
                .thenApply(
                    row ->
                        row.isObject()
                            ? Optional.of(SkyWalkingService.fromJson(row))
                            : Optional.empty()));
  }

  /**
   * Every endpoint of a service (at most {@value #DEFAULT_ENDPOINT_LIMIT}).
   *
   * @param service service name
   * @return the endpoints
   */
  public List<SkyWalkingEndpoint> listEndpoints(String service) {
    return listEndpoints(service, null, DEFAULT_ENDPOINT_LIMIT);
  }

  /**
   * Endpoints of a service matching a keyword (at most {@value #DEFAULT_ENDPOINT_LIMIT}).
   *
   * @param service service name
   * @param keyword keyword or null
   * @return the endpoints
   */
  public List<SkyWalkingEndpoint> listEndpoints(String service, String keyword) {
    return listEndpoints(service, keyword, DEFAULT_ENDPOINT_LIMIT);
  }

  /**
   * Endpoints of a service ({@code findEndpoint(keyword, serviceId, limit)}). {@code keyword} is
   * OAP's token match, not a substring match: {@code downstream} finds {@code /native/downstream},
   * {@code down} does not; null or empty lists all. No duration is sent (OAP 10.4 + BanyanDB
   * returns nothing with one). Only native services have endpoints; OTLP spans create none.
   *
   * @param service service name
   * @param keyword keyword or null
   * @param limit maximum number of endpoints (positive)
   * @return the endpoints
   */
  public List<SkyWalkingEndpoint> listEndpoints(String service, String keyword, int limit) {
    return HttpTransport.await(listEndpointsAsync(service, keyword, limit), "query.listEndpoints");
  }

  /**
   * Asynchronous {@link #listEndpoints(String, String, int)}.
   *
   * @param service service name
   * @param keyword keyword or null
   * @param limit maximum number of endpoints
   * @return a future with the endpoints
   */
  public CompletableFuture<List<SkyWalkingEndpoint>> listEndpointsAsync(
      String service, String keyword, int limit) {
    String op = "query.listEndpoints";
    return guard(
        () -> {
          if (limit < 1) {
            throw new ValidationException(op + ": limit must be a positive integer");
          }
          Map<String, Object> vars = new HashMap<>();
          vars.put("k", keyword == null || keyword.isEmpty() ? null : keyword);
          vars.put("s", SkyWalkingIds.serviceId(required(service, "service", op)));
          vars.put("l", limit);
          return api.field(
                  "query($k:String,$s:ID!,$l:Int!)"
                      + "{findEndpoint(keyword:$k,serviceId:$s,limit:$l){id name}}",
                  vars,
                  "findEndpoint",
                  op)
              .thenApply(
                  rows ->
                      JsonFields.objects(
                          rows,
                          e ->
                              new SkyWalkingEndpoint(
                                  JsonFields.str(e, "id"), JsonFields.str(e, "name"))));
        });
  }

  /**
   * Instances of a service active in the last hour.
   *
   * @param service service name
   * @return the instances
   */
  public List<SkyWalkingInstance> listInstances(String service) {
    return listInstances(service, null);
  }

  /**
   * Instances of a service active in {@code range} ({@code listInstances}).
   *
   * @param service service name
   * @param range window, or null for the last hour
   * @return the instances
   */
  public List<SkyWalkingInstance> listInstances(String service, TimeRange range) {
    return HttpTransport.await(listInstancesAsync(service, range), "query.listInstances");
  }

  /**
   * Asynchronous {@link #listInstances(String, TimeRange)}.
   *
   * @param service service name
   * @param range window, or null for the last hour
   * @return a future with the instances
   */
  public CompletableFuture<List<SkyWalkingInstance>> listInstancesAsync(
      String service, TimeRange range) {
    String op = "query.listInstances";
    return guard(
        () -> {
          String id = SkyWalkingIds.serviceId(required(service, "service", op));
          return api.duration(range, op)
              .thenCompose(
                  d ->
                      api.field(
                          "query($s:ID!,$d:Duration!){listInstances(serviceId:$s,duration:$d)"
                              + "{id name language instanceUUID attributes{name value}}}",
                          Map.of("s", id, "d", d),
                          "listInstances",
                          op))
              .thenApply(
                  rows ->
                      JsonFields.objects(
                          rows,
                          i ->
                              new SkyWalkingInstance(
                                  JsonFields.str(i, "id"),
                                  JsonFields.str(i, "name"),
                                  JsonFields.str(i, "language"),
                                  JsonFields.str(i, "instanceUUID"),
                                  JsonFields.objects(
                                      i,
                                      "attributes",
                                      a ->
                                          new SkyWalkingInstance.Attribute(
                                              JsonFields.str(a, "name"),
                                              JsonFields.str(a, "value"))))));
        });
  }

  private static Map<String, Object> scopedIds(
      Optional<String> service, Optional<String> instance, Optional<String> endpoint, String op) {
    if ((instance.isPresent() || endpoint.isPresent()) && service.isEmpty()) {
      throw new ValidationException(op + ": instance and endpoint need service");
    }
    Map<String, Object> out = new LinkedHashMap<>();
    if (service.isPresent()) {
      String s = required(service.get(), "service", op);
      out.put("serviceId", SkyWalkingIds.serviceId(s));
      instance.ifPresent(i -> out.put("serviceInstanceId", SkyWalkingIds.instanceId(s, i)));
      endpoint.ifPresent(e -> out.put("endpointId", SkyWalkingIds.endpointId(s, e)));
    }
    return out;
  }

  private static List<Map<String, String>> tagList(Map<String, String> tags) {
    List<Map<String, String>> out = new ArrayList<>();
    tags.forEach((k, v) -> out.add(Map.of("key", k, "value", v)));
    return out;
  }

  private static Map<String, Object> pagingVar(Paging p, String op) {
    p.validate(op);
    return Map.of("pageNum", p.pageNum(), "pageSize", p.pageSize());
  }

  /**
   * Native traces ({@code queryTraces(condition)}, whole traces). Durations in ms; tags must be
   * searchable keys. Traces sent over OTLP are not stored here (use {@link
   * SkyWalkingClient#traces()} with the zipkin backend). The legacy {@code queryBasicTraces} is not
   * used (unsupported on BanyanDB).
   *
   * @param options filter
   * @return one page of traces
   */
  public QueryTracesResult queryTraces(QueryTracesOptions options) {
    return HttpTransport.await(queryTracesAsync(options), "query.queryTraces");
  }

  /**
   * Asynchronous {@link #queryTraces(QueryTracesOptions)}.
   *
   * @param options filter
   * @return a future with one page of traces
   */
  public CompletableFuture<QueryTracesResult> queryTracesAsync(QueryTracesOptions options) {
    String op = "query.queryTraces";
    return guard(
        () -> {
          QueryTracesOptions o = options == null ? QueryTracesOptions.defaults() : options;
          Map<String, Object> cond =
              new LinkedHashMap<>(scopedIds(o.service(), o.instance(), o.endpoint(), op));
          cond.put("traceState", o.traceState().name());
          cond.put("queryOrder", o.order().name());
          cond.put("paging", pagingVar(o.paging(), op));
          if (o.minDurationMs().orElse(0) < 0 || o.maxDurationMs().orElse(0) < 0) {
            throw new ValidationException(op + ": durations must be non-negative");
          }
          o.minDurationMs().ifPresent(v -> cond.put("minTraceDuration", v));
          o.maxDurationMs().ifPresent(v -> cond.put("maxTraceDuration", v));
          o.traceId().ifPresent(id -> cond.put("traceId", required(id, "traceId", op)));
          if (!o.tags().isEmpty()) {
            cond.put("tags", tagList(o.tags()));
          }
          CompletableFuture<Map<String, Object>> withDuration =
              o.traceId().isEmpty() || o.range().isPresent()
                  ? api.duration(o.range().orElse(null), op)
                      .thenApply(
                          d -> {
                            cond.put("queryDuration", d);
                            return cond;
                          })
                  : CompletableFuture.completedFuture(cond);
          return withDuration
              .thenCompose(
                  c ->
                      api.field(
                          "query($c:TraceQueryCondition){queryTraces(condition:$c){traces{spans{"
                              + NativeSpan.FIELDS
                              + "}} retrievedTimeRange{startTime endTime}}}",
                          Map.of("c", c),
                          "queryTraces",
                          op))
              .thenApply(SkyWalkingQueryClient::tracesResult);
        });
  }

  private static QueryTracesResult tracesResult(JsonNode body) {
    List<NativeTrace> traces = new ArrayList<>();
    JsonNode list = body.get("traces");
    if (list != null && list.isArray()) {
      for (JsonNode t : list) {
        NativeTrace.fromSpans(t.get("spans")).ifPresent(traces::add);
      }
    }
    JsonNode r = body.get("retrievedTimeRange");
    Optional<QueryTracesResult.RetrievedTimeRange> range =
        r != null && r.isObject() && r.path("startTime").isNumber() && r.path("endTime").isNumber()
            ? Optional.of(
                new QueryTracesResult.RetrievedTimeRange(
                    r.get("startTime").asLong(), r.get("endTime").asLong()))
            : Optional.empty();
    return new QueryTracesResult(traces, range);
  }

  /**
   * One native trace ({@code queryTrace(traceId)}); the id is kept verbatim.
   *
   * @param traceId native trace id
   * @return the trace, or empty when unknown (OTLP trace ids are never native)
   */
  public Optional<NativeTrace> getTrace(String traceId) {
    return HttpTransport.await(getTraceAsync(traceId), "query.getTrace");
  }

  /**
   * Asynchronous {@link #getTrace(String)}.
   *
   * @param traceId native trace id
   * @return a future with the trace, or empty
   */
  public CompletableFuture<Optional<NativeTrace>> getTraceAsync(String traceId) {
    String op = "query.getTrace";
    return guard(
        () ->
            api.field(
                    "query($id:ID!){queryTrace(traceId:$id){spans{" + NativeSpan.FIELDS + "}}}",
                    Map.of("id", required(traceId, "traceId", op).trim()),
                    "queryTrace",
                    op)
                .thenApply(res -> NativeTrace.fromSpans(res.get("spans"))));
  }

  /**
   * Logs ({@code queryLogs(condition)}). Either a window (default the last hour) or a trace id is
   * sent; see {@link QueryLogsOptions#keywords()} for keyword handling on BanyanDB.
   *
   * @param options filter
   * @return one page of logs
   */
  public QueryLogsResult queryLogs(QueryLogsOptions options) {
    return HttpTransport.await(queryLogsAsync(options), "query.queryLogs");
  }

  /**
   * Asynchronous {@link #queryLogs(QueryLogsOptions)}.
   *
   * @param options filter
   * @return a future with one page of logs
   */
  public CompletableFuture<QueryLogsResult> queryLogsAsync(QueryLogsOptions options) {
    String op = "query.queryLogs";
    return guard(
        () -> {
          QueryLogsOptions o = options == null ? QueryLogsOptions.defaults() : options;
          Map<String, Object> cond =
              new LinkedHashMap<>(scopedIds(o.service(), o.instance(), o.endpoint(), op));
          cond.put("paging", pagingVar(o.paging(), op));
          cond.put("queryOrder", o.order() == SortOrder.ASC ? "ASC" : "DES");
          if ((o.segmentId().isPresent() || o.spanId().isPresent()) && o.traceId().isEmpty()) {
            throw new ValidationException(op + ": segmentId and spanId need traceId");
          }
          if (o.traceId().isPresent()) {
            Map<String, Object> related = new LinkedHashMap<>();
            related.put("traceId", required(o.traceId().get(), "traceId", op));
            o.segmentId().ifPresent(s -> related.put("segmentId", s));
            o.spanId().ifPresent(s -> related.put("spanId", s));
            cond.put("relatedTrace", related);
          }
          if (!o.tags().isEmpty()) {
            cond.put("tags", tagList(o.tags()));
          }
          List<String> keywords = o.keywords();
          List<String> excluding = o.excludingKeywords();
          CompletableFuture<Boolean> serverKeywords =
              keywords.isEmpty() && excluding.isEmpty()
                  ? CompletableFuture.completedFuture(false)
                  : api.capabilities().thenApply(SkyWalkingCapabilities::logsByKeywords);
          return serverKeywords.thenCompose(
              server -> {
                if (server) {
                  if (!keywords.isEmpty()) {
                    cond.put("keywordsOfContent", keywords);
                  }
                  if (!excluding.isEmpty()) {
                    cond.put("excludingKeywordsOfContent", excluding);
                  }
                }
                CompletableFuture<Map<String, Object>> withDuration =
                    o.traceId().isEmpty() || o.range().isPresent()
                        ? api.duration(o.range().orElse(null), op)
                            .thenApply(
                                d -> {
                                  cond.put("queryDuration", d);
                                  return cond;
                                })
                        : CompletableFuture.completedFuture(cond);
                return withDuration
                    .thenCompose(
                        c ->
                            api.field(
                                "query($c:LogQueryCondition){queryLogs(condition:$c){errorReason"
                                    + " logs{"
                                    + NativeLog.FIELDS
                                    + "}}}",
                                Map.of("c", c),
                                "queryLogs",
                                op))
                    .thenApply(
                        body ->
                            logsResult(
                                body,
                                server ? List.of() : keywords,
                                server ? List.of() : excluding));
              });
        });
  }

  private static QueryLogsResult logsResult(
      JsonNode body, List<String> keywords, List<String> excluding) {
    List<NativeLog> logs = new ArrayList<>();
    for (NativeLog l : JsonFields.objects(body, "logs", NativeLog::fromJson)) {
      String content = l.content().toLowerCase(Locale.ROOT);
      boolean all = keywords.stream().allMatch(w -> content.contains(w.toLowerCase(Locale.ROOT)));
      boolean none =
          excluding.stream().noneMatch(w -> content.contains(w.toLowerCase(Locale.ROOT)));
      if (all && none) {
        logs.add(l);
      }
    }
    String reason = JsonFields.str(body, "errorReason");
    return new QueryLogsResult(logs, reason.isEmpty() ? Optional.empty() : Optional.of(reason));
  }

  /**
   * Log tag keys seen in the last hour ({@code queryLogTagAutocompleteKeys}), sorted.
   *
   * @return the keys
   */
  public List<String> listLogTagKeys() {
    return listLogTagKeys(null);
  }

  /**
   * Log tag keys seen in {@code range} ({@code queryLogTagAutocompleteKeys}), sorted.
   *
   * @param range window, or null for the last hour
   * @return the keys
   */
  public List<String> listLogTagKeys(TimeRange range) {
    return HttpTransport.await(listLogTagKeysAsync(range), "query.listLogTagKeys");
  }

  /**
   * Asynchronous {@link #listLogTagKeys(TimeRange)}.
   *
   * @param range window, or null for the last hour
   * @return a future with the keys
   */
  public CompletableFuture<List<String>> listLogTagKeysAsync(TimeRange range) {
    String op = "query.listLogTagKeys";
    return api.duration(range, op)
        .thenCompose(
            d ->
                api.field(
                    "query($d:Duration!){queryLogTagAutocompleteKeys(duration:$d)}",
                    Map.of("d", d),
                    "queryLogTagAutocompleteKeys",
                    op))
        .thenApply(
            keys -> {
              List<String> out = JsonFields.strings(keys);
              out.sort(null);
              return out;
            });
  }

  /**
   * The global service topology of the last hour.
   *
   * @return the topology
   */
  public Topology topology() {
    return topology(null, TopologyOptions.GLOBAL);
  }

  /**
   * The global service topology of {@code range}.
   *
   * @param range window, or null for the last hour
   * @return the topology
   */
  public Topology topology(TimeRange range) {
    return topology(range, TopologyOptions.GLOBAL);
  }

  /**
   * Service topology ({@code getGlobalTopology(duration, layer)}, or {@code getServiceTopology}
   * when {@link TopologyOptions#service()} is set). Native traffic only (OTLP spans create no
   * edges); includes the virtual {@code User} node.
   *
   * @param range window, or null for the last hour
   * @param options service or layer filter
   * @return the topology
   * @throws SkyWalkingException on failure
   */
  public Topology topology(TimeRange range, TopologyOptions options) {
    return HttpTransport.await(topologyAsync(range, options), "query.topology");
  }

  /**
   * Asynchronous {@link #topology(TimeRange, TopologyOptions)}.
   *
   * @param range window, or null for the last hour
   * @param options service or layer filter
   * @return a future with the topology
   */
  public CompletableFuture<Topology> topologyAsync(TimeRange range, TopologyOptions options) {
    String op = "query.topology";
    TopologyOptions o = options == null ? TopologyOptions.GLOBAL : options;
    return guard(
        () -> {
          String serviceId =
              o.service()
                  .map(s -> SkyWalkingIds.serviceId(required(s, "service", op)))
                  .orElse(null);
          return api.duration(range, op)
              .thenCompose(
                  d -> {
                    if (serviceId != null) {
                      return api.field(
                          "query($s:ID!,$d:Duration!){getServiceTopology(serviceId:$s,duration:$d){"
                              + Topology.FIELDS
                              + "}}",
                          Map.of("s", serviceId, "d", d),
                          "getServiceTopology",
                          op);
                    }
                    Map<String, Object> vars = new HashMap<>();
                    vars.put("d", d);
                    vars.put("l", o.layer().orElse(null));
                    return api.field(
                        "query($d:Duration!,$l:String){getGlobalTopology(duration:$d,layer:$l){"
                            + Topology.FIELDS
                            + "}}",
                        vars,
                        "getGlobalTopology",
                        op);
                  })
              .thenApply(Topology::fromJson);
        });
  }
}
