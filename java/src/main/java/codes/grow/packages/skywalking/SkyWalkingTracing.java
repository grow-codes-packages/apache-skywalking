package codes.grow.packages.skywalking;

import codes.grow.packages.skywalking.core.ConfigException;
import codes.grow.packages.skywalking.tracing.OtlpExporters;
import codes.grow.packages.skywalking.tracing.SpanExporterFactory;
import codes.grow.packages.skywalking.tracing.Tracing;
import codes.grow.packages.skywalking.tracing.TracingConfig;
import codes.grow.packages.skywalking.tracing.TracingHandle;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * {@code initTracing} for SkyWalking: OpenTelemetry tracing that exports OTLP/gRPC to the OAP
 * receiver (port 11800). OAP stores OTLP spans in its Zipkin model; read them back with {@link
 * SkyWalkingClient#traces()} (zipkin or auto backend; the server needs {@code SW_QUERY_ZIPKIN})
 * about 5–15 s after export.
 *
 * <pre>{@code
 * try (TracingHandle tracing = SkyWalkingTracing.initTracing(
 *     TracingConfig.builder("checkout").build())) {
 *   Spans.withSpan("charge", () -> pay());
 * }
 * }</pre>
 *
 * <p>Teams that prefer full native SkyWalking traces (segments, sw8 between services, endpoint
 * metrics) use the official SkyWalking Java agent instead:
 * https://skywalking.apache.org/docs/skywalking-java/next/en/setup/service-agent/java-agent/readme/
 */
public final class SkyWalkingTracing {
  /** gRPC metadata key OAP reads the token from ({@code SW_AUTHENTICATION}). */
  public static final String AUTHENTICATION_HEADER = "authentication";

  /** Default OTLP endpoint when neither the tracing nor the SkyWalking config names one. */
  public static final String DEFAULT_OTLP_ENDPOINT =
      "http://127.0.0.1:" + SkyWalkingConfig.OTLP_GRPC_PORT;

  private SkyWalkingTracing() {}

  /**
   * Starts tracing with the SkyWalking settings from the environment ({@link
   * SkyWalkingConfig#fromEnv()}).
   *
   * @param config tracing config
   * @return the handle (close it to flush)
   * @throws ConfigException for invalid settings
   */
  public static TracingHandle initTracing(TracingConfig config) {
    return initTracing(config, SkyWalkingConfig.fromEnv());
  }

  /**
   * Starts tracing that exports to SkyWalking OAP. The SkyWalking settings fill what {@code config}
   * leaves open (see {@link #withDefaults}); the {@code sw8} propagator name is available (extract
   * by default; see {@link Sw8Propagator}).
   *
   * @param config tracing config
   * @param skywalking endpoint, protocol, headers, token and TLS source
   * @return the handle (close it to flush)
   * @throws ConfigException for invalid settings
   */
  public static TracingHandle initTracing(TracingConfig config, SkyWalkingConfig skywalking) {
    return Tracing.initWith(
        withDefaults(config, skywalking),
        exporterFactory(),
        Map.<String, TextMapPropagator>of(Sw8.PROPAGATOR, Sw8Propagator.getInstance()));
  }

  /**
   * A tracing config builder prefilled with the SkyWalking settings (endpoint, protocol, OTLP
   * headers, token, TLS); set further fields on it.
   *
   * @param serviceName {@code service.name}
   * @param skywalking SkyWalking settings
   * @return the builder
   */
  public static TracingConfig.Builder configBuilder(
      String serviceName, SkyWalkingConfig skywalking) {
    return withDefaults(TracingConfig.builder(serviceName).build(), skywalking).toBuilder();
  }

  /**
   * Fills the open fields of a tracing config from the SkyWalking settings: without an endpoint,
   * the endpoint <b>and</b> protocol become {@link SkyWalkingConfig#otlpEndpoint()} / {@link
   * SkyWalkingConfig#otlpProtocol()} (the {@link TracingConfig} protocol default http/protobuf is
   * then replaced); with an explicit endpoint the configured protocol is kept. OTLP headers and the
   * {@code authentication} token are added unless already present; the SkyWalking TLS options apply
   * when the tracing config has none.
   *
   * @param config tracing config
   * @param skywalking SkyWalking settings
   * @return the merged config
   */
  public static TracingConfig withDefaults(TracingConfig config, SkyWalkingConfig skywalking) {
    TracingConfig.Builder b = config.toBuilder();
    if (config.endpoint().isEmpty()) {
      b.endpoint(skywalking.otlpEndpoint()).protocol(skywalking.otlpProtocol().toTracingProtocol());
    }
    Map<String, String> headers = new LinkedHashMap<>(config.headers());
    skywalking.otlpHeaders().forEach((k, v) -> putIfAbsent(headers, k, v));
    skywalking
        .authentication()
        .ifPresent(a -> putIfAbsent(headers, AUTHENTICATION_HEADER, a.reveal()));
    b.headers(headers);
    if (config.tls().isEmpty() && skywalking.tls().isCustom()) {
      b.tls(skywalking.tls());
    }
    return b.build();
  }

  private static void putIfAbsent(Map<String, String> headers, String key, String value) {
    for (String k : headers.keySet()) {
      if (k.toLowerCase(Locale.ROOT).equals(key.toLowerCase(Locale.ROOT))) {
        return;
      }
    }
    headers.put(key, value);
  }

  /**
   * The SkyWalking span exporter factory: {@code grpc} → OTLP/gRPC (default endpoint {@value
   * #DEFAULT_OTLP_ENDPOINT}; headers become gRPC metadata); {@code http/protobuf} → OTLP/HTTP to
   * {@code <endpoint>/v1/traces} (only for an OpenTelemetry Collector in front of OAP — OAP 10.4
   * serves no OTLP/HTTP; an explicit endpoint is required); {@code native} → {@link
   * ConfigException} (use the official SkyWalking agents).
   *
   * @return the factory
   */
  public static SpanExporterFactory exporterFactory() {
    return SkyWalkingTracing::createSpanExporter;
  }

  /**
   * Creates the span exporter (see {@link #exporterFactory()}).
   *
   * @param config tracing config
   * @return the exporter
   * @throws ConfigException for the native protocol or http/protobuf without an endpoint
   */
  public static SpanExporter createSpanExporter(TracingConfig config) {
    switch (config.protocol()) {
      case NATIVE:
        throw new ConfigException(
            "tracing: the SkyWalking native segment protocol is not supported; use grpc (OTLP) or"
                + " the official SkyWalking agents");
      case HTTP_PROTOBUF:
        if (config.endpoint().isEmpty()) {
          throw new ConfigException(
              "tracing: http/protobuf needs an explicit endpoint (an OpenTelemetry Collector); OAP"
                  + " serves OTLP over gRPC only");
        }
        return OtlpExporters.create(config);
      default:
        return OtlpExporters.create(
            config.endpoint().isPresent()
                ? config
                : config.toBuilder().endpoint(DEFAULT_OTLP_ENDPOINT).build());
    }
  }
}
