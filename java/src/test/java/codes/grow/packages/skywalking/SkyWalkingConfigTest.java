package codes.grow.packages.skywalking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import codes.grow.packages.skywalking.core.ConfigException;
import codes.grow.packages.skywalking.core.EnvReader;
import codes.grow.packages.skywalking.core.RetryPolicy;
import codes.grow.packages.skywalking.core.Secret;
import codes.grow.packages.skywalking.core.TlsOptions;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SkyWalkingConfigTest {
  private static final String P = EnvReader.DEFAULT_PREFIX;

  private static SkyWalkingConfig.Builder cfg() {
    return SkyWalkingConfig.builder();
  }

  @Test
  void defaultsDeriveOtlpAndZipkinFromTheUrl() {
    SkyWalkingConfig d = SkyWalkingConfig.fromEnv(Map.of());
    assertThat(d.url()).isEqualTo("http://127.0.0.1:12800");
    assertThat(d.graphqlPath()).isEqualTo("/graphql");
    assertThat(d.graphqlUrl()).isEqualTo("http://127.0.0.1:12800/graphql");
    assertThat(d.otlpEndpoint()).isEqualTo("http://127.0.0.1:11800");
    assertThat(d.otlpProtocol()).isEqualTo(OtlpProtocol.GRPC);
    assertThat(d.otlpHeaders()).isEmpty();
    assertThat(d.zipkinUrl()).isEqualTo("http://127.0.0.1:9412/zipkin");
    assertThat(d.authentication()).isEmpty();
    assertThat(d.traceBackend()).isEqualTo(TraceBackend.AUTO);
    assertThat(d.connectTimeout()).isEqualTo(Duration.ofSeconds(10));
    assertThat(d.requestTimeout()).isEqualTo(Duration.ofSeconds(30));
    assertThat(d.totalTimeout()).isEqualTo(Duration.ofSeconds(120));
    assertThat(d.retry().maxRetries()).isEqualTo(3);
    SkyWalkingConfig https = cfg().url("https://oap.example:443/graphql/").build();
    assertThat(https.url()).isEqualTo("https://oap.example:443");
    assertThat(https.otlpEndpoint()).isEqualTo("https://oap.example:11800");
    assertThat(https.zipkinUrl()).isEqualTo("https://oap.example:9412/zipkin");
    assertThat(SkyWalkingConfig.fromEnv()).isNotNull();
  }

  @Test
  void core_config_env_precedence() {
    Map<String, String> env = new HashMap<>();
    env.put(P + "URL", "http://from-env:1");
    env.put("GROW_SKYWALKING_URL", "http://short:1");
    env.put("SW_GRAPHQL_URL", "http://vendor:1/graphql");
    env.put(P + "GRAPHQL_PATH", "/gql");
    env.put(P + "OTLP_ENDPOINT", "http://otlp:11800");
    env.put("OTEL_EXPORTER_OTLP_ENDPOINT", "http://ignored:4317");
    env.put(P + "OTLP_PROTOCOL", "HTTP/PROTOBUF");
    env.put("OTEL_EXPORTER_OTLP_HEADERS", "X-Key=a%20b, other = 2 ,");
    env.put(P + "AUTHENTICATION", "env-token");
    env.put("SW_AUTHENTICATION", "vendor-token");
    env.put(P + "ZIPKIN_URL", "http://zipkin:9412");
    env.put(P + "TRACE_BACKEND", "Native");
    env.put(P + "CONNECT_TIMEOUT", "1500");
    env.put(P + "REQUEST_TIMEOUT", "2s");
    env.put(P + "TOTAL_TIMEOUT", "1m");
    env.put(P + "MAX_RETRIES", "5");
    env.put(P + "CA_CERT", "/tmp/ca.pem");
    env.put("GROW_SKYWALKING_CLIENT_CERT", "/tmp/c.pem");
    env.put("GROW_SKYWALKING_CLIENT_KEY", "/tmp/k.pem");
    env.put("GROW_SKYWALKING_INSECURE_SKIP_VERIFY", "on");
    env.put("GROW_SKYWALKING_PROXY", "http://proxy:1");
    SkyWalkingConfig c = SkyWalkingConfig.fromEnv(env);
    assertThat(c.url()).isEqualTo("http://from-env:1");
    assertThat(c.graphqlUrl()).isEqualTo("http://from-env:1/gql");
    assertThat(c.otlpEndpoint()).isEqualTo("http://otlp:11800");
    assertThat(c.otlpProtocol()).isEqualTo(OtlpProtocol.HTTP_PROTOBUF);
    assertThat(c.otlpHeaders()).containsEntry("x-key", "a b").containsEntry("other", "2");
    assertThat(c.authentication().map(Secret::reveal)).contains("env-token");
    assertThat(c.zipkinUrl()).isEqualTo("http://zipkin:9412/zipkin");
    assertThat(c.traceBackend()).isEqualTo(TraceBackend.NATIVE);
    assertThat(c.connectTimeout()).isEqualTo(Duration.ofMillis(1500));
    assertThat(c.requestTimeout()).isEqualTo(Duration.ofSeconds(2));
    assertThat(c.totalTimeout()).isEqualTo(Duration.ofMinutes(1));
    assertThat(c.retry().maxRetries()).isEqualTo(5);
    assertThat(c.tls().insecureSkipVerify()).isTrue();
    assertThat(c.tls().caCertPath()).isPresent();
    assertThat(c.tls().clientCertPath()).isPresent();
    assertThat(c.proxy()).contains("http://proxy:1");
    // explicit values win over the environment
    SkyWalkingConfig explicit =
        cfg()
            .url("http://explicit:2")
            .graphqlPath("/x")
            .otlpEndpoint("http://o:1")
            .otlpProtocol(OtlpProtocol.GRPC)
            .otlpHeaders(Map.of("A", "1"))
            .authentication("explicit-token")
            .zipkinUrl("http://z:1/zipkin")
            .traceBackend(TraceBackend.ZIPKIN)
            .connectTimeout(Duration.ofSeconds(9))
            .requestTimeout(Duration.ofSeconds(9))
            .totalTimeout(Duration.ofSeconds(9))
            .retry(RetryPolicy.none())
            .tls(TlsOptions.defaults())
            .proxy("http://p2:1")
            .fromEnv(EnvReader.of(env))
            .build();
    assertThat(explicit.url()).isEqualTo("http://explicit:2");
    assertThat(explicit.graphqlPath()).isEqualTo("/x");
    assertThat(explicit.otlpEndpoint()).isEqualTo("http://o:1");
    assertThat(explicit.otlpProtocol()).isEqualTo(OtlpProtocol.GRPC);
    assertThat(explicit.otlpHeaders()).containsExactly(Map.entry("a", "1"));
    assertThat(explicit.authentication().map(Secret::reveal)).contains("explicit-token");
    assertThat(explicit.zipkinUrl()).isEqualTo("http://z:1/zipkin");
    assertThat(explicit.traceBackend()).isEqualTo(TraceBackend.ZIPKIN);
    assertThat(explicit.retry().maxRetries()).isZero();
    assertThat(explicit.tls().insecureSkipVerify()).isFalse();
    assertThat(explicit.proxy()).contains("http://p2:1");
    // invalid values name the variable, never the value
    assertThatThrownBy(() -> SkyWalkingConfig.fromEnv(Map.of(P + "TOTAL_TIMEOUT", "forever")))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining(P + "TOTAL_TIMEOUT")
        .message()
        .doesNotContain("forever");
    assertThatThrownBy(() -> SkyWalkingConfig.fromEnv(Map.of(P + "MAX_RETRIES", "-1")))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining(P + "MAX_RETRIES");
  }

  @Test
  void vendorVariables() {
    SkyWalkingConfig short1 =
        SkyWalkingConfig.fromEnv(
            Map.of("GROW_SKYWALKING_URL", "http://short:1", "SW_OAP_ADDRESS", "http://ui:2"));
    assertThat(short1.url()).isEqualTo("http://short:1");
    SkyWalkingConfig gql =
        SkyWalkingConfig.fromEnv(
            Map.of("SW_GRAPHQL_URL", "http://oap:12800/GraphQL/", "SW_OAP_ADDRESS", "http://ui:2"));
    assertThat(gql.url()).isEqualTo("http://oap:12800");
    assertThat(gql.otlpEndpoint()).isEqualTo("http://oap:11800");
    SkyWalkingConfig ui = SkyWalkingConfig.fromEnv(Map.of("SW_OAP_ADDRESS", "http://ui:2"));
    assertThat(ui.url()).isEqualTo("http://ui:2");
    SkyWalkingConfig collector =
        SkyWalkingConfig.fromEnv(
            Map.of(
                "SW_AGENT_COLLECTOR_BACKEND_SERVICES", "oap:11800,oap2:11800",
                "SW_AGENT_AUTHENTICATION", "agent-token",
                "SW_ZIPKIN_ADDRESS", "http://oap:9412/zipkin/",
                "OTEL_EXPORTER_OTLP_TRACES_PROTOCOL", "grpc",
                "OTEL_EXPORTER_OTLP_TRACES_HEADERS", "authentication=from-header"));
    assertThat(collector.otlpEndpoint()).isEqualTo("http://oap:11800");
    assertThat(collector.authentication().map(Secret::reveal)).contains("agent-token");
    assertThat(collector.zipkinUrl()).isEqualTo("http://oap:9412/zipkin");
    assertThat(collector.otlpProtocol()).isEqualTo(OtlpProtocol.GRPC);
    assertThat(collector.knownSecrets()).containsExactly("agent-token", "from-header");
    assertThat(
            SkyWalkingConfig.fromEnv(Map.of("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", "http://t:1"))
                .otlpEndpoint())
        .isEqualTo("http://t:1");
    assertThat(SkyWalkingConfig.collectorToUrl("https://a:1")).isEqualTo("https://a:1");
    assertThat(SkyWalkingConfig.zipkinQueryUrl("http://z:9412/ZIPKIN"))
        .isEqualTo("http://z:9412/ZIPKIN");
    assertThat(SkyWalkingConfig.baseFromGraphqlUrl("http://h:1/")).isEqualTo("http://h:1");
  }

  @Test
  void invalidEnvironmentValues() {
    assertThatThrownBy(() -> SkyWalkingConfig.fromEnv(Map.of(P + "OTLP_PROTOCOL", "thrift")))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining(P + "OTLP_PROTOCOL")
        .message()
        .doesNotContain("thrift");
    assertThatThrownBy(() -> SkyWalkingConfig.fromEnv(Map.of(P + "TRACE_BACKEND", "jaeger")))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining(P + "TRACE_BACKEND");
    assertThatThrownBy(
            () ->
                SkyWalkingConfig.fromEnv(Map.of("OTEL_EXPORTER_OTLP_HEADERS", "secret-without-eq")))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("OTEL_EXPORTER_OTLP_HEADERS")
        .message()
        .doesNotContain("secret-without-eq");
    assertThatThrownBy(
            () -> SkyWalkingConfig.fromEnv(Map.of("OTEL_EXPORTER_OTLP_HEADERS", "k=%zz")))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("percent");
    assertThatThrownBy(() -> TraceBackend.parse(null, "x")).isInstanceOf(ConfigException.class);
    assertThatThrownBy(() -> OtlpProtocol.parse(null, "x")).isInstanceOf(ConfigException.class);
    assertThat(TraceBackend.AUTO.wireValue()).isEqualTo("auto");
    assertThat(OtlpProtocol.HTTP_PROTOBUF.wireValue()).isEqualTo("http/protobuf");
  }

  @Test
  void validation() {
    assertThatThrownBy(() -> cfg().url("ftp://x").build()).isInstanceOf(ConfigException.class);
    assertThatThrownBy(() -> cfg().url("http://[").build()).isInstanceOf(ConfigException.class);
    assertThatThrownBy(() -> cfg().url("mailto:x").build()).isInstanceOf(ConfigException.class);
    assertThatThrownBy(() -> cfg().otlpEndpoint("oap:11800").build())
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("otlpEndpoint");
    assertThatThrownBy(() -> cfg().zipkinUrl("ftp://z").build())
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("zipkinUrl");
    assertThatThrownBy(() -> cfg().graphqlPath("graphql").build())
        .isInstanceOf(ConfigException.class);
    assertThatThrownBy(() -> cfg().authentication(" ").build()).isInstanceOf(ConfigException.class);
    assertThatThrownBy(() -> cfg().connectTimeout(Duration.ZERO).build())
        .isInstanceOf(ConfigException.class);
    assertThatThrownBy(() -> cfg().requestTimeout(Duration.ofSeconds(-1)).build())
        .isInstanceOf(ConfigException.class);
    assertThatThrownBy(() -> cfg().totalTimeout(Duration.ZERO).build())
        .isInstanceOf(ConfigException.class);
    assertThatThrownBy(() -> cfg().tls(TlsOptions.builder().clientCertPem("x").build()).build())
        .isInstanceOf(ConfigException.class);
    assertThatThrownBy(() -> cfg().proxy("relative").build()).isInstanceOf(ConfigException.class);
    assertThatThrownBy(() -> cfg().proxy("http://[").build()).isInstanceOf(ConfigException.class);
    assertThat(cfg().authentication((String) null).build().authentication()).isEmpty();
    assertThat(cfg().url("not a url").otlpEndpoint("http://o:1").zipkinUrl("http://z:1"))
        .isNotNull();
    assertThatThrownBy(
            () -> cfg().url("not a url").otlpEndpoint("http://o:1").zipkinUrl("http://z:1").build())
        .isInstanceOf(ConfigException.class);
  }

  @Test
  void core_redaction_config() {
    SkyWalkingConfig c =
        cfg()
            .url("http://user:pw@h:1")
            .authentication("tok-very-secret")
            .otlpHeader("X-Api-Key", "otlp-secret")
            .otlpHeader("x-tenant", "acme")
            .header("Authorization", "Bearer gw-secret")
            .proxy("http://u:proxypass@p:3128")
            .build();
    assertThat(c.toString())
        .doesNotContain("tok-very-secret")
        .doesNotContain("otlp-secret")
        .doesNotContain("acme")
        .doesNotContain("gw-secret")
        .doesNotContain("proxypass")
        .doesNotContain(":pw@")
        .contains("authentication=[REDACTED]")
        .contains("traceBackend=auto")
        .contains("otlpProtocol=grpc");
    assertThat(c.knownSecrets()).containsExactly("tok-very-secret", "otlp-secret");
    assertThat(cfg().build().toString()).contains("authentication=null").contains("proxy=null");
    SkyWalkingConfig copy = c.toBuilder().build();
    assertThat(copy.toString()).isEqualTo(c.toString());
    assertThat(copy.headers()).isEqualTo(c.headers());
  }
}
