package codes.grow.packages.skywalking;

import codes.grow.packages.skywalking.core.ConfigException;
import codes.grow.packages.skywalking.core.EnvReader;
import codes.grow.packages.skywalking.core.Hooks;
import codes.grow.packages.skywalking.core.Logger;
import codes.grow.packages.skywalking.core.Redaction;
import codes.grow.packages.skywalking.core.RetryPolicy;
import codes.grow.packages.skywalking.core.Secret;
import codes.grow.packages.skywalking.core.TlsOptions;
import codes.grow.packages.skywalking.core.TransportConfig;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Immutable configuration of a {@link SkyWalkingClient} (SPEC section 2.2): the OAP REST/GraphQL
 * endpoint (port 12800), the OTLP endpoint used by {@link SkyWalkingTracing#initTracing} (gRPC,
 * port 11800), the Zipkin query API where OAP stores OTLP spans (port 9412) and the common
 * transport settings.
 *
 * <p>Build one with {@link #builder()} or read it from the environment with {@link #fromEnv()}.
 * Precedence: explicit builder value &gt; {@code GROW_APACHE_SKYWALKING_<NAME>} &gt; vendor
 * variables ({@code GROW_SKYWALKING_<NAME>}, then the SkyWalking/OpenTelemetry names) &gt; default.
 * {@link #toString()} never prints credentials.
 *
 * <p>OAP has no query authentication: GraphQL and the native {@code /v3} HTTP API are open. When
 * OAP runs behind an authenticating gateway, put its credentials in {@link Builder#header}.
 */
public final class SkyWalkingConfig {
  /** Default REST/GraphQL endpoint. */
  public static final String DEFAULT_URL = "http://127.0.0.1:12800";

  /** Default GraphQL path below the URL. */
  public static final String DEFAULT_GRAPHQL_PATH = "/graphql";

  /** OAP gRPC port (OTLP receiver). */
  public static final int OTLP_GRPC_PORT = 11800;

  /** OAP Zipkin query port ({@code SW_QUERY_ZIPKIN}). */
  public static final int ZIPKIN_QUERY_PORT = 9412;

  /** Short prefix of the ticket, accepted as a vendor alias after the SDK prefix. */
  static final String SHORT_PREFIX = "GROW_SKYWALKING_";

  /** Vendor variables for {@code url}, after {@code GROW_APACHE_SKYWALKING_URL}. */
  static final String[] URL_VENDOR_VARS = {
    SHORT_PREFIX + "URL", "SW_GRAPHQL_URL", "SW_OAP_ADDRESS"
  };

  /** Vendor variables for {@code otlpEndpoint}. */
  static final String[] OTLP_ENDPOINT_VENDOR_VARS = {
    SHORT_PREFIX + "OTLP_ENDPOINT",
    "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT",
    "OTEL_EXPORTER_OTLP_ENDPOINT",
    "SW_AGENT_COLLECTOR_BACKEND_SERVICES"
  };

  /** Vendor variables for {@code otlpProtocol}. */
  static final String[] OTLP_PROTOCOL_VENDOR_VARS = {
    SHORT_PREFIX + "OTLP_PROTOCOL",
    "OTEL_EXPORTER_OTLP_TRACES_PROTOCOL",
    "OTEL_EXPORTER_OTLP_PROTOCOL"
  };

  /** Vendor variables for {@code otlpHeaders}. */
  static final String[] OTLP_HEADERS_VENDOR_VARS = {
    "OTEL_EXPORTER_OTLP_TRACES_HEADERS", "OTEL_EXPORTER_OTLP_HEADERS"
  };

  /** Vendor variables for {@code authentication}. */
  static final String[] AUTHENTICATION_VENDOR_VARS = {
    SHORT_PREFIX + "AUTHENTICATION", "SW_AUTHENTICATION", "SW_AGENT_AUTHENTICATION"
  };

  /** Vendor variables for {@code zipkinUrl}. */
  static final String[] ZIPKIN_URL_VENDOR_VARS = {SHORT_PREFIX + "ZIPKIN_URL", "SW_ZIPKIN_ADDRESS"};

  private static final String COLLECTOR_VAR = "SW_AGENT_COLLECTOR_BACKEND_SERVICES";
  private static final Pattern SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*://.*");
  private static final Pattern GRAPHQL_SUFFIX = Pattern.compile("(?i)/graphql$");
  private static final Pattern ZIPKIN_SUFFIX = Pattern.compile("(?i)/zipkin$");

  private final String url;
  private final String graphqlPath;
  private final String otlpEndpoint;
  private final OtlpProtocol otlpProtocol;
  private final Map<String, String> otlpHeaders;
  private final String zipkinUrl;
  private final Secret authentication;
  private final TraceBackend traceBackend;
  private final Duration connectTimeout;
  private final Duration requestTimeout;
  private final Duration totalTimeout;
  private final RetryPolicy retry;
  private final TlsOptions tls;
  private final String proxy;
  private final Map<String, String> headers;
  private final String userAgentSuffix;
  private final Hooks hooks;
  private final Logger logger;

  private SkyWalkingConfig(Builder b) {
    this.url = baseFromGraphqlUrl(b.url != null ? b.url : DEFAULT_URL);
    this.graphqlPath = b.graphqlPath != null ? b.graphqlPath : DEFAULT_GRAPHQL_PATH;
    this.otlpEndpoint =
        stripSlash(b.otlpEndpoint != null ? b.otlpEndpoint : hostUrl(url, OTLP_GRPC_PORT, ""));
    this.otlpProtocol = b.otlpProtocol != null ? b.otlpProtocol : OtlpProtocol.GRPC;
    Map<String, String> oh = new LinkedHashMap<>();
    b.otlpHeaders.forEach((k, v) -> oh.put(k.toLowerCase(Locale.ROOT), v));
    this.otlpHeaders = Collections.unmodifiableMap(oh);
    this.zipkinUrl =
        stripSlash(b.zipkinUrl != null ? b.zipkinUrl : hostUrl(url, ZIPKIN_QUERY_PORT, "/zipkin"));
    this.authentication = b.authentication;
    this.traceBackend = b.traceBackend != null ? b.traceBackend : TraceBackend.AUTO;
    this.connectTimeout = b.connectTimeout != null ? b.connectTimeout : Duration.ofSeconds(10);
    this.requestTimeout = b.requestTimeout != null ? b.requestTimeout : Duration.ofSeconds(30);
    this.totalTimeout = b.totalTimeout != null ? b.totalTimeout : Duration.ofSeconds(120);
    this.retry = b.retry != null ? b.retry : RetryPolicy.defaults();
    this.tls = b.tls != null ? b.tls : TlsOptions.defaults();
    this.proxy = b.proxy;
    this.headers = Map.copyOf(b.headers);
    this.userAgentSuffix = b.userAgentSuffix;
    this.hooks = b.hooks != null ? b.hooks : Hooks.NONE;
    this.logger = b.logger != null ? b.logger : Logger.NOOP;
  }

  /**
   * Starts an empty builder (every field at its default).
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Reads the configuration from the process environment.
   *
   * @return the validated configuration
   * @throws ConfigException when a variable holds an invalid value (the message names the variable,
   *     never the value)
   */
  public static SkyWalkingConfig fromEnv() {
    return builder().fromEnv(EnvReader.system()).build();
  }

  /**
   * Reads the configuration from an explicit variable map (tests).
   *
   * @param env variable map
   * @return the validated configuration
   */
  public static SkyWalkingConfig fromEnv(Map<String, String> env) {
    return builder().fromEnv(EnvReader.of(env)).build();
  }

  /**
   * Strips a trailing {@code /graphql} (and slashes) from a GraphQL URL: {@code
   * http://oap:12800/graphql} → {@code http://oap:12800}.
   *
   * @param url URL
   * @return the base URL
   */
  public static String baseFromGraphqlUrl(String url) {
    return stripSlash(GRAPHQL_SUFFIX.matcher(stripSlash(url)).replaceAll(""));
  }

  /**
   * Turns a SkyWalking agent collector address ({@code host:port[,host:port]}) into an OTLP
   * endpoint URL ({@code http://host:port}, first entry).
   *
   * @param value collector address
   * @return the endpoint URL
   */
  public static String collectorToUrl(String value) {
    String first = value.split(",", -1)[0].trim();
    return SCHEME.matcher(first).matches() ? first : "http://" + first;
  }

  /**
   * Appends {@code /zipkin} to a Zipkin address that does not end with it.
   *
   * @param value Zipkin address, e.g. {@code http://oap:9412}
   * @return the query API base, e.g. {@code http://oap:9412/zipkin}
   */
  public static String zipkinQueryUrl(String value) {
    String base = stripSlash(value);
    return ZIPKIN_SUFFIX.matcher(base).find() ? base : base + "/zipkin";
  }

  /**
   * Parses OpenTelemetry-style headers {@code k1=v1,k2=v2} (keys lower-cased, values URL-decoded).
   *
   * @param raw header list
   * @param source variable name for the error message (never the value)
   * @return the headers
   * @throws ConfigException when an entry is malformed
   */
  public static Map<String, String> parseOtlpHeaders(String raw, String source) {
    Map<String, String> out = new LinkedHashMap<>();
    for (String part : raw.split(",", -1)) {
      if (part.isBlank()) {
        continue;
      }
      int eq = part.indexOf('=');
      String key = eq <= 0 ? "" : part.substring(0, eq).trim();
      if (key.isEmpty()) {
        throw new ConfigException("config: invalid value in " + source + " (expected key=value)");
      }
      String value;
      try {
        value = URLDecoder.decode(part.substring(eq + 1).trim(), StandardCharsets.UTF_8);
      } catch (IllegalArgumentException e) {
        throw new ConfigException("config: invalid value in " + source + " (bad percent-encoding)");
      }
      out.put(key.toLowerCase(Locale.ROOT), value);
    }
    return out;
  }

  private static String stripSlash(String url) {
    String s = url;
    while (s.endsWith("/")) {
      s = s.substring(0, s.length() - 1);
    }
    return s;
  }

  private static String hostUrl(String base, int port, String path) {
    try {
      URI u = new URI(base);
      if (u.getHost() != null && u.getScheme() != null) {
        return u.getScheme().toLowerCase(Locale.ROOT) + "://" + u.getHost() + ":" + port + path;
      }
    } catch (URISyntaxException e) {
      // validate() reports the bad URL
    }
    return "http://127.0.0.1:" + port + path;
  }

  /**
   * Checks the URLs, protocol, backend, positive timeouts, TLS pairs and the proxy.
   *
   * @throws ConfigException when a rule is violated
   */
  public void validate() {
    checkHttpUrl(url, "url");
    checkHttpUrl(otlpEndpoint, "otlpEndpoint");
    checkHttpUrl(zipkinUrl, "zipkinUrl");
    if (!graphqlPath.startsWith("/")) {
      throw new ConfigException("config: graphqlPath must start with /");
    }
    if (authentication != null && authentication.reveal().isBlank()) {
      throw new ConfigException("config: authentication must not be blank");
    }
    requirePositive("connectTimeout", connectTimeout);
    requirePositive("requestTimeout", requestTimeout);
    requirePositive("totalTimeout", totalTimeout);
    tls.validate();
    if (proxy != null) {
      try {
        URI p = new URI(proxy);
        if (p.getHost() == null) {
          throw new ConfigException("config: proxy must be an absolute URL");
        }
      } catch (URISyntaxException e) {
        throw new ConfigException("config: proxy is not a valid URL");
      }
    }
  }

  private static void checkHttpUrl(String value, String field) {
    URI u;
    try {
      u = new URI(value);
    } catch (URISyntaxException e) {
      throw new ConfigException("config: " + field + " is not a valid URL");
    }
    String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
    if (!(scheme.equals("http") || scheme.equals("https")) || u.getHost() == null) {
      throw new ConfigException("config: " + field + " must use http or https and include a host");
    }
  }

  private static void requirePositive(String name, Duration d) {
    if (d.isZero() || d.isNegative()) {
      throw new ConfigException("config: " + name + " must be positive");
    }
  }

  /**
   * Translates the common fields into transport settings.
   *
   * @return the transport configuration
   */
  public TransportConfig toTransportConfig() {
    return TransportConfig.builder(url)
        .connectTimeout(connectTimeout)
        .requestTimeout(requestTimeout)
        .totalTimeout(totalTimeout)
        .retry(retry)
        .tls(tls)
        .proxy(proxy)
        .headers(headers)
        .userAgentSuffix(userAgentSuffix)
        .hooks(hooks)
        .logger(logger)
        .knownSecrets(this::knownSecrets)
        .build();
  }

  /**
   * Credential values to redact from error messages and logs: the authentication token and the
   * values of sensitive OTLP headers.
   *
   * @return the secret values
   */
  public List<String> knownSecrets() {
    List<String> out = new ArrayList<>();
    if (authentication != null && !authentication.isCleared()) {
      out.add(authentication.reveal());
    }
    otlpHeaders.forEach(
        (k, v) -> {
          if (Redaction.isSensitiveHeader(k) || k.equals("authentication")) {
            out.add(v);
          }
        });
    return out;
  }

  /**
   * Returns the REST/GraphQL base URL (port 12800, no trailing {@code /graphql}).
   *
   * @return the URL
   */
  public String url() {
    return url;
  }

  /**
   * Returns the GraphQL path (default {@code /graphql}).
   *
   * @return the path
   */
  public String graphqlPath() {
    return graphqlPath;
  }

  /**
   * Returns the absolute GraphQL URL.
   *
   * @return {@code url + graphqlPath}
   */
  public String graphqlUrl() {
    return url + graphqlPath;
  }

  /**
   * Returns the OTLP endpoint of {@link SkyWalkingTracing#initTracing} (default {@code http://<url
   * host>:11800}).
   *
   * @return the endpoint
   */
  public String otlpEndpoint() {
    return otlpEndpoint;
  }

  /**
   * Returns the OTLP protocol (default {@link OtlpProtocol#GRPC}).
   *
   * @return the protocol
   */
  public OtlpProtocol otlpProtocol() {
    return otlpProtocol;
  }

  /**
   * Returns the extra OTLP exporter headers (gRPC metadata), keys lower-case.
   *
   * @return immutable map
   */
  public Map<String, String> otlpHeaders() {
    return otlpHeaders;
  }

  /**
   * Returns the Zipkin query API base (default {@code http://<url host>:9412/zipkin}).
   *
   * @return the URL (ends with {@code /zipkin} unless configured otherwise)
   */
  public String zipkinUrl() {
    return zipkinUrl;
  }

  /**
   * Returns the OAP gRPC token ({@code SW_AUTHENTICATION}), sent as the {@code authentication}
   * metadata on OTLP exports. OAP does not protect GraphQL or {@code /v3} with it.
   *
   * @return the token, or empty
   */
  public Optional<Secret> authentication() {
    return Optional.ofNullable(authentication);
  }

  /**
   * Returns the trace store read by {@link SkyWalkingClient#traces()} (default {@link
   * TraceBackend#AUTO}).
   *
   * @return the backend
   */
  public TraceBackend traceBackend() {
    return traceBackend;
  }

  /**
   * Returns the connect timeout.
   *
   * @return the timeout
   */
  public Duration connectTimeout() {
    return connectTimeout;
  }

  /**
   * Returns the per-attempt timeout.
   *
   * @return the timeout
   */
  public Duration requestTimeout() {
    return requestTimeout;
  }

  /**
   * Returns the total timeout per call including retries.
   *
   * @return the timeout
   */
  public Duration totalTimeout() {
    return totalTimeout;
  }

  /**
   * Returns the retry policy.
   *
   * @return the policy
   */
  public RetryPolicy retry() {
    return retry;
  }

  /**
   * Returns the TLS options.
   *
   * @return the options
   */
  public TlsOptions tls() {
    return tls;
  }

  /**
   * Returns the explicit proxy URL.
   *
   * @return the proxy, or empty
   */
  public Optional<String> proxy() {
    return Optional.ofNullable(proxy);
  }

  /**
   * Returns the extra request headers.
   *
   * @return immutable map
   */
  public Map<String, String> headers() {
    return headers;
  }

  /**
   * Returns the user agent suffix.
   *
   * @return the suffix, or empty
   */
  public Optional<String> userAgentSuffix() {
    return Optional.ofNullable(userAgentSuffix);
  }

  /**
   * Returns the hooks.
   *
   * @return the hooks
   */
  public Hooks hooks() {
    return hooks;
  }

  /**
   * Returns the logger.
   *
   * @return the logger
   */
  public Logger logger() {
    return logger;
  }

  /**
   * Returns a builder holding these values.
   *
   * @return builder copy
   */
  public Builder toBuilder() {
    Builder b =
        builder()
            .url(url)
            .graphqlPath(graphqlPath)
            .otlpEndpoint(otlpEndpoint)
            .otlpProtocol(otlpProtocol)
            .otlpHeaders(otlpHeaders)
            .zipkinUrl(zipkinUrl)
            .authentication(authentication)
            .traceBackend(traceBackend)
            .connectTimeout(connectTimeout)
            .requestTimeout(requestTimeout)
            .totalTimeout(totalTimeout)
            .retry(retry)
            .tls(tls)
            .proxy(proxy)
            .userAgentSuffix(userAgentSuffix)
            .hooks(hooks)
            .logger(logger);
    headers.forEach(b::header);
    return b;
  }

  @Override
  public String toString() {
    Map<String, String> oh = new LinkedHashMap<>();
    otlpHeaders.keySet().forEach(k -> oh.put(k, Redaction.REDACTED));
    return "SkyWalkingConfig{url="
        + Redaction.redactUrl(url)
        + ", graphqlPath="
        + graphqlPath
        + ", otlpEndpoint="
        + Redaction.redactUrl(otlpEndpoint)
        + ", otlpProtocol="
        + otlpProtocol.wireValue()
        + ", otlpHeaders="
        + oh
        + ", zipkinUrl="
        + Redaction.redactUrl(zipkinUrl)
        + ", authentication="
        + (authentication == null ? "null" : Redaction.REDACTED)
        + ", traceBackend="
        + traceBackend.wireValue()
        + ", connectTimeout="
        + connectTimeout
        + ", requestTimeout="
        + requestTimeout
        + ", totalTimeout="
        + totalTimeout
        + ", retry="
        + retry
        + ", tls="
        + tls
        + ", proxy="
        + (proxy == null ? "null" : Redaction.redactUrl(proxy))
        + ", headers="
        + Redaction.redactHeaders(headers)
        + ", userAgentSuffix="
        + userAgentSuffix
        + "}";
  }

  /**
   * Builder of the configuration.
   *
   * <p>Unset fields take the environment (when {@link #fromEnv(EnvReader)} is called) or the
   * default.
   */
  public static final class Builder {
    private String url;
    private String graphqlPath;
    private String otlpEndpoint;
    private OtlpProtocol otlpProtocol;
    private final Map<String, String> otlpHeaders = new LinkedHashMap<>();
    private String zipkinUrl;
    private Secret authentication;
    private TraceBackend traceBackend;
    private Duration connectTimeout;
    private Duration requestTimeout;
    private Duration totalTimeout;
    private RetryPolicy retry;
    private TlsOptions tls;
    private String proxy;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private String userAgentSuffix;
    private Hooks hooks;
    private Logger logger;

    private Builder() {}

    /**
     * Fills every field that was not set explicitly from the environment: {@code url} ({@code URL},
     * {@code GROW_SKYWALKING_URL}, {@code SW_GRAPHQL_URL} without {@code /graphql}, {@code
     * SW_OAP_ADDRESS}), {@code GRAPHQL_PATH}, {@code otlpEndpoint} ({@code OTLP_ENDPOINT}, {@code
     * OTEL_EXPORTER_OTLP_TRACES_ENDPOINT}, {@code OTEL_EXPORTER_OTLP_ENDPOINT}, {@code
     * SW_AGENT_COLLECTOR_BACKEND_SERVICES} as {@code http://host:port}), {@code otlpProtocol},
     * {@code otlpHeaders} ({@code OTEL_EXPORTER_OTLP_[TRACES_]HEADERS}), {@code authentication}
     * ({@code AUTHENTICATION}, {@code SW_AUTHENTICATION}, {@code SW_AGENT_AUTHENTICATION}), {@code
     * zipkinUrl} ({@code ZIPKIN_URL}, {@code SW_ZIPKIN_ADDRESS} plus {@code /zipkin}), {@code
     * TRACE_BACKEND} and the common timeout, retry, TLS and proxy variables.
     *
     * @param env environment reader
     * @return this builder
     * @throws ConfigException when a variable holds an invalid value
     */
    public Builder fromEnv(EnvReader env) {
      if (url == null) {
        url = env.string("URL", URL_VENDOR_VARS).orElse(null);
      }
      if (graphqlPath == null) {
        graphqlPath = env.string("GRAPHQL_PATH").orElse(null);
      }
      if (otlpEndpoint == null) {
        Optional<String> v = env.string("OTLP_ENDPOINT", OTLP_ENDPOINT_VENDOR_VARS);
        if (v.isPresent()) {
          String source = env.source("OTLP_ENDPOINT", OTLP_ENDPOINT_VENDOR_VARS).orElse("");
          otlpEndpoint = COLLECTOR_VAR.equals(source) ? collectorToUrl(v.get()) : v.get().trim();
        }
      }
      if (otlpProtocol == null) {
        Optional<String> v = env.string("OTLP_PROTOCOL", OTLP_PROTOCOL_VENDOR_VARS);
        if (v.isPresent()) {
          String source = env.source("OTLP_PROTOCOL", OTLP_PROTOCOL_VENDOR_VARS).orElse("");
          otlpProtocol = OtlpProtocol.parse(v.get(), source);
        }
      }
      if (otlpHeaders.isEmpty()) {
        Optional<String> v = env.string("OTLP_HEADERS", OTLP_HEADERS_VENDOR_VARS);
        if (v.isPresent()) {
          String source = env.source("OTLP_HEADERS", OTLP_HEADERS_VENDOR_VARS).orElse("");
          otlpHeaders.putAll(parseOtlpHeaders(v.get(), source));
        }
      }
      if (authentication == null) {
        authentication =
            env.string("AUTHENTICATION", AUTHENTICATION_VENDOR_VARS).map(Secret::of).orElse(null);
      }
      if (zipkinUrl == null) {
        zipkinUrl =
            env.string("ZIPKIN_URL", ZIPKIN_URL_VENDOR_VARS)
                .map(SkyWalkingConfig::zipkinQueryUrl)
                .orElse(null);
      }
      if (traceBackend == null) {
        String[] vendor = {SHORT_PREFIX + "TRACE_BACKEND"};
        Optional<String> v = env.string("TRACE_BACKEND", vendor);
        if (v.isPresent()) {
          traceBackend =
              TraceBackend.parse(v.get(), env.source("TRACE_BACKEND", vendor).orElse(""));
        }
      }
      if (connectTimeout == null) {
        connectTimeout = env.duration("CONNECT_TIMEOUT").orElse(null);
      }
      if (requestTimeout == null) {
        requestTimeout = env.duration("REQUEST_TIMEOUT").orElse(null);
      }
      if (totalTimeout == null) {
        totalTimeout = env.duration("TOTAL_TIMEOUT").orElse(null);
      }
      if (retry == null && env.integer("MAX_RETRIES").isPresent()) {
        int n = env.integer("MAX_RETRIES").getAsInt();
        if (n < 0) {
          String variable = EnvReader.DEFAULT_PREFIX + "MAX_RETRIES";
          throw new ConfigException("config: invalid value in " + variable + " (expected >= 0)");
        }
        retry = RetryPolicy.builder().maxRetries(n).build();
      }
      if (tls == null) {
        TlsOptions.Builder t = TlsOptions.builder();
        env.string("CA_CERT", SHORT_PREFIX + "CA_CERT").ifPresent(p -> t.caCertPath(Paths.get(p)));
        env.string("CLIENT_CERT", SHORT_PREFIX + "CLIENT_CERT")
            .ifPresent(p -> t.clientCertPath(Paths.get(p)));
        env.string("CLIENT_KEY", SHORT_PREFIX + "CLIENT_KEY")
            .ifPresent(p -> t.clientKeyPath(Paths.get(p)));
        env.bool("INSECURE_SKIP_VERIFY", SHORT_PREFIX + "INSECURE_SKIP_VERIFY")
            .ifPresent(t::insecureSkipVerify);
        tls = t.build();
      }
      if (proxy == null) {
        proxy = env.string("PROXY", SHORT_PREFIX + "PROXY").orElse(null);
      }
      return this;
    }

    /**
     * Sets the REST/GraphQL base URL (default {@code http://127.0.0.1:12800}); a trailing {@code
     * /graphql} is stripped.
     *
     * @param url {@code http} or {@code https} URL
     * @return this builder
     */
    public Builder url(String url) {
      this.url = url;
      return this;
    }

    /**
     * Sets the GraphQL path (default {@code /graphql}).
     *
     * @param graphqlPath path starting with {@code /}
     * @return this builder
     */
    public Builder graphqlPath(String graphqlPath) {
      this.graphqlPath = graphqlPath;
      return this;
    }

    /**
     * Sets the OTLP endpoint (default {@code http://<url host>:11800}). Never point it at port
     * 12800: OAP 10.4 serves OTLP over gRPC only.
     *
     * @param otlpEndpoint {@code http} or {@code https} URL
     * @return this builder
     */
    public Builder otlpEndpoint(String otlpEndpoint) {
      this.otlpEndpoint = otlpEndpoint;
      return this;
    }

    /**
     * Sets the OTLP protocol (default {@link OtlpProtocol#GRPC}).
     *
     * @param otlpProtocol protocol
     * @return this builder
     */
    public Builder otlpProtocol(OtlpProtocol otlpProtocol) {
      this.otlpProtocol = otlpProtocol;
      return this;
    }

    /**
     * Adds an OTLP exporter header (gRPC metadata).
     *
     * @param name header name (lower-cased)
     * @param value header value
     * @return this builder
     */
    public Builder otlpHeader(String name, String value) {
      this.otlpHeaders.put(name, value);
      return this;
    }

    /**
     * Replaces the OTLP exporter headers.
     *
     * @param otlpHeaders headers
     * @return this builder
     */
    public Builder otlpHeaders(Map<String, String> otlpHeaders) {
      this.otlpHeaders.clear();
      this.otlpHeaders.putAll(otlpHeaders);
      return this;
    }

    /**
     * Sets the Zipkin query API base (default {@code http://<url host>:9412/zipkin}).
     *
     * @param zipkinUrl {@code http} or {@code https} URL
     * @return this builder
     */
    public Builder zipkinUrl(String zipkinUrl) {
      this.zipkinUrl = zipkinUrl;
      return this;
    }

    /**
     * Sets the OAP gRPC token ({@code SW_AUTHENTICATION}).
     *
     * @param authentication token
     * @return this builder
     */
    public Builder authentication(Secret authentication) {
      this.authentication = authentication;
      return this;
    }

    /**
     * Sets the OAP gRPC token ({@code SW_AUTHENTICATION}).
     *
     * @param authentication token
     * @return this builder
     */
    public Builder authentication(String authentication) {
      this.authentication = authentication == null ? null : Secret.of(authentication);
      return this;
    }

    /**
     * Sets the trace store read by {@link SkyWalkingClient#traces()} (default {@link
     * TraceBackend#AUTO}).
     *
     * @param traceBackend backend
     * @return this builder
     */
    public Builder traceBackend(TraceBackend traceBackend) {
      this.traceBackend = traceBackend;
      return this;
    }

    /**
     * Sets the connect timeout (default 10 s).
     *
     * @param d positive duration
     * @return this builder
     */
    public Builder connectTimeout(Duration d) {
      this.connectTimeout = d;
      return this;
    }

    /**
     * Sets the per-attempt timeout (default 30 s).
     *
     * @param d positive duration
     * @return this builder
     */
    public Builder requestTimeout(Duration d) {
      this.requestTimeout = d;
      return this;
    }

    /**
     * Sets the total timeout per call including retries (default 120 s).
     *
     * @param d positive duration
     * @return this builder
     */
    public Builder totalTimeout(Duration d) {
      this.totalTimeout = d;
      return this;
    }

    /**
     * Sets the retry policy (default {@link RetryPolicy#defaults()}).
     *
     * @param retry policy
     * @return this builder
     */
    public Builder retry(RetryPolicy retry) {
      this.retry = retry;
      return this;
    }

    /**
     * Sets the TLS options; environment TLS variables are then ignored.
     *
     * @param tls options
     * @return this builder
     */
    public Builder tls(TlsOptions tls) {
      this.tls = tls;
      return this;
    }

    /**
     * Sets an explicit proxy URL; environment proxies ({@code HTTPS_PROXY}) are never used.
     *
     * @param proxy proxy URL
     * @return this builder
     */
    public Builder proxy(String proxy) {
      this.proxy = proxy;
      return this;
    }

    /**
     * Adds a header sent with every HTTP request (for example gateway credentials).
     *
     * @param name header name
     * @param value header value
     * @return this builder
     */
    public Builder header(String name, String value) {
      this.headers.put(name, value);
      return this;
    }

    /**
     * Sets the text appended to the {@code User-Agent}.
     *
     * @param suffix suffix
     * @return this builder
     */
    public Builder userAgentSuffix(String suffix) {
      this.userAgentSuffix = suffix;
      return this;
    }

    /**
     * Sets request hooks.
     *
     * @param hooks hooks
     * @return this builder
     */
    public Builder hooks(Hooks hooks) {
      this.hooks = hooks;
      return this;
    }

    /**
     * Sets the logger (default silent).
     *
     * @param logger logger
     * @return this builder
     */
    public Builder logger(Logger logger) {
      this.logger = logger;
      return this;
    }

    /**
     * Builds and validates the configuration.
     *
     * @return the configuration
     * @throws ConfigException when a rule is violated
     */
    public SkyWalkingConfig build() {
      SkyWalkingConfig c = new SkyWalkingConfig(this);
      c.validate();
      return c;
    }
  }
}
