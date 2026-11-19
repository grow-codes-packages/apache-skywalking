package codes.grow.packages.skywalking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import codes.grow.packages.skywalking.core.MockServer;
import codes.grow.packages.skywalking.core.RetryPolicy;
import codes.grow.packages.skywalking.core.UnsupportedException;
import codes.grow.packages.skywalking.core.ValidationException;
import codes.grow.packages.skywalking.logging.SortOrder;
import codes.grow.packages.skywalking.tracing.query.DependencyLink;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QueryClientTest {
  static final Instant NOW = Instant.parse("2026-11-18T21:15:30Z");
  static final String SVC_A = "sw-native-a-1fddf66a";
  static final String NATIVE_ID = "1fddf66a1c64db2c1345419627769f1f.1.1795036241775";

  private MockServer server;
  private SkyWalkingClient client;

  @BeforeEach
  void start() throws Exception {
    server = MockServer.start();
    client =
        SkyWalkingClient.create(
            SkyWalkingConfig.builder()
                .url(server.url())
                .zipkinUrl(server.url() + "/zipkin")
                .retry(RetryPolicy.builder().initialBackoff(Duration.ofMillis(1)).build())
                .build(),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @AfterEach
  void stop() {
    client.close();
    server.close();
  }

  private JsonNode request(int i) {
    return Fixtures.request(server.requests().get(i));
  }

  private JsonNode vars(int i) {
    return request(i).get("variables");
  }

  @Test
  void timeInfoCachesTheTimezoneForDurations() {
    server.enqueue(
        200, "{\"data\":{\"getTimeInfo\":{\"timezone\":\"+0530\",\"currentTimestamp\":5}}}");
    Fixtures.enqueue(server, "listInstances.ok");
    Fixtures.enqueue(server, "listInstances.ok");
    SkyWalkingQueryClient q = client.query();
    TimeInfo t = q.timeInfo();
    assertThat(t.timezone()).isEqualTo("+0530");
    assertThat(t.currentTime()).isEqualTo(Instant.ofEpochMilli(5));
    List<SkyWalkingInstance> inst = q.listInstances(SVC_A);
    assertThat(inst)
        .singleElement()
        .satisfies(
            i -> {
              assertThat(i.name()).isEqualTo("inst-a-1fddf66a");
              assertThat(i.language()).isEqualTo("GO");
              assertThat(i.instanceUUID()).isNotEmpty();
              assertThat(i.id()).isEqualTo(SkyWalkingIds.instanceId(SVC_A, "inst-a-1fddf66a"));
              assertThat(i.attributes())
                  .containsExactly(new SkyWalkingInstance.Attribute("ipv4s", ""));
            });
    JsonNode d = vars(1).get("d");
    assertThat(d.get("start").asText()).isEqualTo("2026-11-19 0145");
    assertThat(d.get("end").asText()).isEqualTo("2026-11-19 0245");
    assertThat(d.get("step").asText()).isEqualTo("MINUTE");
    assertThat(vars(1).get("s").asText()).isEqualTo(SkyWalkingIds.serviceId(SVC_A));
    q.listInstances(
        SVC_A,
        TimeRange.of(Instant.parse("2026-11-18T20:00:00Z"), Instant.parse("2026-11-18T21:00:05Z"))
            .withStep(Step.SECOND));
    assertThat(vars(2).get("d").get("start").asText()).isEqualTo("2026-11-19 013000");
    assertThat(vars(2).get("d").get("step").asText()).isEqualTo("SECOND");
    assertThat(server.requests()).hasSize(3);
  }

  @Test
  void timeInfoFailureIsNotCached() {
    server.enqueue(400, "{\"errors\":[{\"message\":\"x\"}]}");
    Fixtures.enqueue(server, "ping.ok");
    Fixtures.enqueue(server, "listInstances.ok");
    assertThatThrownBy(() -> client.query().listInstances(SVC_A))
        .isInstanceOf(ValidationException.class);
    assertThat(client.query().listInstances(SVC_A)).hasSize(1);
    server.enqueue(200, "{\"data\":{\"getTimeInfo\":{}}}");
    assertThat(client.query().timeInfo().timezone()).isEqualTo("+0000");
  }

  @Test
  void listServicesAndFindService() {
    Fixtures.enqueue(server, "listServices.all");
    Fixtures.enqueue(server, "listServices.layer-general");
    Fixtures.enqueue(server, "listServices.layer-unknown");
    Fixtures.enqueue(server, "findService.ok");
    Fixtures.enqueue(server, "findService.missing");
    SkyWalkingQueryClient q = client.query();
    List<SkyWalkingService> all = q.listServices();
    assertThat(all).extracting(SkyWalkingService::name).contains(SVC_A);
    SkyWalkingService a = all.stream().filter(s -> s.name().equals(SVC_A)).findFirst().get();
    assertThat(a.id()).isEqualTo(SkyWalkingIds.serviceId(SVC_A));
    assertThat(a.normal()).isTrue();
    assertThat(a.layers()).contains("GENERAL");
    assertThat(vars(0).get("l").isNull()).isTrue();
    assertThat(q.listServices("GENERAL")).isNotEmpty();
    assertThat(vars(1).get("l").asText()).isEqualTo("GENERAL");
    assertThat(q.listServices("NOPE", TimeRange.last(Duration.ofMinutes(1)))).isEmpty();
    assertThat(q.findService(SVC_A)).hasValueSatisfying(s -> assertThat(s.name()).isEqualTo(SVC_A));
    assertThat(vars(3).get("n").asText()).isEqualTo(SVC_A);
    assertThat(q.findService("missing")).isEmpty();
    assertThatThrownBy(() -> q.findService(" ")).isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> q.findServiceAsync(null).join())
        .hasCauseInstanceOf(ValidationException.class);
  }

  @Test
  void listEndpoints() {
    Fixtures.enqueue(server, "listEndpoints.ok");
    Fixtures.enqueue(server, "listEndpoints.keyword", Map.of("findEndpointByName", "findEndpoint"));
    server.enqueue(200, "{\"data\":{\"findEndpoint\":null}}");
    SkyWalkingQueryClient q = client.query();
    List<SkyWalkingEndpoint> eps = q.listEndpoints(SVC_A);
    assertThat(eps).extracting(SkyWalkingEndpoint::name).contains("/native/entry");
    assertThat(eps.get(0).id()).startsWith(SkyWalkingIds.serviceId(SVC_A) + "_");
    assertThat(vars(0).get("k").isNull()).isTrue();
    assertThat(vars(0).get("l").asInt()).isEqualTo(100);
    assertThat(q.listEndpoints("sw-native-b-1fddf66a", "downstream", 5))
        .extracting(SkyWalkingEndpoint::name)
        .containsExactly("/native/downstream");
    assertThat(vars(1).get("k").asText()).isEqualTo("downstream");
    assertThat(vars(1).get("l").asInt()).isEqualTo(5);
    assertThat(request(1).get("query").asText()).doesNotContain("duration");
    assertThat(q.listEndpoints(SVC_A, "")).isEmpty();
    assertThatThrownBy(() -> q.listEndpoints(SVC_A, null, 0))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> q.listEndpoints("", null, 1)).isInstanceOf(ValidationException.class);
  }

  @Test
  void queryTracesBuildsTheCondition() {
    Fixtures.enqueue(server, "ping.ok");
    Fixtures.enqueue(server, "queryTraces.by-service");
    Fixtures.enqueue(server, "queryTraces.by-trace-id");
    Fixtures.enqueue(server, "queryTraces.min-duration-no-match");
    server.enqueue(200, "{\"data\":{\"queryTraces\":{\"traces\":[{\"spans\":[]}]}}}");
    SkyWalkingQueryClient q = client.query();
    QueryTracesResult res =
        q.queryTraces(
            QueryTracesOptions.builder()
                .service(SVC_A)
                .instance("inst-a")
                .endpoint("/native/entry")
                .traceState(TraceState.ERROR)
                .order(TraceOrder.BY_DURATION)
                .minDurationMs(5L)
                .maxDurationMs(500L)
                .tag("run_id", "1fddf66a")
                .tags(Map.of("http.method", "GET"))
                .paging(Paging.of(2, 10))
                .build());
    assertThat(res.traces())
        .singleElement()
        .satisfies(
            t -> {
              assertThat(t.traceId()).isEqualTo(NATIVE_ID);
              assertThat(t.spans()).hasSize(3);
              assertThat(t.toTrace()).isPresent();
            });
    assertThat(res.retrievedTimeRange())
        .contains(new QueryTracesResult.RetrievedTimeRange(1795034400000L, 1795036560000L));
    JsonNode c = vars(1).get("c");
    assertThat(c.get("serviceId").asText()).isEqualTo(SkyWalkingIds.serviceId(SVC_A));
    assertThat(c.get("serviceInstanceId").asText())
        .isEqualTo(SkyWalkingIds.instanceId(SVC_A, "inst-a"));
    assertThat(c.get("endpointId").asText())
        .isEqualTo(SkyWalkingIds.endpointId(SVC_A, "/native/entry"));
    assertThat(c.get("traceState").asText()).isEqualTo("ERROR");
    assertThat(c.get("queryOrder").asText()).isEqualTo("BY_DURATION");
    assertThat(c.get("minTraceDuration").asLong()).isEqualTo(5);
    assertThat(c.get("maxTraceDuration").asLong()).isEqualTo(500);
    assertThat(c.get("tags").size()).isEqualTo(2);
    assertThat(c.get("paging").get("pageNum").asInt()).isEqualTo(2);
    assertThat(c.get("queryDuration").get("end").asText()).isEqualTo("2026-11-18 2115");
    assertThat(request(1).get("query").asText()).contains("retrievedTimeRange");
    // a trace id alone sends no window
    assertThat(q.queryTraces(QueryTracesOptions.builder().traceId(NATIVE_ID).build()).traces())
        .hasSize(1);
    assertThat(vars(2).get("c").has("queryDuration")).isFalse();
    assertThat(vars(2).get("c").get("traceId").asText()).isEqualTo(NATIVE_ID);
    assertThat(vars(2).get("c").get("paging").get("pageSize").asInt()).isEqualTo(20);
    QueryTracesResult none =
        q.queryTracesAsync(
                QueryTracesOptions.builder()
                    .traceId(NATIVE_ID)
                    .range(TimeRange.DEFAULT)
                    .traceState(null)
                    .order(null)
                    .paging(null)
                    .build())
            .join();
    assertThat(none.traces()).isEmpty();
    assertThat(vars(3).get("c").has("queryDuration")).isTrue();
    assertThat(q.queryTraces(null).retrievedTimeRange()).isEmpty();
  }

  @Test
  void queryTracesValidation() {
    SkyWalkingQueryClient q = client.query();
    assertThatThrownBy(() -> q.queryTraces(QueryTracesOptions.builder().instance("i").build()))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("need service");
    assertThatThrownBy(() -> q.queryTraces(QueryTracesOptions.builder().endpoint("e").build()))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> q.queryTraces(QueryTracesOptions.builder().minDurationMs(-1L).build()))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> q.queryTraces(QueryTracesOptions.builder().maxDurationMs(-1L).build()))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(
            () -> q.queryTraces(QueryTracesOptions.builder().paging(Paging.of(0, 1)).build()))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(
            () -> q.queryTraces(QueryTracesOptions.builder().paging(Paging.of(1, 10_001)).build()))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> q.queryTraces(QueryTracesOptions.builder().traceId(" ").build()))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(
            () ->
                q.queryTraces(
                    QueryTracesOptions.builder()
                        .range(TimeRange.of(NOW, NOW.minusSeconds(1)))
                        .build()))
        .isInstanceOf(ValidationException.class);
    assertThat(server.requests()).isEmpty();
    Fixtures.enqueue(server, "queryBasicTraces.unsupported");
    assertThatThrownBy(() -> q.graphql("{queryBasicTraces{traces{key}}}").throwIfErrors("q"))
        .isInstanceOf(UnsupportedException.class);
    QueryTracesOptions o = QueryTracesOptions.defaults();
    assertThat(o.service()).isEmpty();
    assertThat(o.instance()).isEmpty();
    assertThat(o.endpoint()).isEmpty();
    assertThat(o.minDurationMs()).isEmpty();
    assertThat(o.maxDurationMs()).isEmpty();
    assertThat(o.range()).isEmpty();
  }

  @Test
  void getTraceReturnsNativeSpans() {
    Fixtures.enqueue(server, "getTrace.native");
    Fixtures.enqueue(server, "getTrace.native-missing");
    SkyWalkingQueryClient q = client.query();
    NativeTrace t = q.getTrace(" " + NATIVE_ID + " ").orElseThrow();
    assertThat(vars(0).get("id").asText()).isEqualTo(NATIVE_ID);
    assertThat(t.traceId()).isEqualTo(NATIVE_ID);
    NativeSpan b = t.spans().stream().filter(s -> !s.refs().isEmpty()).findFirst().orElseThrow();
    assertThat(b.refs().get(0).type()).isEqualTo("CROSS_PROCESS");
    assertThat(b.refs().get(0).parentSpanId()).isEqualTo(1);
    assertThat(b.isError()).isTrue();
    assertThat(q.getTrace("0".repeat(32))).isEmpty();
    assertThatThrownBy(() -> q.getTrace(null)).isInstanceOf(ValidationException.class);
  }

  @Test
  void queryLogsBuildsTheCondition() {
    Fixtures.enqueue(server, "ping.ok");
    Fixtures.enqueue(server, "queryLogs.by-service");
    Fixtures.enqueue(server, "queryLogs.related-trace");
    Fixtures.enqueue(server, "queryLogs.order-asc");
    SkyWalkingQueryClient q = client.query();
    QueryLogsResult res =
        q.queryLogs(
            QueryLogsOptions.builder()
                .service("sw-otlp-1fddf66a")
                .instance("inst-1fddf66a")
                .endpoint("/x")
                .tag("run_id", "1fddf66a")
                .build());
    assertThat(res.logs()).hasSize(2);
    assertThat(res.errorReason()).isEmpty();
    NativeLog first = res.logs().get(0);
    assertThat(first.endpointName()).isEmpty();
    assertThat(first.traceId()).isEmpty();
    assertThat(first.tag("trace_id")).contains("055ffba8ecc69a64950538fda698a308");
    assertThat(first.tag("nope")).isEmpty();
    assertThat(first.contentType()).isEqualTo("TEXT");
    JsonNode c = vars(1).get("c");
    assertThat(c.get("queryOrder").asText()).isEqualTo("DES");
    assertThat(c.get("serviceInstanceId").asText())
        .isEqualTo(SkyWalkingIds.instanceId("sw-otlp-1fddf66a", "inst-1fddf66a"));
    assertThat(c.get("endpointId").asText()).isNotEmpty();
    assertThat(c.get("queryDuration")).isNotNull();
    assertThat(c.get("tags").get(0).get("key").asText()).isEqualTo("run_id");
    assertThat(c.has("keywordsOfContent")).isFalse();
    q.queryLogs(QueryLogsOptions.builder().traceId(NATIVE_ID).segmentId("seg").spanId(1).build());
    JsonNode rt = vars(2).get("c").get("relatedTrace");
    assertThat(rt.get("traceId").asText()).isEqualTo(NATIVE_ID);
    assertThat(rt.get("segmentId").asText()).isEqualTo("seg");
    assertThat(rt.get("spanId").asInt()).isEqualTo(1);
    assertThat(vars(2).get("c").has("queryDuration")).isFalse();
    q.queryLogs(
        QueryLogsOptions.builder()
            .order(SortOrder.ASC)
            .range(TimeRange.of(NOW.minusSeconds(300), NOW))
            .traceId("t")
            .build()
            .toBuilder()
            .build());
    assertThat(vars(3).get("c").get("queryOrder").asText()).isEqualTo("ASC");
    assertThat(vars(3).get("c").has("queryDuration")).isTrue();
    assertThatThrownBy(() -> q.queryLogs(QueryLogsOptions.builder().segmentId("s").build()))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> q.queryLogs(QueryLogsOptions.builder().spanId(1).build()))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> q.queryLogs(QueryLogsOptions.builder().instance("i").build()))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> q.queryLogs(QueryLogsOptions.builder().traceId("").build()))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void queryLogsKeywordsAreFilteredClientSideOnBanyanDb() {
    Fixtures.enqueue(server, "capabilities.ok");
    Fixtures.enqueue(server, "ping.ok");
    server.enqueue(
        200,
        "{\"data\":{\"queryLogs\":{\"errorReason\":\"partial\",\"logs\":["
            + "{\"content\":\"Payment failed\"},{\"content\":\"order created payment\"},"
            + "{\"content\":\"other\"}]}}}");
    QueryLogsResult res =
        client
            .query()
            .queryLogs(
                QueryLogsOptions.builder()
                    .keywords(List.of("payment"))
                    .excludingKeywords(List.of("ORDER"))
                    .build());
    assertThat(res.logs()).extracting(NativeLog::content).containsExactly("Payment failed");
    assertThat(res.errorReason()).contains("partial");
    assertThat(vars(2).get("c").has("keywordsOfContent")).isFalse();
    assertThat(client.query().capabilities()).isEqualTo(new SkyWalkingCapabilities(true, false));
    assertThat(server.requests()).hasSize(3);
  }

  @Test
  void queryLogsKeywordsGoToTheServerWhenSupported() {
    server.enqueue(
        200, "{\"data\":{\"hasQueryTracesV2Support\":true,\"supportQueryLogsByKeywords\":true}}");
    Fixtures.enqueue(server, "ping.ok");
    server.enqueue(200, "{\"data\":{\"queryLogs\":{\"logs\":[{\"content\":\"anything\"}]}}}");
    Fixtures.enqueue(server, "ping.ok");
    server.enqueue(200, "{\"data\":{\"queryLogs\":{\"logs\":[]}}}");
    QueryLogsResult res =
        client
            .query()
            .queryLogs(
                QueryLogsOptions.builder()
                    .keywords(List.of("a"))
                    .excludingKeywords(List.of("b"))
                    .build());
    assertThat(res.logs()).hasSize(1);
    assertThat(vars(2).get("c").get("keywordsOfContent").get(0).asText()).isEqualTo("a");
    assertThat(vars(2).get("c").get("excludingKeywordsOfContent").get(0).asText()).isEqualTo("b");
    client.query().queryLogs(QueryLogsOptions.builder().excludingKeywords(List.of("b")).build());
    assertThat(vars(3).get("c").has("keywordsOfContent")).isFalse();
  }

  @Test
  void capabilitiesFailureIsNotCached() {
    server.enqueue(400, "{\"errors\":[{\"message\":\"x\"}]}");
    Fixtures.enqueue(server, "capabilities.ok");
    assertThatThrownBy(() -> client.query().capabilities()).isInstanceOf(ValidationException.class);
    assertThat(client.query().capabilitiesAsync().join().queryTracesV2()).isTrue();
    assertThat(client.query().capabilities().logsByKeywords()).isFalse();
    assertThat(server.requests()).hasSize(2);
  }

  @Test
  void listLogTagKeysSorted() {
    Fixtures.enqueue(server, "ping.ok");
    Fixtures.enqueue(server, "queryLogTagAutocompleteKeys.ok");
    assertThat(client.query().listLogTagKeys())
        .containsExactly("level", "run_id", "span_id", "trace_id");
    assertThat(vars(1).get("d").get("step").asText()).isEqualTo("MINUTE");
  }

  @Test
  void topologyGlobalServiceAndLayer() {
    Fixtures.enqueue(server, "ping.ok");
    Fixtures.enqueue(server, "topology.global");
    Fixtures.enqueue(server, "topology.service");
    Fixtures.enqueue(server, "topology.global");
    Fixtures.enqueue(server, "graphql.fetch-error");
    SkyWalkingQueryClient q = client.query();
    Topology t = q.topology();
    assertThat(t.nodes()).extracting(TopologyNode::name).contains("User", SVC_A);
    TopologyNode user = t.nodes().get(0);
    assertThat(user.isReal()).isFalse();
    assertThat(user.type()).isEqualTo("USER");
    assertThat(user.layers()).containsExactly("UNDEFINED");
    assertThat(t.calls().get(0).detectPoints()).containsExactly("CLIENT", "SERVER");
    assertThat(t.calls().get(0).sourceComponents()).containsExactly("HttpClient");
    assertThat(t.calls().get(0).targetComponents()).containsExactly("Tomcat");
    assertThat(t.calls().get(0).id()).isNotEmpty();
    assertThat(t.nodeName(SkyWalkingIds.serviceId(SVC_A))).isEqualTo(SVC_A);
    assertThat(t.nodeName("nope")).isEmpty();
    List<DependencyLink> deps = t.toDependencies();
    assertThat(deps)
        .containsExactly(
            new DependencyLink("User", SVC_A, 0),
            new DependencyLink(SVC_A, "sw-native-b-1fddf66a", 0));
    assertThat(vars(1).get("l").isNull()).isTrue();
    Topology s = q.topology(null, TopologyOptions.forService(SVC_A));
    assertThat(s.nodes()).isNotEmpty();
    assertThat(vars(2).get("s").asText()).isEqualTo(SkyWalkingIds.serviceId(SVC_A));
    q.topologyAsync(TimeRange.DEFAULT, TopologyOptions.forLayer("GENERAL")).join();
    assertThat(vars(3).get("l").asText()).isEqualTo("GENERAL");
    assertThatThrownBy(() -> q.topology(TimeRange.DEFAULT))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("query.topology");
    assertThatThrownBy(() -> q.topology(null, TopologyOptions.forService(" ")))
        .isInstanceOf(ValidationException.class);
    Topology unknown =
        new Topology(List.of(), List.of(new TopologyCall("x", "a", "b", null, null, null)));
    assertThat(unknown.toDependencies()).containsExactly(new DependencyLink("a", "b", 0));
    assertThat(q.topologyAsync(null, null)).isNotNull();
  }

  @Test
  void durationAndIdHelpers() {
    assertThat(GraphQLDuration.parseTimezone("-05:30")).isEqualTo(-330);
    assertThat(GraphQLDuration.parseTimezone("+0800")).isEqualTo(480);
    assertThat(GraphQLDuration.parseTimezone("UTC")).isZero();
    assertThat(GraphQLDuration.parseTimezone(null)).isZero();
    Instant t = Instant.parse("2026-01-02T03:04:05Z");
    assertThat(GraphQLDuration.format(t, Step.DAY, 0)).isEqualTo("2026-01-02");
    assertThat(GraphQLDuration.format(t, Step.HOUR, 0)).isEqualTo("2026-01-02 03");
    assertThat(GraphQLDuration.format(t, Step.MINUTE, -240)).isEqualTo("2026-01-01 2304");
    assertThat(GraphQLDuration.format(t, Step.SECOND, 0)).isEqualTo("2026-01-02 030405");
    assertThat(SkyWalkingIds.serviceId("svc")).isEqualTo("c3Zj.1");
    assertThat(SkyWalkingIds.serviceId("svc", false)).isEqualTo("c3Zj.0");
    assertThat(SkyWalkingIds.endpointId("svc", "/a")).isEqualTo("c3Zj.1_L2E=");
    assertThat(TimeRange.DEFAULT.isDefault()).isTrue();
    assertThat(TimeRange.since(t).isDefault()).isFalse();
    assertThat(new TimeRange(null, null, null)).isEqualTo(TimeRange.DEFAULT);
    GraphQLDuration.Resolved r = TimeRange.DEFAULT.resolve(t, "op");
    assertThat(r.start()).isEqualTo(t.minus(TimeRange.DEFAULT_WINDOW));
    assertThat(r.step()).isEqualTo(Step.MINUTE);
    assertThat(new KeyValue(null, null)).isEqualTo(new KeyValue("", ""));
    assertThat(new GraphQLErrorInfo(null, null, null).message()).isEmpty();
    assertThat(GraphQLErrorInfo.listFrom(null)).isEmpty();
    assertThat(
            GraphQLErrorInfo.listFrom(
                codes.grow.packages.skywalking.core.Json.tree(
                    "{\"errors\":[1,{\"message\":5,\"path\":[\"a\",0],\"extensions\":{}}]}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8))))
        .singleElement()
        .satisfies(e -> assertThat(e.path()).containsExactly("a", 0));
    assertThat(GraphQLResponse.fromBody(null).data()).isEmpty();
    assertThat(new GraphQLResponse(null, null).errors()).isEmpty();
    assertThat(
            GraphQLResponse.toException("op", List.of(new GraphQLErrorInfo("", null, null)), s -> s)
                .getMessage())
        .isEqualTo("op: GraphQL error");
    assertThat(
            KeyValue.listFrom(
                codes.grow.packages.skywalking.core.Json.tree(
                    "[{\"key\":\"a\",\"value\":1},{\"key\":\"b\",\"value\":null},{\"key\":\"c\"}]"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8))))
        .containsExactly(new KeyValue("a", "1"), new KeyValue("b", ""), new KeyValue("c", ""));
  }
}
