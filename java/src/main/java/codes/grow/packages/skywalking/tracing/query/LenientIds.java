package codes.grow.packages.skywalking.tracing.query;

import codes.grow.packages.skywalking.core.ValidationException;
import java.util.regex.Pattern;

/**
 * Id normalisation of the trace model for Apache SkyWalking (DECISIONS section 2, SPEC exception):
 * hex ids (OTLP, Zipkin) are normalised with {@link TraceIds}; native SkyWalking ids, which are
 * opaque strings such as {@code <hex>.<n>.<ms>} (trace) or {@code <segmentId>-<spanId>} (span), are
 * kept verbatim (trimmed).
 */
final class LenientIds {
  private static final Pattern HEX_LIKE = Pattern.compile("(?i)^(0x)?[0-9a-f]+$");

  private LenientIds() {}

  static String traceId(String id) {
    return normalize(id, true);
  }

  static String spanId(String id) {
    return normalize(id, false);
  }

  private static String normalize(String id, boolean trace) {
    String what = trace ? "trace id" : "span id";
    if (id == null) {
      throw new ValidationException(what + " must not be null");
    }
    String t = id.trim();
    if (HEX_LIKE.matcher(t).matches()) {
      return trace ? TraceIds.normalizeTraceId(t) : TraceIds.normalizeSpanId(t);
    }
    if (t.isEmpty()) {
      throw new ValidationException(what + " must not be empty");
    }
    return t;
  }
}
