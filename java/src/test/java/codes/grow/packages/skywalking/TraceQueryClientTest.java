package codes.grow.packages.skywalking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import codes.grow.packages.skywalking.core.MockServer;
import codes.grow.packages.skywalking.core.RetryPolicy;
import codes.grow.packages.skywalking.core.TimeoutException;
import codes.grow.packages.skywalking.core.UnsupportedException;
import codes.grow.packages.skywalking.core.ValidationException;
import codes.grow.packages.skywalking.tracing.query.DependencyLink;
import codes.grow.packages.skywalking.tracing.query.Span;
import codes.grow.packages.skywalking.tracing.query.Trace;
import codes.grow.packages.skywalking.tracing.query.TraceSearchQuery;
import codes.grow.packages.skywalking.tracing.query.TraceSummary;
import codes.grow.packages.skywalking.tracing.query.WaitForTraceOptions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TraceQueryClientTest {
  static final Instant NOW = QueryClientTest.NOW;
  static final String OTLP_ID = "055ffba8ecc69a64950538fda698a308";
  static final String NATIVE_ID = QueryClientTest.NATIVE_ID;
  static final String SEG_A = "1fddf66a0691f3d5361838903180a743.1.1795036241775";

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

  private String uri(int i) {
    return server.requests().get(i).uri();
  }

  @Test
  void zipkinTraceIsConvertedToTheSpecModel() {
    Fixtures.enqueue(server, "getTrace.zipkin");
    Trace t = client.traces().getTrace(OTLP_ID.toUpperCase()).orElseThrow();
    assertThat(uri(0)).isEqualTo("/zipkin/api/v2/trace/" + OTLP_ID);
    assertThat(t.traceId()).isEqualTo(OTLP_ID);
    assertThat(t.spans()).hasSize(2);
    Span root = t.root().orElseThrow();
    assertThat(root.name()).isEqualTo("get /checkout");
    assertThat(root.kind()).isEqualTo(Span.Kind.SERVER);
    assertThat(root.serviceName()).isEqualTo("sw-otlp-1fddf66a");
    assertThat(root.status().code()).isEqualTo(Span.StatusCode.OK);
    assertThat(root.attributes())
        .containsEntry("retries", "2")
        .containsEntry("run_id", "1fddf66a")
        .doesNotContainKey("otel.status_code")
        .doesNotContainKey("w3c.tracestate");
    assertThat(root.durationMicros()).isEqualTo(50000);
    assertThat(root.endMicros()).isEqualTo(root.startMicros() + 50000);
    assertThat(root.events())
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.name()).isEqualTo("cache.miss");
              assertThat(e.attributes()).containsEntry("k", "v");
            });
    Span child = t.spans().stream().filter(s -> !s.isRoot()).findFirst().orElseThrow();
    assertThat(child.parentSpanId()).contains(root.spanId());
    assertThat(child.kind()).isEqualTo(Span.Kind.CLIENT);
    assertThat(child.status().code()).isEqualTo(Span.StatusCode.ERROR);
    assertThat(child.status().message()).contains("boom");
    assertThat(child.attributes()).doesNotContainKey("error");
  }

  @Test
  void autoFallsBackToTheNativeStore() {
    Fixtures.enqueue(server, "getTrace.zipkin-404");
    Fixtures.enqueue(server, "getTrace.native-otlp-id");
    Fixtures.enqueue(server, "getTrace.native");
    assertThat(client.traces().getTrace("0000000000000000000000000000BEEF")).isEmpty();
    assertThat(uri(0)).isEqualTo("/zipkin/api/v2/trace/0000000000000000000000000000beef");
    assertThat(Fixtures.request(server.requests().get(1)).get("variables").get("id").asText())
        .isEqualTo("0000000000000000000000000000beef");
    Trace t = client.traces().getTraceAsync(NATIVE_ID).join().orElseThrow();
    assertThat(uri(2)).isEqualTo("/graphql");
    assertThat(t.traceId()).isEqualTo(NATIVE_ID);
    assertThat(t.spans()).hasSize(3);
    Span b =
        t.spans().stream()
            .filter(s -> s.serviceName().equals("sw-native-b-1fddf66a"))
            .findFirst()
            .orElseThrow();
    assertThat(b.parentSpanId()).contains(SEG_A + "-1");
    assertThat(b.status().code()).isEqualTo(Span.StatusCode.ERROR);
    assertThat(b.kind()).isEqualTo(Span.Kind.SERVER);
    assertThat(b.spanId()).endsWith("-0");
    Span exit =
        t.spans().stream().filter(s -> s.kind() == Span.Kind.CLIENT).findFirst().orElseThrow();
    assertThat(exit.parentSpanId()).contains(SEG_A + "-0");
    assertThat(exit.attributes())
        .containsEntry("sw.peer", "sw-native-b-1fddf66a:8080")
        .containsEntry("sw.component", "HttpClient")
        .containsEntry("sw.layer", "Http")
        .containsEntry("sw.service_instance", "inst-a-1fddf66a");
    assertThat(t.root().orElseThrow().name()).isEqualTo("/native/entry");
  }

  @Test
  void backendsSelectTheStore() {
    SkyWalkingTraceQueryClient zipkin = client.traces().withBackend(TraceBackend.ZIPKIN);
    SkyWalkingTraceQueryClient nat = client.traces().withBackend(TraceBackend.NATIVE);
    assertThat(zipkin.backend()).isEqualTo(TraceBackend.ZIPKIN);
    assertThatThrownBy(() -> zipkin.getTrace(NATIVE_ID))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("16 or 32 hex");
    assertThatThrownBy(() -> zipkin.getTrace("0".repeat(32)))
        .isInstanceOf(ValidationException.class);
    Fixtures.enqueue(server, "getTrace.zipkin-404");
    assertThat(zipkin.getTrace("00f067aa0ba902b7")).isEmpty();
    assertThat(uri(0)).isEqualTo("/zipkin/api/v2/trace/00f067aa0ba902b7");
    Fixtures.enqueue(server, "getTrace.zipkin-400");
    assertThatThrownBy(() -> client.query().graphql("{x}")).isInstanceOf(ValidationException.class);
    Fixtures.enqueue(server, "getTrace.native-missing");
    assertThat(nat.getTrace(OTLP_ID)).isEmpty();
    assertThat(uri(2)).isEqualTo("/graphql");
    assertThatThrownBy(() -> client.traces().getTrace(" ")).isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> client.traces().withBackend(null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void zipkinBadRequestBecomesAValidationError() {
    server.enqueue(400, "id should be lower-hex encoded with no prefix", "Content-Type", "text/*");
    assertThatThrownBy(() -> client.traces().listServices())
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("lower-hex")
        .hasMessageContaining("HTTP 400");
  }

  @Test
  void searchTracesOnZipkin() {
    Fixtures.enqueue(server, "searchTraces.by-tag");
    List<TraceSummary> hits =
        client
            .traces()
            .searchTraces(
                TraceSearchQuery.builder()
                    .service("sw-otlp-1fddf66a")
                    .operation("GET /Checkout")
                    .tag("run_id", "1fddf66a")
                    .tag("http.method", "GET")
                    .minDurationMicros(1000L)
                    .maxDurationMicros(90_000L)
                    .start(NOW.minusSeconds(600))
                    .limit(5)
                    .build());
    assertThat(hits).isNotEmpty();
    TraceSummary hit =
        hits.stream().filter(h -> h.traceId().equals(OTLP_ID)).findFirst().orElseThrow();
    assertThat(hit.rootName()).contains("get /checkout");
    assertThat(hit.rootServiceName()).contains("sw-otlp-1fddf66a");
    assertThat(hit.spanCount()).isEqualTo(2);
    String u = uri(0);
    assertThat(u).startsWith("/zipkin/api/v2/traces?");
    assertThat(u)
        .contains("serviceName=sw-otlp-1fddf66a")
        .contains("spanName=get%20%2Fcheckout")
        .contains("annotationQuery=run_id%3D1fddf66a%20and%20http.method%3DGET")
        .contains("minDuration=1000")
        .contains("maxDuration=90000")
        .contains("endTs=" + NOW.toEpochMilli())
        .contains("lookback=600000")
        .contains("limit=5");
    server.enqueue(200, "[[], [{\"bad\":1}]]");
    assertThat(client.traces().searchTracesAsync(null).join()).isEmpty();
    assertThat(uri(1))
        .doesNotContain("lookback")
        .doesNotContain("serviceName")
        .contains("limit=20");
    server.enqueue(200, "{}");
    assertThat(client.traces().searchTraces(TraceSearchQuery.forService("x"))).isEmpty();
    assertThatThrownBy(
            () ->
                client
                    .traces()
                    .searchTraces(TraceSearchQuery.builder().tag("a", "x and y").build()))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(
            () ->
                client
                    .traces()
                    .searchTraces(TraceSearchQuery.builder().start(NOW.plusSeconds(60)).build()))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void searchTracesOnTheNativeStore() {
    SkyWalkingTraceQueryClient nat = client.traces().withBackend(TraceBackend.NATIVE);
    Fixtures.enqueue(server, "ping.ok");
    Fixtures.enqueue(server, "queryTraces.by-service");
    List<TraceSummary> hits =
        nat.searchTraces(
            TraceSearchQuery.builder()
                .service("sw-native-a-1fddf66a")
                .operation("/native/entry")
                .tag("run_id", "1fddf66a")
                .minDurationMicros(1500L)
                .maxDurationMicros(2500L)
                .limit(3)
                .build());
    assertThat(hits)
        .singleElement()
        .satisfies(
            h -> {
              assertThat(h.traceId()).isEqualTo(NATIVE_ID);
              assertThat(h.rootName()).contains("/native/entry");
              assertThat(h.spanCount()).isEqualTo(3);
              assertThat(h.durationMicros()).isEqualTo(100_000);
            });
    var c = Fixtures.request(server.requests().get(1)).get("variables").get("c");
    assertThat(c.get("minTraceDuration").asLong()).isEqualTo(1);
    assertThat(c.get("maxTraceDuration").asLong()).isEqualTo(3);
    assertThat(c.get("paging").get("pageSize").asInt()).isEqualTo(3);
    assertThat(c.get("endpointId").asText())
        .isEqualTo(SkyWalkingIds.endpointId("sw-native-a-1fddf66a", "/native/entry"));
    assertThat(c.get("queryDuration").get("start").asText()).isEqualTo("2026-11-18 2015");
    assertThatThrownBy(() -> nat.searchTraces(TraceSearchQuery.builder().operation("/x").build()))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("needs service");
  }

  @Test
  void listServicesAndOperationsPerBackend() {
    Fixtures.enqueueByPath(server, "listServices.zipkin", "listServices.all", 2);
    List<String> all = client.traces().listServices();
    assertThat(all).contains("sw-otlp-traceonly-1fddf66a", "sw-native-a-1fddf66a");
    assertThat(all).isSorted().doesNotHaveDuplicates();
    Fixtures.enqueue(server, "listServices.zipkin");
    assertThat(client.traces().withBackend(TraceBackend.ZIPKIN).listServices())
        .containsExactly("sw-otlp-1fddf66a", "sw-otlp-traceonly-1fddf66a");
    server.enqueue(
        200,
        "{\"data\":{\"listServices\":[{\"name\":\"b\",\"normal\":true},{\"name\":\"v\",\"normal\":false},{\"name\":\"\"}]}}");
    assertThat(client.traces().withBackend(TraceBackend.NATIVE).listServicesAsync().join())
        .containsExactly("b");
    Fixtures.enqueueByPath(server, "listOperations.zipkin", "listEndpoints.ok", 2);
    List<String> ops = client.traces().listOperations("sw-native-a-1fddf66a");
    assertThat(ops).contains("get /checkout", "/native/entry");
    assertThat(server.requests())
        .extracting(MockServer.Recorded::uri)
        .contains("/zipkin/api/v2/spans?serviceName=sw-native-a-1fddf66a");
    Fixtures.enqueue(server, "listOperations.zipkin");
    assertThat(client.traces().withBackend(TraceBackend.ZIPKIN).listOperations("s"))
        .containsExactly("get /checkout", "gzip-span", "select orders");
    Fixtures.enqueue(server, "listEndpoints.ok");
    assertThat(client.traces().withBackend(TraceBackend.NATIVE).listOperationsAsync("s").join())
        .contains("/native/entry");
    assertThatThrownBy(() -> client.traces().listOperations(""))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void dependenciesFromTheTopology() {
    Fixtures.enqueue(server, "ping.ok");
    Fixtures.enqueue(server, "topology.global");
    List<DependencyLink> deps = client.traces().getDependencies(NOW.minusSeconds(900), null);
    assertThat(deps)
        .contains(new DependencyLink("sw-native-a-1fddf66a", "sw-native-b-1fddf66a", 0));
    assertThatThrownBy(
            () -> client.traces().withBackend(TraceBackend.ZIPKIN).getDependencies(null, null))
        .isInstanceOf(UnsupportedException.class);
    Fixtures.enqueue(server, "topology.global");
    assertThat(
            client
                .traces()
                .withBackend(TraceBackend.NATIVE)
                .getDependenciesAsync(null, null)
                .join())
        .hasSize(2);
  }

  @Test
  void waitForTraceNativeAndHex() {
    Fixtures.enqueue(server, "getTrace.native-missing");
    Fixtures.enqueue(server, "getTrace.native");
    WaitForTraceOptions opts =
        new WaitForTraceOptions(Duration.ofSeconds(5), Duration.ofMillis(10), 3);
    Trace t = client.traces().waitForTrace(NATIVE_ID, opts);
    assertThat(t.spans()).hasSize(3);
    Fixtures.enqueue(server, "getTrace.zipkin");
    assertThat(client.traces().waitForTrace(OTLP_ID).spans()).hasSize(2);
    Fixtures.enqueue(server, "getTrace.native");
    assertThat(client.traces().waitForTraceAsync(NATIVE_ID, opts).join().traceId())
        .isEqualTo(NATIVE_ID);
    server.fallback(ex -> MockServer.send(ex, 200, Fixtures.body("getTrace.native-missing")));
    assertThatThrownBy(
            () ->
                client
                    .traces()
                    .waitForTrace(
                        NATIVE_ID,
                        new WaitForTraceOptions(Duration.ofMillis(50), Duration.ofMillis(10), 1)))
        .isInstanceOf(TimeoutException.class);
    assertThatThrownBy(() -> client.traces().waitForTrace(" ", opts))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> client.traces().waitForTraceAsync(null, opts).join())
        .hasCauseInstanceOf(ValidationException.class);
  }

  @Test
  void zipkinSpanConversionEdgeCases() {
    ZipkinSpan s =
        new ZipkinSpan(
            "ABC",
            "00F067AA0BA902B7",
            null,
            "weird",
            null,
            5,
            7,
            null,
            List.of(
                new ZipkinSpan.Annotation(1, "plain"),
                new ZipkinSpan.Annotation(2, "e|not json|0"),
                new ZipkinSpan.Annotation(3, "e|[1]|0"),
                new ZipkinSpan.Annotation(4, "e||0"),
                new ZipkinSpan.Annotation(5, "|x|")),
            Map.of("error", "true"));
    Span span = s.toSpan();
    assertThat(span.traceId()).isEqualTo("0".repeat(29) + "abc");
    assertThat(span.spanId()).isEqualTo("00f067aa0ba902b7");
    assertThat(span.kind()).isEqualTo(Span.Kind.UNSPECIFIED);
    assertThat(span.status().code()).isEqualTo(Span.StatusCode.ERROR);
    assertThat(span.events())
        .extracting(Span.Event::name)
        .containsExactly("plain", "e|not json|0", "e|[1]|0", "e", "|x|");
    assertThat(span.attributes()).isEmpty();
    assertThat(span.name()).isEmpty();
    for (String k : new String[] {"PRODUCER", "CONSUMER"}) {
      assertThat(new ZipkinSpan("a", "b", "", k, "n", 0, 0, "", null, null).toSpan().kind().name())
          .isEqualTo(k);
    }
    assertThat(
            new ZipkinSpan(
                    "a",
                    "b",
                    "",
                    "",
                    "n",
                    0,
                    0,
                    "",
                    null,
                    Map.of("otel.status_code", "ERROR", "otel.status_description", ""))
                .toSpan()
                .status()
                .message())
        .isEmpty();
    assertThat(
            new ZipkinSpan("a", "b", "", "", "n", 0, 0, "", null, Map.of("otel.status_code", "OK"))
                .toSpan()
                .status()
                .code())
        .isEqualTo(Span.StatusCode.OK);
    assertThat(ZipkinSpan.toTrace(List.of())).isEmpty();
    assertThat(ZipkinSpan.fromJson(null)).isEmpty();
    assertThat(ZipkinSpan.listFrom(null)).isEmpty();
    assertThat(ZipkinSpan.isHexTraceId(null)).isFalse();
    assertThat(ZipkinSpan.isHexTraceId(NATIVE_ID)).isFalse();
    assertThat(ZipkinSpan.isBookkeepingTag("otel.x")).isTrue();
  }

  @Test
  void nativeSpanConversionEdgeCases() {
    NativeSpanRef r1 = new NativeSpanRef("t", "segP", 2, "CROSS_PROCESS");
    NativeSpanRef r2 = new NativeSpanRef("t2", "segQ", 0, "CROSS_THREAD");
    NativeSpan rootWithRefs =
        new NativeSpan(
            "t.1.2",
            "seg",
            0,
            -1,
            List.of(r1, r2),
            "svc",
            "",
            10,
            5,
            "op",
            "Local",
            "",
            "",
            false,
            "",
            List.of(new KeyValue("k", "v")),
            List.of(
                new NativeSpanLog(
                    11, List.of(new KeyValue("event", "error"), new KeyValue("event", "x"))),
                new NativeSpanLog(12, List.of(new KeyValue("message", "m")))));
    Span s = rootWithRefs.toSpan();
    assertThat(s.parentSpanId()).contains("segP-2");
    assertThat(s.links())
        .singleElement()
        .satisfies(
            l -> {
              assertThat(l.spanId()).isEqualTo("segQ-0");
              assertThat(l.attributes()).containsEntry("sw.ref_type", "CROSS_THREAD");
            });
    assertThat(s.kind()).isEqualTo(Span.Kind.INTERNAL);
    assertThat(s.startMicros()).isEqualTo(10_000);
    assertThat(s.endMicros()).isEqualTo(10_000);
    assertThat(s.events()).extracting(Span.Event::name).containsExactly("error", "log");
    assertThat(s.attributes()).containsOnlyKeys("k");
    NativeSpan child =
        new NativeSpan(
            "t.1.2",
            "seg",
            3,
            1,
            List.of(r1),
            "svc",
            "i",
            1,
            2,
            "op",
            "Other",
            "p",
            "c",
            true,
            "l",
            null,
            null);
    Span c = child.toSpan();
    assertThat(c.parentSpanId()).contains("seg-1");
    assertThat(c.links()).hasSize(1);
    assertThat(c.kind()).isEqualTo(Span.Kind.UNSPECIFIED);
    NativeSpan orphan =
        new NativeSpan(
            "t.1.2", "seg", 0, -1, null, "svc", "", 1, 2, "op", "Entry", "", "", false, "", null,
            null);
    assertThat(orphan.toSpan().isRoot()).isTrue();
    assertThat(new NativeTrace("", List.of(orphan)).toTrace().orElseThrow().traceId())
        .isEqualTo("t.1.2");
    assertThat(new NativeTrace("x", null).toTrace()).isEmpty();
    assertThat(NativeSpan.spanKey("s", 4)).isEqualTo("s-4");
    assertThatThrownBy(() -> Span.builder(" ", "a", "n").build())
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> Span.builder(null, "a", "n").build())
        .isInstanceOf(ValidationException.class);
  }
}
