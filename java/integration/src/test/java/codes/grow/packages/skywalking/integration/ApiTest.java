package codes.grow.packages.skywalking.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import codes.grow.packages.skywalking.NativeLog;
import codes.grow.packages.skywalking.NativeSpan;
import codes.grow.packages.skywalking.NativeTrace;
import codes.grow.packages.skywalking.Paging;
import codes.grow.packages.skywalking.QueryLogsOptions;
import codes.grow.packages.skywalking.QueryLogsResult;
import codes.grow.packages.skywalking.QueryTracesOptions;
import codes.grow.packages.skywalking.QueryTracesResult;
import codes.grow.packages.skywalking.SkyWalkingCapabilities;
import codes.grow.packages.skywalking.SkyWalkingClient;
import codes.grow.packages.skywalking.SkyWalkingConfig;
import codes.grow.packages.skywalking.SkyWalkingEndpoint;
import codes.grow.packages.skywalking.SkyWalkingIds;
import codes.grow.packages.skywalking.SkyWalkingInstance;
import codes.grow.packages.skywalking.SkyWalkingLogClientOptions;
import codes.grow.packages.skywalking.SkyWalkingLogExporter;
import codes.grow.packages.skywalking.SkyWalkingService;
import codes.grow.packages.skywalking.SkyWalkingTracing;
import codes.grow.packages.skywalking.Sw8;
import codes.grow.packages.skywalking.Sw8Context;
import codes.grow.packages.skywalking.Sw8Propagator;
import codes.grow.packages.skywalking.TimeInfo;
import codes.grow.packages.skywalking.TimeRange;
import codes.grow.packages.skywalking.Topology;
import codes.grow.packages.skywalking.TopologyOptions;
import codes.grow.packages.skywalking.TraceBackend;
import codes.grow.packages.skywalking.TraceState;
import codes.grow.packages.skywalking.core.EnvReader;
import codes.grow.packages.skywalking.core.HealthStatus;
import codes.grow.packages.skywalking.core.Json;
import codes.grow.packages.skywalking.core.Poll;
import codes.grow.packages.skywalking.core.ValidationException;
import codes.grow.packages.skywalking.logging.BatchOptions;
import codes.grow.packages.skywalking.logging.BatchingLogClient;
import codes.grow.packages.skywalking.logging.ExportOptions;
import codes.grow.packages.skywalking.logging.LogInput;
import codes.grow.packages.skywalking.logging.LogRecord;
import codes.grow.packages.skywalking.logging.LogSearchResult;
import codes.grow.packages.skywalking.logging.SearchOptions;
import codes.grow.packages.skywalking.logging.Severity;
import codes.grow.packages.skywalking.logging.SortOrder;
import codes.grow.packages.skywalking.logging.TailHandle;
import codes.grow.packages.skywalking.logging.TailOptions;
import codes.grow.packages.skywalking.logging.WaitForLogOptions;
import codes.grow.packages.skywalking.tracing.TracingConfig;
import codes.grow.packages.skywalking.tracing.TracingHandle;
import codes.grow.packages.skywalking.tracing.query.DependencyLink;
import codes.grow.packages.skywalking.tracing.query.Span;
import codes.grow.packages.skywalking.tracing.query.Trace;
import codes.grow.packages.skywalking.tracing.query.TraceSearchQuery;
import codes.grow.packages.skywalking.tracing.query.TraceSummary;
import codes.grow.packages.skywalking.tracing.query.WaitForTraceOptions;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Every public function against the real SkyWalking OAP 10.4.0 + BanyanDB (docker/compose.yml),
 * each with a round-trip assertion (write → read back). Native traces are seeded over OAP's
 * documented HTTP API ({@code /v3/segments}) because no SkyWalking agent runs in the test
 * container; OTLP spans come from the package's own {@code initTracing}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApiTest {
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Duration WAIT = Duration.ofSeconds(150);

  private final String run = hex(8);
  private final String svcA = "it-native-a-" + run;
  private final String svcB = "it-native-b-" + run;
  private final String svcOtlp = "it-otlp-" + run;
  private final String svcLogs = "it-logs-" + run;

  private Map<String, String> env;
  private SkyWalkingClient client;
  private String otlpTraceId;
  private String otlpSpanId;
  private String nativeTraceId;
  private String segA;
  private String segB;

  private static String hex(int n) {
    byte[] b = new byte[(n + 1) / 2];
    RANDOM.nextBytes(b);
    return HexFormat.of().formatHex(b).substring(0, n);
  }

  private static <T> T poll(String label, Supplier<T> fn, Predicate<T> ok) {
    return Poll.until(
        label,
        WAIT,
        Duration.ofSeconds(2),
        () -> {
          T v = fn.get();
          return ok.test(v) ? Optional.of(v) : Optional.empty();
        });
  }

  @BeforeAll
  void seed() throws Exception {
    env = ConformanceTest.environment();
    assumeTrue(
        env.containsKey(EnvReader.DEFAULT_PREFIX + "URL"),
        "GROW_APACHE_SKYWALKING_URL is not set; use docker/compose.yml");
    client = SkyWalkingClient.builder().fromEnv(EnvReader.of(env)).build();
    seedNativeTrace();
    TracingHandle handle =
        client.initTracing(
            TracingConfig.builder(svcOtlp)
                .setGlobal(false)
                .propagators(List.of("tracecontext", "baggage", "sw8"))
                .build());
    Tracer tracer = handle.tracer("it", "1.0.0");
    io.opentelemetry.api.trace.Span root =
        tracer
            .spanBuilder("GET /checkout")
            .setAttribute("run_id", run)
            .setAttribute("retries", 2L)
            .startSpan();
    otlpTraceId = root.getSpanContext().getTraceId();
    otlpSpanId = root.getSpanContext().getSpanId();
    io.opentelemetry.api.trace.Span child =
        tracer.spanBuilder("select orders").setParent(Context.root().with(root)).startSpan();
    child.addEvent("cache.miss", Attributes.builder().put("k", "v").build());
    child.end();
    root.end();
    handle.forceFlush(Duration.ofSeconds(30));
    BatchingLogClient logs =
        client.logClient(
            SkyWalkingLogClientOptions.builder()
                .serviceName(svcLogs)
                .serviceInstance("inst-" + run)
                .batchOptions(BatchOptions.builder().flushInterval(Duration.ofMillis(200)).build())
                .build());
    logs.send(
        LogInput.of(
            Severity.WARN, "order created native-log-" + run, Map.of("run_id", run, "n", 1)));
    logs.send(
        LogInput.builder("payment failed native-log-" + run)
            .severity(Severity.ERROR)
            .attribute("run_id", run)
            .traceId(otlpTraceId)
            .spanId(otlpSpanId)
            .build());
    logs.flush();
    assertEquals(2, logs.stats().sent());
  }

  /** Seeds a native A → B trace; B's ref is built by decoding the sw8 header of A's exit span. */
  private void seedNativeTrace() throws Exception {
    long now = System.currentTimeMillis();
    nativeTraceId = hex(32) + ".1." + now;
    segA = hex(32) + ".1." + now;
    segB = hex(32) + ".2." + now;
    String header =
        Sw8.encode(
            new Sw8Context(
                true,
                nativeTraceId,
                segA,
                1,
                svcA,
                "inst-a-" + run,
                "/it/entry",
                svcB + ":8080"));
    Sw8Context ref = Sw8.decode(header).orElseThrow();
    List<Map<String, Object>> segments =
        List.of(
            Map.of(
                "traceId", nativeTraceId,
                "traceSegmentId", segA,
                "service", svcA,
                "serviceInstance", "inst-a-" + run,
                "spans",
                    List.of(
                        span(1, 0, now - 80, now - 20, "/it/downstream", svcB + ":8080", "Exit", 2,
                            false, List.of(tag("http.method", "GET")), null, null),
                        span(0, -1, now - 100, now, "/it/entry", "", "Entry", 1, false,
                            List.of(tag("http.method", "GET"), tag("run_id", run)), null, null))),
            Map.of(
                "traceId", ref.traceId(),
                "traceSegmentId", segB,
                "service", svcB,
                "serviceInstance", "inst-b-" + run,
                "spans",
                    List.of(
                        span(0, -1, now - 70, now - 30, "/it/downstream", "", "Entry", 1, true,
                            List.of(tag("run_id", run)),
                            List.of(
                                Map.of(
                                    "refType", "CrossProcess",
                                    "traceId", ref.traceId(),
                                    "parentTraceSegmentId", ref.parentTraceSegmentId(),
                                    "parentSpanId", ref.parentSpanId(),
                                    "parentService", ref.parentService(),
                                    "parentServiceInstance", ref.parentServiceInstance(),
                                    "parentEndpoint", ref.parentEndpoint(),
                                    "networkAddressUsedAtPeer", ref.networkAddressUsedAtPeer())),
                            List.of(
                                Map.of(
                                    "time", now - 40,
                                    "data", List.of(tag("event", "error"), tag("message", "boom"))))))));
    HttpResponse<String> res =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create(client.config().url() + "/v3/segments"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(segments)))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    assertEquals(200, res.statusCode(), res.body());
  }

  private static Map<String, String> tag(String k, String v) {
    return Map.of("key", k, "value", v);
  }

  private static Map<String, Object> span(
      int id,
      int parent,
      long start,
      long end,
      String op,
      String peer,
      String type,
      int component,
      boolean error,
      List<Map<String, String>> tags,
      List<Map<String, Object>> refs,
      List<Map<String, Object>> logs) {
    Map<String, Object> m = new HashMap<>();
    m.put("spanId", id);
    m.put("parentSpanId", parent);
    m.put("startTime", start);
    m.put("endTime", end);
    m.put("operationName", op);
    m.put("peer", peer);
    m.put("spanType", type);
    m.put("spanLayer", "Http");
    m.put("componentId", component);
    m.put("isError", error);
    m.put("tags", tags);
    if (refs != null) {
      m.put("refs", refs);
    }
    if (logs != null) {
      m.put("logs", logs);
    }
    return m;
  }

  @AfterAll
  void close() {
    if (client != null) {
      client.close();
      client.close();
      assertTrue(client.isClosed());
    }
  }

  @Test
  void healthPingVersionGraphqlTimeInfoCapabilities() {
    assertEquals(new HealthStatus(true, "ok", Map.of()), client.health());
    assertTrue(client.healthAsync().join().healthy());
    assertFalse(client.ping().isNegative());
    assertFalse(client.pingAsync().join().isNegative());
    String version = client.serverVersion();
    assertTrue(version.startsWith("10.4."), version);
    assertEquals(version, client.serverVersionAsync().join());
    assertEquals(version, client.graphql("{version}").field("version").orElseThrow().asText());
    assertEquals(
        version,
        client.query().graphql("query{version}", Map.of()).field("version").orElseThrow().asText());
    assertEquals(
        version,
        client.graphqlAsync("{version}", null).join().field("version").orElseThrow().asText());
    assertThrows(ValidationException.class, () -> client.graphql("{nope}"));
    TimeInfo time = client.query().timeInfo();
    assertFalse(time.timezone().isEmpty());
    assertTrue(Math.abs(time.currentTimestamp() - System.currentTimeMillis()) < 300_000);
    assertEquals(new SkyWalkingCapabilities(true, false), client.query().capabilities());
    assertTrue(client.query().capabilitiesAsync().join().queryTracesV2());
    assertEquals(time.timezone(), client.query().timeInfoAsync().join().timezone());
  }

  @Test
  void tracesZipkinStoreRoundTripsTheOtlpSpans() {
    Trace trace =
        client
            .traces()
            .waitForTrace(otlpTraceId, new WaitForTraceOptions(WAIT, Duration.ofSeconds(2), 2));
    assertEquals(otlpTraceId, trace.traceId());
    Span root = trace.spans().stream().filter(Span::isRoot).findFirst().orElseThrow();
    Span child = trace.spans().stream().filter(s -> !s.isRoot()).findFirst().orElseThrow();
    assertEquals("get /checkout", root.name());
    assertEquals(svcOtlp, root.serviceName());
    assertEquals(run, root.attributes().get("run_id"));
    assertEquals(otlpSpanId, root.spanId());
    assertEquals(Optional.of(root.spanId()), child.parentSpanId());
    assertEquals("v", child.events().get(0).attributes().get("k"));
    assertEquals(2, client.traces().getTrace(otlpTraceId).orElseThrow().spans().size());
    assertEquals(2, client.traces().getTraceAsync(otlpTraceId).join().orElseThrow().spans().size());
    assertTrue(client.traces().getTrace("0000000000000000000000000000beef").isEmpty());
    assertTrue(
        client.traces().withBackend(TraceBackend.ZIPKIN).getTrace(otlpTraceId).isPresent());
  }

  @Test
  void searchTracesFindsTheOtlpTraceByServiceAndTag() {
    List<TraceSummary> hits =
        poll(
            "searchTraces",
            () ->
                client
                    .traces()
                    .searchTraces(
                        TraceSearchQuery.builder()
                            .service(svcOtlp)
                            .tag("run_id", run)
                            .start(Instant.now().minusSeconds(600))
                            .build()),
            h -> h.stream().anyMatch(x -> x.traceId().equals(otlpTraceId)));
    TraceSummary hit =
        hits.stream().filter(x -> x.traceId().equals(otlpTraceId)).findFirst().orElseThrow();
    assertEquals(Optional.of("get /checkout"), hit.rootName());
    assertEquals(2, hit.spanCount());
    assertFalse(
        client
            .traces()
            .searchTracesAsync(TraceSearchQuery.builder().service(svcOtlp).operation("select orders").build())
            .join()
            .isEmpty());
    List<TraceSummary> nativeHits =
        poll(
            "native searchTraces",
            () ->
                client
                    .traces()
                    .withBackend(TraceBackend.NATIVE)
                    .searchTraces(
                        TraceSearchQuery.builder()
                            .service(svcA)
                            .start(Instant.now().minusSeconds(900))
                            .build()),
            h -> h.stream().anyMatch(x -> x.traceId().equals(nativeTraceId)));
    assertFalse(nativeHits.isEmpty());
  }

  @Test
  void tracesListServicesAndOperations() {
    List<String> services =
        poll(
            "traces.listServices",
            () -> client.traces().listServices(),
            s -> s.contains(svcOtlp) && s.contains(svcA));
    assertTrue(services.contains(svcB));
    assertTrue(client.traces().listServicesAsync().join().contains(svcOtlp));
    List<String> ops =
        poll(
            "traces.listOperations",
            () -> client.traces().listOperations(svcOtlp),
            o -> o.contains("get /checkout"));
    assertTrue(ops.contains("select orders"));
    List<String> nativeOps =
        poll(
            "traces.listOperations native",
            () -> client.traces().listOperationsAsync(svcA).join(),
            o -> o.contains("/it/entry"));
    assertFalse(nativeOps.isEmpty());
  }

  @Test
  void tracesNativeTraceSw8LinkedParentAndDependencies() {
    Trace trace =
        client
            .traces()
            .waitForTraceAsync(nativeTraceId, new WaitForTraceOptions(WAIT, Duration.ofSeconds(2), 3))
            .join();
    assertEquals(nativeTraceId, trace.traceId());
    Span b = trace.spans().stream().filter(s -> s.serviceName().equals(svcB)).findFirst().orElseThrow();
    assertEquals(Optional.of(segA + "-1"), b.parentSpanId());
    assertEquals(Span.StatusCode.ERROR, b.status().code());
    assertEquals("error", b.events().get(0).name());
    List<DependencyLink> deps =
        poll(
            "getDependencies",
            () -> client.traces().getDependencies(Instant.now().minusSeconds(900), null),
            d -> d.stream().anyMatch(l -> l.parent().equals(svcA) && l.child().equals(svcB)));
    assertEquals(
        0,
        deps.stream().filter(l -> l.parent().equals(svcA)).findFirst().orElseThrow().callCount());
    assertFalse(
        client.traces().getDependenciesAsync(Instant.now().minusSeconds(900), null).join().isEmpty());
  }

  @Test
  void queryServicesEndpointsInstances() {
    List<SkyWalkingService> services =
        poll(
            "query.listServices",
            () -> client.query().listServices("GENERAL"),
            s -> s.stream().anyMatch(x -> x.name().equals(svcA)));
    assertEquals(
        SkyWalkingIds.serviceId(svcA),
        services.stream().filter(x -> x.name().equals(svcA)).findFirst().orElseThrow().id());
    assertTrue(client.query().listServices().stream().anyMatch(x -> x.name().equals(svcA)));
    assertTrue(
        client.query().listServices("NO_SUCH_LAYER", TimeRange.last(Duration.ofMinutes(1))).isEmpty());
    assertTrue(client.query().listServicesAsync(null, null).join().size() >= 2);
    assertEquals(svcA, client.query().findService(svcA).orElseThrow().name());
    assertTrue(client.query().findService("no-such-" + run).isEmpty());
    assertTrue(client.query().findServiceAsync(svcB).join().isPresent());
    List<SkyWalkingEndpoint> eps =
        poll(
            "listEndpoints",
            () -> client.query().listEndpoints(svcA),
            e -> e.stream().anyMatch(x -> x.name().equals("/it/entry")));
    assertFalse(eps.isEmpty());
    List<SkyWalkingEndpoint> kw =
        poll(
            "listEndpoints keyword",
            () -> client.query().listEndpoints(svcB, "downstream", 5),
            e -> !e.isEmpty());
    assertEquals(List.of("/it/downstream"), kw.stream().map(SkyWalkingEndpoint::name).toList());
    assertEquals(1, client.query().listEndpoints(svcB, "downstream").size());
    assertEquals(1, client.query().listEndpointsAsync(svcB, null, 10).join().size());
    List<SkyWalkingInstance> inst =
        poll(
            "listInstances",
            () -> client.query().listInstances(svcA, TimeRange.since(Instant.now().minusSeconds(900))),
            i -> !i.isEmpty());
    assertEquals("inst-a-" + run, inst.get(0).name());
    assertFalse(client.query().listInstances(svcA).isEmpty());
    assertFalse(client.query().listInstancesAsync(svcA, null).join().isEmpty());
  }

  @Test
  void queryTracesGetTraceTopology() {
    TimeRange window = TimeRange.since(Instant.now().minusSeconds(900));
    QueryTracesResult res =
        poll(
            "queryTraces",
            () ->
                client
                    .query()
                    .queryTraces(
                        QueryTracesOptions.builder().service(svcA).tag("run_id", run).range(window).build()),
            r -> r.traces().stream().anyMatch(t -> t.traceId().equals(nativeTraceId)));
    NativeTrace t =
        res.traces().stream().filter(x -> x.traceId().equals(nativeTraceId)).findFirst().orElseThrow();
    assertEquals(3, t.spans().size());
    QueryTracesResult errors =
        client
            .query()
            .queryTraces(
                QueryTracesOptions.builder().service(svcB).traceState(TraceState.ERROR).range(window).build());
    assertTrue(errors.traces().stream().anyMatch(x -> x.traceId().equals(nativeTraceId)));
    QueryTracesResult none =
        client
            .query()
            .queryTracesAsync(
                QueryTracesOptions.builder()
                    .service(svcA)
                    .minDurationMs(60_000L)
                    .range(window)
                    .paging(Paging.of(1, 5))
                    .build())
            .join();
    assertTrue(none.traces().isEmpty());
    NativeTrace nat = client.query().getTrace(nativeTraceId).orElseThrow();
    NativeSpan bSpan =
        nat.spans().stream().filter(s -> s.serviceCode().equals(svcB)).findFirst().orElseThrow();
    assertEquals(segA, bSpan.refs().get(0).parentSegmentId());
    assertEquals("CROSS_PROCESS", bSpan.refs().get(0).type());
    assertTrue(client.query().getTrace(otlpTraceId).isEmpty());
    assertTrue(client.query().getTraceAsync(nativeTraceId).join().isPresent());
    Topology topo =
        poll(
            "topology",
            () -> client.query().topology(window),
            tp ->
                tp.calls().stream()
                    .anyMatch(
                        c ->
                            c.source().equals(SkyWalkingIds.serviceId(svcA))
                                && c.target().equals(SkyWalkingIds.serviceId(svcB))));
    assertTrue(topo.nodes().stream().anyMatch(n -> n.name().equals(svcB)));
    Topology svcTopo = client.query().topology(window, TopologyOptions.forService(svcA));
    assertTrue(svcTopo.nodes().stream().anyMatch(n -> n.name().equals(svcB)));
    assertFalse(client.query().topology().nodes().isEmpty());
    assertFalse(
        client.query().topologyAsync(window, TopologyOptions.forLayer("GENERAL")).join().nodes().isEmpty());
  }

  @Test
  void queryLogsByServiceTagRelatedTraceKeywordsAndTagKeys() {
    QueryLogsResult byService =
        poll(
            "queryLogs",
            () -> client.query().queryLogs(QueryLogsOptions.builder().service(svcLogs).build()),
            r -> r.logs().size() >= 2);
    assertTrue(byService.logs().stream().allMatch(l -> l.serviceName().equals(svcLogs)));
    QueryLogsResult byTag =
        client
            .query()
            .queryLogs(QueryLogsOptions.builder().tag("run_id", run).order(SortOrder.ASC).build());
    assertEquals(2, byTag.logs().size());
    assertTrue(byTag.logs().get(0).timestamp() <= byTag.logs().get(1).timestamp());
    QueryLogsResult related =
        client.query().queryLogs(QueryLogsOptions.builder().traceId(otlpTraceId).build());
    assertEquals(1, related.logs().size());
    assertTrue(related.logs().get(0).content().contains("payment failed"));
    QueryLogsResult kw =
        client
            .query()
            .queryLogsAsync(
                QueryLogsOptions.builder()
                    .service(svcLogs)
                    .keywords(List.of("payment"))
                    .excludingKeywords(List.of("order"))
                    .build())
            .join();
    assertEquals(
        List.of("payment failed native-log-" + run),
        kw.logs().stream().map(NativeLog::content).toList());
    List<String> keys =
        poll("listLogTagKeys", () -> client.query().listLogTagKeys(), k -> k.contains("run_id"));
    assertTrue(keys.contains("level"));
    assertTrue(client.query().listLogTagKeys(TimeRange.DEFAULT).contains("run_id"));
    assertTrue(client.query().listLogTagKeysAsync(null).join().contains("run_id"));
  }

  @Test
  void logsSearchWaitForLogStreamsLabelsTail() throws Exception {
    LogRecord found =
        client
            .logs()
            .waitForLog(
                "payment native-log-" + run, WaitForLogOptions.of(WAIT, Duration.ofSeconds(2)));
    assertEquals(Severity.ERROR, found.severity());
    assertEquals(Optional.of(otlpTraceId), found.traceId());
    assertEquals(run, found.attributes().get("run_id"));
    assertEquals(svcLogs, found.resource().get("service.name"));
    LogSearchResult byTag =
        client.logs().search("run_id=" + run, SearchOptions.builder().limit(1).build());
    assertEquals(1, byTag.records().size());
    assertTrue(byTag.nextCursor().isPresent());
    LogSearchResult page2 =
        client
            .logs()
            .search(
                "run_id=" + run,
                SearchOptions.builder().limit(1).cursor(byTag.nextCursor().get()).build());
    assertEquals(1, page2.records().size());
    assertNotEquals(byTag.records().get(0).body(), page2.records().get(0).body());
    assertEquals(1, client.logs().search("trace_id=" + otlpTraceId).records().size());
    LogSearchResult byService =
        client
            .logs()
            .searchAsync(
                "service=" + svcLogs + " instance=inst-" + run + " created", SearchOptions.defaults())
            .join();
    assertEquals(
        List.of("order created native-log-" + run),
        byService.records().stream().map(LogRecord::body).toList());
    assertTrue(client.logs().listStreams().contains(svcLogs));
    assertTrue(client.logs().listStreamsAsync().join().contains(svcLogs));
    assertTrue(client.logs().listLabels().contains("run_id"));
    assertTrue(client.logs().listLabelsAsync().join().contains("run_id"));

    List<LogRecord> seen = new CopyOnWriteArrayList<>();
    TailHandle tail =
        client
            .logs()
            .tail(
                "tail-" + run,
                seen::add,
                new TailOptions(Optional.empty(), Duration.ofSeconds(1), null));
    BatchingLogClient writer =
        SkyWalkingClient.createLogClient(
            SkyWalkingConfig.fromEnv(env),
            SkyWalkingLogClientOptions.builder()
                .serviceName(svcLogs)
                .batchOptions(BatchOptions.builder().flushInterval(Duration.ofMillis(100)).build())
                .build());
    try {
      writer.send(LogInput.of("tail-" + run + " live"));
      writer.flush();
      poll("tail", seen::size, n -> n >= 1);
      assertEquals("tail-" + run + " live", seen.get(0).body());
    } finally {
      tail.stop();
      writer.close();
    }
  }

  @Test
  void logExporterDirectAndLogClientByName() {
    SkyWalkingLogExporter exporter = new SkyWalkingLogExporter(client.transport(), svcLogs, false);
    LogRecord r =
        LogRecord.builder()
            .timestamp(Instant.now())
            .severity(Severity.INFO)
            .body("direct export " + run)
            .attribute("exp_id", run)
            .build();
    assertEquals(1, exporter.export(List.of(r), new ExportOptions(false, Duration.ofSeconds(10))).join().accepted());
    exporter.shutdown().join();
    BatchingLogClient byName = client.logClient(svcLogs);
    byName.send(LogInput.of("by name " + run));
    byName.flush();
    assertEquals(1, byName.stats().sent());
    LogRecord got =
        client.logs().waitForLog("\"direct export " + run + "\"", WaitForLogOptions.of(WAIT, Duration.ofSeconds(2)));
    assertEquals(svcLogs, got.resource().get("service.name"));
    assertTrue(
        client.logs().waitForLog("\"by name " + run + "\"", WaitForLogOptions.of(WAIT, Duration.ofSeconds(2)))
            .body()
            .startsWith("by name"));
  }

  @Test
  void standaloneInitTracingAndSw8Propagator() {
    SkyWalkingConfig sw = SkyWalkingConfig.fromEnv(env);
    TracingConfig cfg =
        SkyWalkingTracing.configBuilder(svcOtlp + "-standalone", sw)
            .setGlobal(false)
            .propagators(List.of("sw8"))
            .build();
    assertEquals(sw.otlpEndpoint(), SkyWalkingTracing.withDefaults(cfg, sw).endpoint().orElseThrow());
    String id;
    try (TracingHandle standalone = SkyWalkingTracing.initTracing(cfg, sw)) {
      io.opentelemetry.api.trace.Span s = standalone.tracer("it").spanBuilder("standalone").startSpan();
      id = s.getSpanContext().getTraceId();
      s.end();
      standalone.forceFlush();
    }
    String idEnv;
    try (TracingHandle fromEnv =
        SkyWalkingTracing.initTracing(TracingConfig.builder(svcOtlp + "-env").setGlobal(false).build())) {
      io.opentelemetry.api.trace.Span s = fromEnv.tracer("it").spanBuilder("from-env").startSpan();
      idEnv = s.getSpanContext().getTraceId();
      s.end();
    }
    WaitForTraceOptions w = new WaitForTraceOptions(WAIT, Duration.ofSeconds(2), 1);
    assertEquals("standalone", client.traces().waitForTrace(id, w).spans().get(0).name());
    assertEquals("from-env", client.traces().waitForTrace(idEnv, w).spans().get(0).name());

    Sw8Propagator p = Sw8Propagator.getInstance();
    NativeTrace segments = client.query().getTrace(nativeTraceId).orElseThrow();
    NativeSpan exit =
        segments.spans().stream().filter(s -> s.type().equals("Exit")).findFirst().orElseThrow();
    Sw8Context sw8 =
        new Sw8Context(
            true,
            exit.traceId(),
            exit.segmentId(),
            exit.spanId(),
            exit.serviceCode(),
            exit.serviceInstanceName(),
            "/it/entry",
            exit.peer());
    Map<String, String> carrier = new HashMap<>();
    p.inject(Sw8.withContext(Context.root(), sw8, Map.of("run", run)), carrier, Map::put);
    assertEquals(Sw8.encode(sw8), carrier.get(Sw8.HEADER));
    assertEquals(Sw8.encodeCorrelation(Map.of("run", run)), carrier.get(Sw8.CORRELATION_HEADER));
    assertEquals(Map.of("run", run), Sw8.decodeCorrelation(carrier.get(Sw8.CORRELATION_HEADER)));
    Context extracted = p.extract(Context.root(), carrier, new MapGetter());
    assertEquals(Optional.of(sw8), Sw8.fromContext(extracted));
    assertEquals(segA, Sw8.attributes(sw8).get("sw8.parent.segment_id"));
    assertEquals(sw8, Sw8.decode(sw8.encode()).orElseThrow());
  }

  /** TextMapGetter over a map. */
  private static final class MapGetter implements TextMapGetter<Map<String, String>> {
    @Override
    @SuppressWarnings("deprecation")
    public Iterable<String> keys(Map<String, String> carrier) {
      return new ArrayList<>(carrier.keySet());
    }

    @Override
    public String get(Map<String, String> carrier, String key) {
      return carrier == null ? null : carrier.get(key);
    }
  }
}
