package codes.grow.packages.skywalking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import codes.grow.packages.skywalking.core.ConfigException;
import codes.grow.packages.skywalking.core.TlsOptions;
import codes.grow.packages.skywalking.tracing.TracingConfig;
import codes.grow.packages.skywalking.tracing.TracingHandle;
import com.fasterxml.jackson.databind.JsonNode;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class Sw8AndTracingTest {
  @SuppressWarnings("deprecation")
  private static final TextMapGetter<Map<String, String>> GETTER =
      new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
          return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
          return carrier == null ? null : carrier.get(key);
        }
      };

  @Test
  void sw8VectorsFromTheRealServer() {
    JsonNode v = Fixtures.load("sw8.vectors");
    String header = v.get("sw8").get("header").asText();
    JsonNode d = v.get("sw8").get("decoded");
    Sw8Context ctx = Sw8.decode(header).orElseThrow();
    assertThat(ctx.sample()).isTrue();
    assertThat(ctx.traceId()).isEqualTo(d.get("traceId").asText());
    assertThat(ctx.parentTraceSegmentId()).isEqualTo(d.get("parentTraceSegmentId").asText());
    assertThat(ctx.parentSpanId()).isEqualTo(1);
    assertThat(ctx.parentService()).isEqualTo(d.get("parentService").asText());
    assertThat(ctx.parentServiceInstance()).isEqualTo(d.get("parentServiceInstance").asText());
    assertThat(ctx.parentEndpoint()).isEqualTo(d.get("parentEndpoint").asText());
    assertThat(ctx.networkAddressUsedAtPeer())
        .isEqualTo(d.get("networkAddressUsedAtPeer").asText());
    assertThat(ctx.encode()).isEqualTo(header);
    for (JsonNode bad : v.get("sw8").get("invalid")) {
      assertThat(Sw8.decode(bad.asText())).as(bad.asText()).isEmpty();
    }
    assertThat(Sw8.decode(null)).isEmpty();
    assertThat(Sw8.decode("1-YQ==-YQ==-99999999999-YQ==-YQ==-YQ==-YQ==")).isEmpty();
    assertThat(Sw8.decode("1--YQ==-1-YQ==-YQ==-YQ==-YQ==")).isEmpty();
    assertThat(Sw8.decode("0-YQ==-YQ==--1-YQ==-YQ==-YQ==-YQ==")).isEmpty();
    assertThat(Sw8.decode("0-YQ==-YQ==-1--YQ==-YQ==-YQ==").orElseThrow().sample()).isFalse();
    String corr = v.get("sw8Correlation").get("header").asText();
    Map<String, String> entries = Sw8.decodeCorrelation(corr);
    assertThat(entries).containsExactly(Map.entry("tenant", "acme"), Map.entry("run", "1fddf66a"));
    assertThat(Sw8.encodeCorrelation(entries)).isEqualTo(corr);
    assertThat(Sw8.decodeCorrelation("a:b:c,!!:YQ==,:YQ==,YQ==:!!,YQ==")).isEmpty();
    assertThat(Sw8.decodeCorrelation(null)).isEmpty();
    Sw8Context empty = new Sw8Context(false, null, null, 0, null, null, null, null);
    assertThat(empty.traceId()).isEmpty();
    assertThat(Sw8.attributes(ctx))
        .containsEntry("sw8.parent.segment_id", ctx.parentTraceSegmentId())
        .containsEntry("sw8.parent.span_id", 1L)
        .containsEntry("sw8.peer", ctx.networkAddressUsedAtPeer());
  }

  @Test
  void propagatorExtractsAndInjectsOnlyNativeMetadata() {
    Sw8Propagator p = Sw8Propagator.getInstance();
    assertThat(p.fields()).containsExactly("sw8", "sw8-correlation");
    assertThat(p.toString()).isEqualTo("Sw8Propagator");
    Map<String, String> out = new HashMap<>();
    p.inject(Context.root(), out, Map::put);
    assertThat(out).isEmpty();
    p.inject(null, out, Map::put);
    p.inject(Context.root(), out, null);
    Sw8Context ctx = new Sw8Context(true, "t.1.2", "seg", 3, "a", "ai", "/e", "b:80");
    Map<String, String> corr = new LinkedHashMap<>();
    corr.put("run", "1");
    corr.put("tenant", "acme");
    p.inject(Sw8.withContext(Context.root(), ctx, corr), out, Map::put);
    assertThat(out.get("sw8")).isEqualTo(Sw8.encode(ctx));
    assertThat(out.get("sw8-correlation")).isEqualTo(Sw8.encodeCorrelation(corr));
    Map<String, String> only = new HashMap<>();
    p.inject(Sw8.withContext(Context.root(), ctx), only, Map::put);
    assertThat(only).containsOnlyKeys("sw8");
    Map<String, String> none = new HashMap<>();
    p.inject(Sw8.withContext(Context.root(), ctx, Map.of()), none, Map::put);
    assertThat(none).containsOnlyKeys("sw8");
    Context extracted = p.extract(Context.root(), out, GETTER);
    assertThat(Sw8.fromContext(extracted)).contains(ctx);
    assertThat(Baggage.fromContext(extracted).getEntryValue("tenant")).isEqualTo("acme");
    assertThat(Sw8.fromContext(p.extract(null, Map.of(), GETTER))).isEmpty();
    assertThat(p.extract(Context.root(), out, null)).isSameAs(Context.root());
    Context bad =
        p.extract(Context.root(), Map.of("sw8", "garbage", "sw8-correlation", "x"), GETTER);
    assertThat(Sw8.fromContext(bad)).isEmpty();
    assertThat(Baggage.fromContext(bad).isEmpty()).isTrue();
  }

  @Test
  void withDefaultsMergesTheSkyWalkingSettings() {
    SkyWalkingConfig sw =
        SkyWalkingConfig.builder()
            .url("http://oap:12800")
            .otlpHeader("x-tenant", "acme")
            .authentication("tok")
            .tls(TlsOptions.builder().insecureSkipVerify(true).build())
            .build();
    TracingConfig merged = SkyWalkingTracing.withDefaults(TracingConfig.builder("svc").build(), sw);
    assertThat(merged.endpoint()).contains("http://oap:11800");
    assertThat(merged.protocol()).isEqualTo(TracingConfig.Protocol.GRPC);
    assertThat(merged.headers())
        .containsEntry("x-tenant", "acme")
        .containsEntry(SkyWalkingTracing.AUTHENTICATION_HEADER, "tok");
    assertThat(merged.tls()).isPresent();
    TracingConfig explicit =
        SkyWalkingTracing.withDefaults(
            TracingConfig.builder("svc")
                .endpoint("http://collector:4318")
                .header("Authentication", "mine")
                .header("X-Tenant", "other")
                .tls(TlsOptions.defaults())
                .build(),
            sw);
    assertThat(explicit.endpoint()).contains("http://collector:4318");
    assertThat(explicit.protocol()).isEqualTo(TracingConfig.Protocol.HTTP_PROTOBUF);
    assertThat(explicit.headers()).containsOnlyKeys("Authentication", "X-Tenant");
    TracingConfig plain =
        SkyWalkingTracing.withDefaults(
            TracingConfig.builder("svc").build(), SkyWalkingConfig.builder().build());
    assertThat(plain.headers()).isEmpty();
    assertThat(plain.tls()).isEmpty();
    TracingConfig.Builder b = SkyWalkingTracing.configBuilder("svc", sw);
    assertThat(b.build().endpoint()).contains("http://oap:11800");
    assertThat(OtlpProtocol.HTTP_PROTOBUF.toTracingProtocol())
        .isEqualTo(TracingConfig.Protocol.HTTP_PROTOBUF);
  }

  @Test
  void exporterFactory() {
    assertThatThrownBy(
            () ->
                SkyWalkingTracing.createSpanExporter(
                    TracingConfig.builder("s").protocol(TracingConfig.Protocol.NATIVE).build()))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("native");
    assertThatThrownBy(
            () ->
                SkyWalkingTracing.exporterFactory()
                    .create(
                        TracingConfig.builder("s")
                            .protocol(TracingConfig.Protocol.HTTP_PROTOBUF)
                            .build()))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("explicit endpoint");
    SpanExporter http =
        SkyWalkingTracing.createSpanExporter(
            TracingConfig.builder("s")
                .protocol(TracingConfig.Protocol.HTTP_PROTOBUF)
                .endpoint("http://collector:4318")
                .build());
    http.shutdown();
    SpanExporter grpc =
        SkyWalkingTracing.createSpanExporter(
            TracingConfig.builder("s").protocol(TracingConfig.Protocol.GRPC).build());
    assertThat(grpc.toString()).contains("127.0.0.1:11800");
    grpc.shutdown();
    SpanExporter grpc2 =
        SkyWalkingTracing.createSpanExporter(
            TracingConfig.builder("s")
                .protocol(TracingConfig.Protocol.GRPC)
                .endpoint("http://oap:11800")
                .build());
    grpc2.shutdown();
  }

  @Test
  void initTracingRegistersTheSw8Propagator() {
    try (TracingHandle h =
        SkyWalkingTracing.initTracing(
            TracingConfig.builder("svc")
                .setGlobal(false)
                .propagators(java.util.List.of("tracecontext", "baggage", "sw8"))
                .build(),
            SkyWalkingConfig.builder().build())) {
      assertThat(h.propagators().getTextMapPropagator().fields()).contains("sw8", "traceparent");
    }
    try (TracingHandle h =
        SkyWalkingTracing.initTracing(TracingConfig.builder("svc").setGlobal(false).build())) {
      assertThat(h.config().protocol()).isEqualTo(TracingConfig.Protocol.GRPC);
    }
  }
}
