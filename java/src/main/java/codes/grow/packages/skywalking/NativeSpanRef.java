package codes.grow.packages.skywalking;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Cross-segment parent reference of a native span.
 *
 * @param traceId native trace id
 * @param parentSegmentId segment of the parent span
 * @param parentSpanId parent span id within that segment
 * @param type {@code CROSS_PROCESS} or {@code CROSS_THREAD}
 */
public record NativeSpanRef(String traceId, String parentSegmentId, int parentSpanId, String type) {
  static NativeSpanRef fromJson(JsonNode r) {
    return new NativeSpanRef(
        JsonFields.str(r, "traceId"),
        JsonFields.str(r, "parentSegmentId"),
        (int) JsonFields.num(r, "parentSpanId", 0),
        JsonFields.str(r, "type"));
  }
}
