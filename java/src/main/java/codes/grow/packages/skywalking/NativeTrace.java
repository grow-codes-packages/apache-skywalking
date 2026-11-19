package codes.grow.packages.skywalking;

import codes.grow.packages.skywalking.tracing.query.Trace;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Optional;

/**
 * A native SkyWalking trace: the spans of every segment.
 *
 * @param traceId native trace id (verbatim)
 * @param spans spans as GraphQL returned them
 */
public record NativeTrace(String traceId, List<NativeSpan> spans) {
  /**
   * Copies the spans.
   *
   * @param traceId trace id
   * @param spans spans
   */
  public NativeTrace {
    spans = spans == null ? List.of() : List.copyOf(spans);
  }

  /**
   * Decodes a JSON span list; empty when there are no spans.
   *
   * @param spans JSON array (may be null)
   * @return the trace, or empty
   */
  static Optional<NativeTrace> fromSpans(JsonNode spans) {
    List<NativeSpan> list = JsonFields.objects(spans, NativeSpan::fromJson);
    return list.isEmpty()
        ? Optional.empty()
        : Optional.of(new NativeTrace(list.get(0).traceId(), list));
  }

  /**
   * Converts to the SPEC model (spans sorted; see {@link NativeSpan#toSpan()}).
   *
   * @return the trace, or empty when it has no spans
   */
  public Optional<Trace> toTrace() {
    if (spans.isEmpty()) {
      return Optional.empty();
    }
    String id = traceId.isEmpty() ? spans.get(0).traceId() : traceId;
    return Optional.of(new Trace(id, spans.stream().map(NativeSpan::toSpan).toList()));
  }
}
