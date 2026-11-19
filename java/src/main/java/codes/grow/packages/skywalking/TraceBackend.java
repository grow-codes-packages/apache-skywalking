package codes.grow.packages.skywalking;

import codes.grow.packages.skywalking.core.ConfigException;
import java.util.Locale;

/**
 * Trace store read by {@link SkyWalkingTraceQueryClient} ({@link SkyWalkingConfig#traceBackend()}).
 *
 * <p>OAP keeps OTLP spans in its Zipkin model (read through the Zipkin query API, 32-hex ids) and
 * SkyWalking agent segments in its native model (read through GraphQL, opaque ids such as {@code
 * <hex>.<n>.<ms>}).
 */
public enum TraceBackend {
  /** Hex ids via Zipkin first, then GraphQL; other ids via GraphQL; listings merge both stores. */
  AUTO("auto"),
  /** Only the Zipkin query API (spans exported over OTLP). */
  ZIPKIN("zipkin"),
  /** Only native SkyWalking traces through GraphQL (agents, {@code /v3/segments}). */
  NATIVE("native");

  private final String wire;

  TraceBackend(String wire) {
    this.wire = wire;
  }

  /**
   * Wire value.
   *
   * @return {@code auto}, {@code zipkin} or {@code native}
   */
  public String wireValue() {
    return wire;
  }

  /**
   * Parses a wire value (case-insensitive).
   *
   * @param text {@code auto}, {@code zipkin} or {@code native}
   * @param source variable or field name for the error message (never the value)
   * @return the backend
   * @throws ConfigException for any other value
   */
  public static TraceBackend parse(String text, String source) {
    String t = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    for (TraceBackend b : values()) {
      if (b.wire.equals(t)) {
        return b;
      }
    }
    throw new ConfigException(
        "config: invalid value in " + source + " (expected auto, zipkin or native)");
  }
}
