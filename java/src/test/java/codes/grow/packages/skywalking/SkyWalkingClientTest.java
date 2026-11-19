package codes.grow.packages.skywalking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import codes.grow.packages.skywalking.core.AuthenticationException;
import codes.grow.packages.skywalking.core.CancelledException;
import codes.grow.packages.skywalking.core.HealthStatus;
import codes.grow.packages.skywalking.core.Hooks;
import codes.grow.packages.skywalking.core.Logger;
import codes.grow.packages.skywalking.core.MockServer;
import codes.grow.packages.skywalking.core.RetryPolicy;
import codes.grow.packages.skywalking.core.Secret;
import codes.grow.packages.skywalking.core.TlsOptions;
import codes.grow.packages.skywalking.core.UnsupportedException;
import codes.grow.packages.skywalking.core.ValidationException;
import codes.grow.packages.skywalking.logging.BatchOptions;
import codes.grow.packages.skywalking.logging.BatchingLogClient;
import codes.grow.packages.skywalking.logging.LogInput;
import codes.grow.packages.skywalking.tracing.TracingConfig;
import codes.grow.packages.skywalking.tracing.TracingHandle;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SkyWalkingClientTest {
  private MockServer server;

  @BeforeEach
  void start() throws Exception {
    server = MockServer.start();
  }

  @AfterEach
  void stop() {
    server.close();
  }

  private SkyWalkingClient client() {
    return SkyWalkingClient.builder()
        .url(server.url())
        .zipkinUrl(server.url() + "/zipkin")
        .retry(RetryPolicy.builder().initialBackoff(Duration.ofMillis(1)).build())
        .build();
  }

  @Test
  void healthUsesTheHealthChecker() {
    Fixtures.enqueue(server, "health.http");
    server.enqueue(503, "Status: 503", "Content-Type", "text/plain");
    server.enqueue(500, "oops", "Content-Type", "text/plain");
    try (var c = client()) {
      assertThat(c.health()).isEqualTo(new HealthStatus(true, "ok", Map.of()));
      HealthStatus down = c.health();
      assertThat(down.healthy()).isFalse();
      assertThat(down.status()).isEqualTo("unhealthy");
      assertThat(down.details()).containsEntry("httpStatus", "503");
      assertThat(c.healthAsync().join().details()).containsEntry("httpStatus", "500");
      assertThat(server.requests().get(0).uri()).isEqualTo("/healthcheck");
      assertThat(server.requests().get(0).method()).isEqualTo("GET");
    }
  }

  @Test
  void healthFallsBackToGraphqlCheckHealth() {
    server.enqueue(404, "Status: 404", "Content-Type", "text/plain");
    Fixtures.enqueue(server, "health.graphql");
    server.enqueue(405, "", "Content-Type", "text/plain");
    server.enqueue(200, "{\"data\":{\"checkHealth\":{\"score\":2,\"details\":\"storage down\"}}}");
    server.enqueue(404, "");
    server.enqueue(200, "{\"data\":{\"checkHealth\":null}}");
    server.enqueue(404, "");
    server.enqueue(401, "{\"message\":\"no\"}");
    server.enqueue(404, "");
    server.enqueue(200, "{\"errors\":[{\"message\":\"boom\"}]}");
    try (var c = client()) {
      assertThat(c.health().status()).isEqualTo("ok");
      HealthStatus bad = c.health();
      assertThat(bad.healthy()).isFalse();
      assertThat(bad.details())
          .containsEntry("score", "2")
          .containsEntry("details", "storage down");
      assertThat(c.health().details()).containsEntry("score", "-1").doesNotContainKey("details");
      HealthStatus gw = c.health();
      assertThat(gw.status()).isEqualTo("unhealthy");
      assertThat(gw.details())
          .containsEntry("httpStatus", "401")
          .containsEntry("error", "AUTHENTICATION");
      assertThat(c.health().status()).isEqualTo("unreachable");
    }
    assertThat(Fixtures.request(server.requests().get(1)).get("query").asText())
        .isEqualTo("{checkHealth{score details}}");
  }

  @Test
  void core_health_unreachable() throws Exception {
    int port;
    try (ServerSocket s = new ServerSocket(0)) {
      port = s.getLocalPort();
    }
    try (var c = SkyWalkingClient.builder().url("http://127.0.0.1:" + port).build()) {
      HealthStatus h = c.health();
      assertThat(h.healthy()).isFalse();
      assertThat(h.status()).isEqualTo("unreachable");
      assertThat(c.healthAsync().join().status()).isEqualTo("unreachable");
    }
  }

  @Test
  void healthThrowsOnlyForCancel() {
    SkyWalkingClient c = client();
    c.close();
    assertThatThrownBy(c::health).isInstanceOf(CancelledException.class);
    assertThatThrownBy(() -> c.healthAsync().join()).hasCauseInstanceOf(CancelledException.class);
  }

  @Test
  void pingIsATimedGraphqlRoundTrip() {
    Fixtures.enqueue(server, "ping.ok");
    server.enqueue(401, "{\"message\":\"bad gateway token gw-secret-1\"}");
    Fixtures.enqueue(server, "ping.ok");
    try (var c =
        SkyWalkingClient.builder()
            .url(server.url())
            .header("Authorization", "Bearer gw-secret-1")
            .build()) {
      assertThat(c.ping()).isPositive();
      assertThat(server.requests().get(0).uri()).isEqualTo("/graphql");
      assertThat(server.requests().get(0).header("Authorization")).isEqualTo("Bearer gw-secret-1");
      assertThat(Fixtures.request(server.requests().get(0)).get("query").asText())
          .isEqualTo("{getTimeInfo{timezone currentTimestamp}}");
      assertThatThrownBy(c::ping)
          .isInstanceOf(AuthenticationException.class)
          .message()
          .doesNotContain("gw-secret-1");
      assertThat(c.pingAsync().join()).isPositive();
    }
  }

  @Test
  void serverVersion() {
    Fixtures.enqueue(server, "serverVersion.ok");
    server.enqueue(200, "{\"data\":{\"version\":\"\"}}");
    server.enqueue(200, "{\"data\":{}}");
    Fixtures.enqueue(server, "serverVersion.ok");
    try (var c = client()) {
      assertThat(c.serverVersion()).isEqualTo("10.4.0-b272da3");
      assertThatThrownBy(c::serverVersion).isInstanceOf(UnsupportedException.class);
      assertThatThrownBy(c::serverVersion).isInstanceOf(UnsupportedException.class);
      assertThat(c.serverVersionAsync().join()).isEqualTo("10.4.0-b272da3");
      assertThat(Fixtures.request(server.requests().get(0)).get("query").asText())
          .isEqualTo("{version}");
    }
  }

  @Test
  void rawGraphql() {
    Fixtures.enqueue(server, "serverVersion.ok");
    Fixtures.enqueue(server, "graphql.validation-error");
    Fixtures.enqueue(server, "graphql.fetch-error");
    Fixtures.enqueue(server, "graphql.bad-json");
    Fixtures.enqueue(server, "serverVersion.ok");
    try (var c = client()) {
      GraphQLResponse ok = c.graphql("query($x:Int){version}", Map.of("x", 1));
      assertThat(ok.field("version").map(n -> n.asText())).contains("10.4.0-b272da3");
      assertThat(ok.hasErrors()).isFalse();
      ok.throwIfErrors("graphql");
      assertThat(Fixtures.request(server.requests().get(0)).get("variables").get("x").asInt())
          .isEqualTo(1);
      assertThatThrownBy(() -> c.graphql("{nope}"))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("FieldUndefined");
      GraphQLResponse partial = c.graphql("{getGlobalTopology{nodes{id}}}");
      assertThat(partial.hasErrors()).isTrue();
      assertThat(partial.errors().get(0).path()).containsExactly("getGlobalTopology");
      assertThat(partial.errors().get(0).classification()).contains("DataFetchingException");
      assertThat(partial.field("getGlobalTopology")).isEmpty();
      assertThatThrownBy(() -> partial.throwIfErrors("x")).isInstanceOf(ValidationException.class);
      assertThatThrownBy(() -> c.graphql("not json")).isInstanceOf(ValidationException.class);
      assertThat(c.graphqlAsync("{version}", null).join().data()).isPresent();
      assertThatThrownBy(() -> c.graphql(" ")).isInstanceOf(ValidationException.class);
    }
  }

  @Test
  void mutationsAreNotRetried() {
    server.enqueue(503, "{}").enqueue(503, "{}").enqueue(200, "{\"data\":{}}");
    try (var c = client()) {
      assertThat(c.graphql("{version}").data()).isPresent();
      assertThat(server.requests()).hasSize(3);
      server.enqueue(503, "{}");
      assertThatThrownBy(() -> c.graphql("mutation { x }")).hasMessageContaining("503");
      assertThat(server.requests()).hasSize(4);
    }
  }

  @Test
  void core_close_idempotent() throws Exception {
    AtomicInteger closed = new AtomicInteger();
    SkyWalkingClient c = client();
    c.register(closed::incrementAndGet);
    c.register(
        () -> {
          throw new IllegalStateException("ignored");
        });
    c.close();
    c.close();
    assertThat(c.isClosed()).isTrue();
    assertThat(closed).hasValue(1);
    assertThat(c.transport().isClosed()).isTrue();
    assertThatThrownBy(c::ping).isInstanceOf(CancelledException.class);
    assertThatThrownBy(() -> c.query().listServices(null)).isInstanceOf(CancelledException.class);
    assertThatThrownBy(() -> c.traces().listServices()).isInstanceOf(CancelledException.class);
  }

  @Test
  void builderSetsEveryField() {
    Hooks hooks = new Hooks() {};
    Logger logger = new Logger() {};
    try (var c =
        SkyWalkingClient.builder()
            .url(server.url() + "/graphql")
            .graphqlPath("/gql")
            .otlpEndpoint("http://collector:4317")
            .otlpProtocol(OtlpProtocol.HTTP_PROTOBUF)
            .otlpHeader("X-Api-Key", "k-secret-1")
            .zipkinUrl("http://zipkin:9412/zipkin")
            .authentication(Secret.of("s3cr3t-value"))
            .traceBackend(TraceBackend.NATIVE)
            .connectTimeout(Duration.ofSeconds(1))
            .requestTimeout(Duration.ofSeconds(2))
            .totalTimeout(Duration.ofSeconds(3))
            .retry(RetryPolicy.none())
            .tls(TlsOptions.defaults())
            .proxy("http://proxy.invalid:3128")
            .header("X-A", "1")
            .userAgentSuffix("app/1")
            .hooks(hooks)
            .logger(logger)
            .build()) {
      SkyWalkingConfig cfg = c.config();
      assertThat(cfg.url()).isEqualTo(server.url());
      assertThat(cfg.graphqlUrl()).isEqualTo(server.url() + "/gql");
      assertThat(cfg.otlpEndpoint()).isEqualTo("http://collector:4317");
      assertThat(cfg.otlpProtocol()).isEqualTo(OtlpProtocol.HTTP_PROTOBUF);
      assertThat(cfg.otlpHeaders()).containsEntry("x-api-key", "k-secret-1");
      assertThat(cfg.zipkinUrl()).isEqualTo("http://zipkin:9412/zipkin");
      assertThat(cfg.authentication()).isPresent();
      assertThat(cfg.traceBackend()).isEqualTo(TraceBackend.NATIVE);
      assertThat(c.traces().backend()).isEqualTo(TraceBackend.NATIVE);
      assertThat(cfg.connectTimeout()).isEqualTo(Duration.ofSeconds(1));
      assertThat(cfg.requestTimeout()).isEqualTo(Duration.ofSeconds(2));
      assertThat(cfg.totalTimeout()).isEqualTo(Duration.ofSeconds(3));
      assertThat(cfg.retry().maxRetries()).isZero();
      assertThat(cfg.tls().isCustom()).isFalse();
      assertThat(cfg.proxy()).contains("http://proxy.invalid:3128");
      assertThat(cfg.headers()).containsEntry("X-A", "1");
      assertThat(cfg.userAgentSuffix()).contains("app/1");
      assertThat(cfg.hooks()).isSameAs(hooks);
      assertThat(cfg.logger()).isSameAs(logger);
      assertThat(c.toString()).doesNotContain("s3cr3t-value").doesNotContain("k-secret-1");
      assertThat(c.transport().config().knownSecrets())
          .containsExactly("s3cr3t-value", "k-secret-1");
      assertThat(c.query()).isNotNull();
      assertThat(c.logs().maxScanPages())
          .isEqualTo(SkyWalkingLogQueryClient.DEFAULT_MAX_SCAN_PAGES);
      cfg.authentication().get().clear();
      assertThat(c.transport().config().knownSecrets()).containsExactly("k-secret-1");
    }
    try (var c = SkyWalkingClient.builder().url(server.url()).authentication("tok").build()) {
      assertThat(c.config().authentication().map(Secret::reveal)).contains("tok");
    }
  }

  @Test
  void createAndFromEnv() {
    try (var c = SkyWalkingClient.create(SkyWalkingConfig.builder().url(server.url()).build())) {
      assertThat(c.isClosed()).isFalse();
    }
    try (var c = SkyWalkingClient.fromEnv()) {
      assertThat(c.config()).isNotNull();
    }
    var env =
        codes.grow.packages.skywalking.core.EnvReader.of(
            Map.of(
                codes.grow.packages.skywalking.core.EnvReader.DEFAULT_PREFIX + "URL",
                server.url()));
    try (var c = SkyWalkingClient.builder().fromEnv(env).build()) {
      assertThat(c.config().url()).isEqualTo(server.url());
    }
  }

  @Test
  void logClientsSendNativeLogsAndCloseWithTheClient() throws Exception {
    server.fallback(ex -> MockServer.send(ex, 200, ""));
    SkyWalkingClient c = client();
    BatchingLogClient logs =
        c.logClient(
            SkyWalkingLogClientOptions.builder()
                .serviceName("svc")
                .serviceInstance("inst-1")
                .resource(Map.of("team", "core"))
                .batchOptions(BatchOptions.builder().flushInterval(Duration.ofMillis(50)).build())
                .build());
    logs.send(LogInput.of("hello"));
    logs.flush();
    assertThat(logs.stats().sent()).isEqualTo(1);
    var req = server.requests().get(0);
    assertThat(req.uri()).isEqualTo("/v3/logs");
    var body = Fixtures.request(req).get(0);
    assertThat(body.get("service").asText()).isEqualTo("svc");
    assertThat(body.get("serviceInstance").asText()).isEqualTo("inst-1");
    assertThat(body.get("body").get("text").get("text").asText()).isEqualTo("hello");
    BatchingLogClient simple = c.logClient("other");
    assertThat(simple.resource()).containsEntry("service.name", "other");
    c.close();
    BatchingLogClient standalone =
        SkyWalkingClient.createLogClient(
            SkyWalkingConfig.builder().url(server.url()).build(), null);
    standalone.send(LogInput.of("x"));
    standalone.close();
    assertThat(standalone.stats().sent()).isEqualTo(1);
    assertThat(Fixtures.request(server.requests().get(1)).get(0).get("service").asText())
        .isEqualTo(SkyWalkingLogExporter.DEFAULT_SERVICE_NAME);
  }

  @Test
  void initTracingIsShutDownWithTheClient() {
    SkyWalkingClient c = client();
    TracingHandle h = c.initTracing(TracingConfig.builder("svc").setGlobal(false).build());
    assertThat(h.config().endpoint()).contains(c.config().otlpEndpoint());
    assertThat(h.config().protocol()).isEqualTo(TracingConfig.Protocol.GRPC);
    c.close();
    assertThat(h.isShutdown()).isTrue();
  }
}
