package codes.grow.packages.skywalking;

import codes.grow.packages.skywalking.core.ConfigException;
import codes.grow.packages.skywalking.tracing.TracingConfig;
import java.util.Locale;

/**
 * OTLP protocol of the trace exporter. OAP 10.4 serves OTLP over gRPC only; {@link #HTTP_PROTOBUF}
 * is for an OpenTelemetry Collector in front of OAP.
 */
public enum OtlpProtocol {
  /** OTLP/gRPC (default, OAP port 11800). */
  GRPC("grpc"),
  /** OTLP/HTTP with protobuf bodies (collector only). */
  HTTP_PROTOBUF("http/protobuf");

  private final String wire;

  OtlpProtocol(String wire) {
    this.wire = wire;
  }

  /**
   * Wire value.
   *
   * @return {@code grpc} or {@code http/protobuf}
   */
  public String wireValue() {
    return wire;
  }

  /**
   * The matching {@link TracingConfig.Protocol}.
   *
   * @return the tracing protocol
   */
  public TracingConfig.Protocol toTracingProtocol() {
    return this == GRPC ? TracingConfig.Protocol.GRPC : TracingConfig.Protocol.HTTP_PROTOBUF;
  }

  /**
   * Parses a wire value (case-insensitive).
   *
   * @param text {@code grpc} or {@code http/protobuf}
   * @param source variable or field name for the error message (never the value)
   * @return the protocol
   * @throws ConfigException for any other value
   */
  public static OtlpProtocol parse(String text, String source) {
    String t = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    for (OtlpProtocol p : values()) {
      if (p.wire.equals(t)) {
        return p;
      }
    }
    throw new ConfigException(
        "config: invalid value in " + source + " (expected grpc or http/protobuf)");
  }
}
