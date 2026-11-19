package codes.grow.packages.skywalking;

import codes.grow.packages.skywalking.core.CancelledException;
import codes.grow.packages.skywalking.core.ConfigException;
import codes.grow.packages.skywalking.core.EnvReader;
import codes.grow.packages.skywalking.core.HealthStatus;
import codes.grow.packages.skywalking.core.Hooks;
import codes.grow.packages.skywalking.core.HttpTransport;
import codes.grow.packages.skywalking.core.Logger;
import codes.grow.packages.skywalking.core.RetryPolicy;
import codes.grow.packages.skywalking.core.Secret;
import codes.grow.packages.skywalking.core.SkyWalkingException;
import codes.grow.packages.skywalking.core.TlsOptions;
import codes.grow.packages.skywalking.core.TransportRequest;
import codes.grow.packages.skywalking.core.TransportResponse;
import codes.grow.packages.skywalking.core.UnsupportedException;
import codes.grow.packages.skywalking.logging.BatchingLogClient;
import codes.grow.packages.skywalking.tracing.TracingConfig;
import codes.grow.packages.skywalking.tracing.TracingHandle;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Client for Apache SkyWalking OAP.
 *
 * <p>Implements SPEC section 2.4 plus the SkyWalking modules: {@link #query()} (GraphQL query API),
 * {@link #traces()} (SPEC {@code TraceQueryClient}), {@link #logs()} (SPEC {@code LogQueryClient}),
 * {@link #logClient} (native log sender) and {@link #initTracing} (OTLP/gRPC tracing). Thread-safe;
 * create one per OAP and close it when done.
 *
 * <pre>{@code
 * try (SkyWalkingClient client = SkyWalkingClient.fromEnv()) {
 *   System.out.println(client.serverVersion());
 *   client.query().listServices().forEach(s -> System.out.println(s.name()));
 * }
 * }</pre>
 *
 * <p>Every I/O method has a blocking form and an {@code …Async} form returning {@link
 * CompletableFuture}.
 */
public final class SkyWalkingClient implements AutoCloseable {
  private final SkyWalkingConfig config;
  private final HttpTransport transport;
  private final SkyWalkingApi api;
  private final SkyWalkingQueryClient query;
  private final SkyWalkingTraceQueryClient traces;
  private final SkyWalkingLogQueryClient logs;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final List<AutoCloseable> resources = new ArrayList<>();

  private SkyWalkingClient(SkyWalkingConfig config, Clock clock) {
    this.config = config;
    this.transport = new HttpTransport(config.toTransportConfig());
    this.api = new SkyWalkingApi(transport, config, clock);
    this.query = new SkyWalkingQueryClient(api);
    this.traces = new SkyWalkingTraceQueryClient(api, query, config.traceBackend());
    this.logs =
        new SkyWalkingLogQueryClient(query, api, SkyWalkingLogQueryClient.DEFAULT_MAX_SCAN_PAGES);
  }

  /**
   * Creates a client.
   *
   * @param config validated configuration
   * @return a new client
   * @throws ConfigException when TLS or proxy settings are invalid
   */
  public static SkyWalkingClient create(SkyWalkingConfig config) {
    return create(config, Clock.systemUTC());
  }

  static SkyWalkingClient create(SkyWalkingConfig config, Clock clock) {
    config.validate();
    return new SkyWalkingClient(config, clock);
  }

  /**
   * Creates a client from the SDK and vendor environment variables.
   *
   * @return a new client
   */
  public static SkyWalkingClient fromEnv() {
    return create(SkyWalkingConfig.fromEnv());
  }

  /**
   * Starts a client builder.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * A standalone {@link BatchingLogClient} that sends native logs to {@code POST /v3/logs} (owns
   * its connections; {@code close()} flushes and releases them).
   *
   * @param config configuration
   * @param options service name, instance, resource and batch settings
   * @return the log client
   */
  public static BatchingLogClient createLogClient(
      SkyWalkingConfig config, SkyWalkingLogClientOptions options) {
    config.validate();
    HttpTransport t = new HttpTransport(config.toTransportConfig());
    return logClient(t, options, true);
  }

  private static BatchingLogClient logClient(
      HttpTransport t, SkyWalkingLogClientOptions options, boolean owns) {
    SkyWalkingLogClientOptions o =
        options == null ? SkyWalkingLogClientOptions.builder().build() : options;
    SkyWalkingLogExporter exporter =
        new SkyWalkingLogExporter(t, o.serviceName().orElse(null), owns);
    return BatchingLogClient.builder(exporter)
        .batchOptions(o.batchOptions())
        .resource(o.resource())
        .build();
  }

  /**
   * Returns the configuration.
   *
   * @return the configuration
   */
  public SkyWalkingConfig config() {
    return config;
  }

  /**
   * Returns the HTTP transport, for service modules built on this client.
   *
   * @return the transport
   */
  public HttpTransport transport() {
    return transport;
  }

  /**
   * The GraphQL query API: services, endpoints, instances, native traces, logs, topology.
   *
   * @return the query client
   */
  public SkyWalkingQueryClient query() {
    return query;
  }

  /**
   * SPEC {@code TraceQueryClient} (Zipkin store for OTLP spans, native store for agents) with the
   * configured {@link SkyWalkingConfig#traceBackend()}.
   *
   * @return the trace query client
   */
  public SkyWalkingTraceQueryClient traces() {
    return traces;
  }

  /**
   * SPEC {@code LogQueryClient}.
   *
   * @return the log query client
   */
  public SkyWalkingLogQueryClient logs() {
    return logs;
  }

  /**
   * Registers a resource closed with the client.
   *
   * @param resource the resource
   */
  public void register(AutoCloseable resource) {
    synchronized (resources) {
      resources.add(resource);
    }
  }

  /**
   * Liveness: {@code GET /healthcheck} (OAP health checker, {@code SW_HEALTH_CHECKER=default}): 2xx
   * → ok, other statuses → unhealthy. Without the health checker (404/405) falls back to GraphQL
   * {@code checkHealth} (score 0 = ok). Never throws for an unhealthy or unreachable service (only
   * {@link CancelledException} and {@link ConfigException}).
   *
   * @return the health status
   */
  public HealthStatus health() {
    return HttpTransport.await(healthAsync(), "health");
  }

  /**
   * Asynchronous {@link #health()}.
   *
   * @return a future with the health status
   */
  public CompletableFuture<HealthStatus> healthAsync() {
    TransportRequest req =
        TransportRequest.get("/healthcheck")
            .operation("health")
            .acceptAnyStatus()
            .maxRetries(0)
            .build();
    return transport
        .sendAsync(req)
        .thenCompose(this::healthFrom)
        .handle(
            (h, err) -> {
              if (err == null) {
                return h;
              }
              SkyWalkingException e = HttpTransport.asSdkException(err, "health");
              if (e instanceof CancelledException || e instanceof ConfigException) {
                throw e;
              }
              if (e.httpStatus().isPresent()) {
                Map<String, String> d = new LinkedHashMap<>();
                d.put("httpStatus", Integer.toString(e.httpStatus().getAsInt()));
                d.put("error", e.code().wireValue());
                return new HealthStatus(false, "unhealthy", d);
              }
              return HealthStatus.unreachable(String.valueOf(e.getMessage()));
            });
  }

  private CompletableFuture<HealthStatus> healthFrom(TransportResponse r) {
    int status = r.status();
    if (status >= 200 && status < 300) {
      return CompletableFuture.completedFuture(HealthStatus.ok());
    }
    if (status != 404 && status != 405) {
      return CompletableFuture.completedFuture(
          new HealthStatus(false, "unhealthy", Map.of("httpStatus", Integer.toString(status))));
    }
    return api.field("{checkHealth{score details}}", Map.of(), "checkHealth", "health")
        .thenApply(
            check -> {
              JsonNode score = check.get("score");
              if (score != null && score.isNumber() && score.asInt() == 0) {
                return HealthStatus.ok();
              }
              Map<String, String> d = new LinkedHashMap<>();
              d.put("score", score != null && score.isNumber() ? score.asText() : "-1");
              String details = JsonFields.str(check, "details");
              if (!details.isEmpty()) {
                d.put("details", api.redact(details));
              }
              return new HealthStatus(false, "unhealthy", d);
            });
  }

  /**
   * Timed GraphQL round trip ({@code getTimeInfo}; also caches the OAP time zone). OAP has no query
   * authentication: an authentication error only comes from a gateway in front of it.
   *
   * @return the latency
   */
  public Duration ping() {
    return HttpTransport.await(pingAsync(), "ping");
  }

  /**
   * Asynchronous {@link #ping()}.
   *
   * @return a future with the latency
   */
  public CompletableFuture<Duration> pingAsync() {
    long start = System.nanoTime();
    return api.timeInfo().thenApply(t -> Duration.ofNanos(System.nanoTime() - start));
  }

  /**
   * The OAP version from GraphQL {@code {version}}, for example {@code 10.4.0-b272da3}.
   *
   * @return the version string
   * @throws UnsupportedException when the server reports none
   */
  public String serverVersion() {
    return HttpTransport.await(serverVersionAsync(), "serverVersion");
  }

  /**
   * Asynchronous {@link #serverVersion()}.
   *
   * @return a future with the version
   */
  public CompletableFuture<String> serverVersionAsync() {
    return api.field("{version}", Map.of(), "version", "serverVersion")
        .thenApply(
            v -> {
              if (!v.isTextual() || v.asText().isEmpty()) {
                throw new UnsupportedException(
                    "serverVersion: the service does not report a version");
              }
              return v.asText();
            });
  }

  /**
   * Raw GraphQL (same as {@code query().graphql}).
   *
   * @param query GraphQL document
   * @param variables variables (may be null)
   * @return the response
   */
  public GraphQLResponse graphql(String query, Map<String, ?> variables) {
    return this.query.graphql(query, variables);
  }

  /**
   * Raw GraphQL without variables.
   *
   * @param query GraphQL document
   * @return the response
   */
  public GraphQLResponse graphql(String query) {
    return this.query.graphql(query);
  }

  /**
   * Asynchronous {@link #graphql(String, Map)}.
   *
   * @param query GraphQL document
   * @param variables variables (may be null)
   * @return a future with the response
   */
  public CompletableFuture<GraphQLResponse> graphqlAsync(String query, Map<String, ?> variables) {
    return this.query.graphqlAsync(query, variables);
  }

  /**
   * A {@link BatchingLogClient} that sends native logs ({@code POST /v3/logs}) over this client's
   * connections. {@link #close()} flushes and closes it.
   *
   * @param options service name, instance, resource and batch settings
   * @return the log client
   */
  public BatchingLogClient logClient(SkyWalkingLogClientOptions options) {
    BatchingLogClient c = logClient(transport, options, false);
    register(c);
    return c;
  }

  /**
   * A log client for one service with default batching.
   *
   * @param serviceName {@code service.name} of the records
   * @return the log client
   */
  public BatchingLogClient logClient(String serviceName) {
    return logClient(SkyWalkingLogClientOptions.forService(serviceName));
  }

  /**
   * {@link SkyWalkingTracing#initTracing(TracingConfig, SkyWalkingConfig)} with this client's
   * configuration (OTLP endpoint, protocol, headers, token, TLS). {@link #close()} shuts the handle
   * down.
   *
   * @param tracing tracing config
   * @return the handle
   */
  public TracingHandle initTracing(TracingConfig tracing) {
    TracingHandle h = SkyWalkingTracing.initTracing(tracing, config);
    register(h);
    return h;
  }

  /**
   * Returns whether {@link #close()} was called.
   *
   * @return true once closed
   */
  public boolean isClosed() {
    return closed.get();
  }

  /**
   * Flushes and closes log clients and tracing handles created here, then releases the connections.
   * Idempotent.
   */
  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    List<AutoCloseable> copy;
    synchronized (resources) {
      copy = new ArrayList<>(resources);
      resources.clear();
    }
    for (AutoCloseable r : copy) {
      try {
        r.close();
      } catch (Exception ignored) {
        // best effort: close must not throw
      }
    }
    transport.close();
  }

  @Override
  public String toString() {
    return "SkyWalkingClient{" + config + "}";
  }

  /** Builder that configures and creates a client in one chain. */
  public static final class Builder {
    private final SkyWalkingConfig.Builder config = SkyWalkingConfig.builder();

    private Builder() {}

    /**
     * Fills unset fields from an environment reader.
     *
     * @param env reader, e.g. {@link EnvReader#system()}
     * @return this builder
     */
    public Builder fromEnv(EnvReader env) {
      config.fromEnv(env);
      return this;
    }

    /**
     * Sets the REST/GraphQL base URL.
     *
     * @param url URL
     * @return this builder
     */
    public Builder url(String url) {
      config.url(url);
      return this;
    }

    /**
     * Sets the GraphQL path.
     *
     * @param path path
     * @return this builder
     */
    public Builder graphqlPath(String path) {
      config.graphqlPath(path);
      return this;
    }

    /**
     * Sets the OTLP endpoint.
     *
     * @param endpoint URL
     * @return this builder
     */
    public Builder otlpEndpoint(String endpoint) {
      config.otlpEndpoint(endpoint);
      return this;
    }

    /**
     * Sets the OTLP protocol.
     *
     * @param protocol protocol
     * @return this builder
     */
    public Builder otlpProtocol(OtlpProtocol protocol) {
      config.otlpProtocol(protocol);
      return this;
    }

    /**
     * Adds an OTLP exporter header.
     *
     * @param name name
     * @param value value
     * @return this builder
     */
    public Builder otlpHeader(String name, String value) {
      config.otlpHeader(name, value);
      return this;
    }

    /**
     * Sets the Zipkin query API base.
     *
     * @param url URL
     * @return this builder
     */
    public Builder zipkinUrl(String url) {
      config.zipkinUrl(url);
      return this;
    }

    /**
     * Sets the OAP gRPC token.
     *
     * @param token token
     * @return this builder
     */
    public Builder authentication(String token) {
      config.authentication(token);
      return this;
    }

    /**
     * Sets the OAP gRPC token.
     *
     * @param token token
     * @return this builder
     */
    public Builder authentication(Secret token) {
      config.authentication(token);
      return this;
    }

    /**
     * Sets the trace backend.
     *
     * @param backend backend
     * @return this builder
     */
    public Builder traceBackend(TraceBackend backend) {
      config.traceBackend(backend);
      return this;
    }

    /**
     * Sets the connect timeout.
     *
     * @param d duration
     * @return this builder
     */
    public Builder connectTimeout(Duration d) {
      config.connectTimeout(d);
      return this;
    }

    /**
     * Sets the per-attempt timeout.
     *
     * @param d duration
     * @return this builder
     */
    public Builder requestTimeout(Duration d) {
      config.requestTimeout(d);
      return this;
    }

    /**
     * Sets the total timeout.
     *
     * @param d duration
     * @return this builder
     */
    public Builder totalTimeout(Duration d) {
      config.totalTimeout(d);
      return this;
    }

    /**
     * Sets the retry policy.
     *
     * @param retry policy
     * @return this builder
     */
    public Builder retry(RetryPolicy retry) {
      config.retry(retry);
      return this;
    }

    /**
     * Sets the TLS options.
     *
     * @param tls options
     * @return this builder
     */
    public Builder tls(TlsOptions tls) {
      config.tls(tls);
      return this;
    }

    /**
     * Sets the proxy URL.
     *
     * @param proxy URL
     * @return this builder
     */
    public Builder proxy(String proxy) {
      config.proxy(proxy);
      return this;
    }

    /**
     * Adds a header.
     *
     * @param name name
     * @param value value
     * @return this builder
     */
    public Builder header(String name, String value) {
      config.header(name, value);
      return this;
    }

    /**
     * Sets the user agent suffix.
     *
     * @param suffix suffix
     * @return this builder
     */
    public Builder userAgentSuffix(String suffix) {
      config.userAgentSuffix(suffix);
      return this;
    }

    /**
     * Sets hooks.
     *
     * @param hooks hooks
     * @return this builder
     */
    public Builder hooks(Hooks hooks) {
      config.hooks(hooks);
      return this;
    }

    /**
     * Sets the logger.
     *
     * @param logger logger
     * @return this builder
     */
    public Builder logger(Logger logger) {
      config.logger(logger);
      return this;
    }

    /**
     * Builds the configuration and the client.
     *
     * @return a new client
     * @throws ConfigException when the configuration is invalid
     */
    public SkyWalkingClient build() {
      return create(config.build());
    }
  }
}
