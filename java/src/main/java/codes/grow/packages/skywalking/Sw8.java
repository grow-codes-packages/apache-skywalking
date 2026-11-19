package codes.grow.packages.skywalking;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * SkyWalking cross-process propagation headers {@code sw8} (v3) and {@code sw8-correlation}:
 * encoding, decoding and the context helpers used by {@link Sw8Propagator}.
 *
 * <p>{@code sw8} = {@code sample-b64(traceId)-b64(parentSegment)-parentSpanId-b64(service)-
 * b64(instance)-b64(endpoint)-b64(peer)} (8 fields, standard Base64 with padding); {@code
 * sw8-correlation} = {@code b64(key):b64(value),...}.
 */
public final class Sw8 {
  /** Header name of the SkyWalking v3 trace context. */
  public static final String HEADER = "sw8";

  /** Header name of the SkyWalking correlation context. */
  public static final String CORRELATION_HEADER = "sw8-correlation";

  /** Propagator name for {@code TracingConfig} propagators. */
  public static final String PROPAGATOR = "sw8";

  private static final Pattern B64 =
      Pattern.compile("^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$");
  private static final Pattern INT = Pattern.compile("^-?\\d+$");

  static final ContextKey<Sw8Context> EXTRACTED = ContextKey.named("grow-codes skywalking sw8");
  static final ContextKey<Sw8Context> INJECT = ContextKey.named("grow-codes skywalking sw8 inject");
  static final ContextKey<Map<String, String>> CORRELATION =
      ContextKey.named("grow-codes skywalking sw8 correlation");

  private Sw8() {}

  private static String enc(String text) {
    return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
  }

  private static String dec(String text) {
    if (!B64.matcher(text).matches()) {
      return null;
    }
    return new String(Base64.getDecoder().decode(text), StandardCharsets.UTF_8);
  }

  /**
   * Encodes an {@code sw8} header value.
   *
   * @param ctx context
   * @return the header value
   */
  public static String encode(Sw8Context ctx) {
    return String.join(
        "-",
        ctx.sample() ? "1" : "0",
        enc(ctx.traceId()),
        enc(ctx.parentTraceSegmentId()),
        Integer.toString(ctx.parentSpanId()),
        enc(ctx.parentService()),
        enc(ctx.parentServiceInstance()),
        enc(ctx.parentEndpoint()),
        enc(ctx.networkAddressUsedAtPeer()));
  }

  /**
   * Decodes an {@code sw8} header value.
   *
   * @param header header value (may be null)
   * @return the context, or empty when the header is malformed (wrong field count, bad Base64,
   *     sample not 0/1, non-integer span id, empty trace or segment id)
   */
  public static Optional<Sw8Context> decode(String header) {
    String[] parts = (header == null ? "" : header.trim()).split("-", -1);
    if (parts.length != 8) {
      return Optional.empty();
    }
    if (!parts[0].equals("0") && !parts[0].equals("1")) {
      return Optional.empty();
    }
    if (!INT.matcher(parts[3]).matches()) {
      return Optional.empty();
    }
    int spanId;
    try {
      spanId = Integer.parseInt(parts[3]);
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
    String[] d = new String[8];
    for (int i : new int[] {1, 2, 4, 5, 6, 7}) {
      d[i] = dec(parts[i]);
      if (d[i] == null) {
        return Optional.empty();
      }
    }
    if (d[1].isEmpty() || d[2].isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        new Sw8Context(parts[0].equals("1"), d[1], d[2], spanId, d[4], d[5], d[6], d[7]));
  }

  /**
   * Encodes {@code sw8-correlation} in map order.
   *
   * @param entries key/value pairs
   * @return the header value
   */
  public static String encodeCorrelation(Map<String, String> entries) {
    List<String> parts = new ArrayList<>();
    entries.forEach((k, v) -> parts.add(enc(k) + ":" + enc(v)));
    return String.join(",", parts);
  }

  /**
   * Decodes {@code sw8-correlation}; malformed entries are skipped.
   *
   * @param header header value (may be null)
   * @return the entries in header order
   */
  public static Map<String, String> decodeCorrelation(String header) {
    Map<String, String> out = new LinkedHashMap<>();
    for (String part : (header == null ? "" : header).split(",", -1)) {
      String[] kv = part.trim().split(":", -1);
      if (kv.length != 2) {
        continue;
      }
      String key = dec(kv[0]);
      String value = dec(kv[1]);
      if (key == null || value == null || key.isEmpty()) {
        continue;
      }
      out.put(key, value);
    }
    return out;
  }

  /**
   * The {@code sw8} context extracted from an incoming request by {@link Sw8Propagator}.
   *
   * @param ctx OpenTelemetry context
   * @return the extracted context, or empty
   */
  public static Optional<Sw8Context> fromContext(Context ctx) {
    return Optional.ofNullable(ctx.get(EXTRACTED));
  }

  /**
   * Returns a context whose outgoing requests carry this native metadata as {@code sw8}. The SDK
   * cannot derive native metadata from an OpenTelemetry span; supply it only when you bridge to
   * SkyWalking agents.
   *
   * @param ctx OpenTelemetry context
   * @param sw8 native metadata
   * @return the new context
   */
  public static Context withContext(Context ctx, Sw8Context sw8) {
    return ctx.with(INJECT, sw8);
  }

  /**
   * Like {@link #withContext(Context, Sw8Context)}, also writing {@code sw8-correlation}.
   *
   * @param ctx OpenTelemetry context
   * @param sw8 native metadata
   * @param correlation correlation entries (written as {@code sw8-correlation})
   * @return the new context
   */
  public static Context withContext(Context ctx, Sw8Context sw8, Map<String, String> correlation) {
    return ctx.with(INJECT, sw8)
        .with(CORRELATION, Collections.unmodifiableMap(new LinkedHashMap<>(correlation)));
  }

  /**
   * Span attributes describing an extracted parent ({@code sw8.parent.*}, {@code sw8.peer}).
   *
   * @param sw8 context
   * @return the attributes
   */
  public static Map<String, Object> attributes(Sw8Context sw8) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("sw8.parent.trace_id", sw8.traceId());
    out.put("sw8.parent.segment_id", sw8.parentTraceSegmentId());
    out.put("sw8.parent.span_id", (long) sw8.parentSpanId());
    out.put("sw8.parent.service", sw8.parentService());
    out.put("sw8.parent.service_instance", sw8.parentServiceInstance());
    out.put("sw8.parent.endpoint", sw8.parentEndpoint());
    out.put("sw8.peer", sw8.networkAddressUsedAtPeer());
    return out;
  }
}
