package codes.grow.packages.skywalking;

import codes.grow.packages.skywalking.tracing.query.Span;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A span as SkyWalking stores it (agents, {@code /v3/segments}), read through GraphQL. Ids are
 * opaque strings; a span is identified by {@code (segmentId, spanId)}; {@code parentSpanId} −1
 * marks the segment root whose parent, if any, is {@code refs[0]}. Times are epoch milliseconds.
 *
 * @param traceId native trace id (for example {@code <hex>.<n>.<ms>})
 * @param segmentId segment id
 * @param spanId span id within the segment
 * @param parentSpanId parent within the segment, −1 for the segment root
 * @param refs cross-segment parents
 * @param serviceCode service name
 * @param serviceInstanceName instance name
 * @param startTime start (epoch ms)
 * @param endTime end (epoch ms)
 * @param endpointName endpoint (operation) name
 * @param type {@code Entry}, {@code Exit} or {@code Local}
 * @param peer remote address of exit spans
 * @param component component name
 * @param isError whether the span failed
 * @param layer layer ({@code Http}, {@code Database}, ...)
 * @param tags tags
 * @param logs span logs
 */
public record NativeSpan(
    String traceId,
    String segmentId,
    int spanId,
    int parentSpanId,
    List<NativeSpanRef> refs,
    String serviceCode,
    String serviceInstanceName,
    long startTime,
    long endTime,
    String endpointName,
    String type,
    String peer,
    String component,
    boolean isError,
    String layer,
    List<KeyValue> tags,
    List<NativeSpanLog> logs) {

  /** GraphQL selection of a native span. */
  public static final String FIELDS =
      "traceId segmentId spanId parentSpanId refs{traceId parentSegmentId parentSpanId type} "
          + "serviceCode serviceInstanceName startTime endTime endpointName type peer component "
          + "isError layer tags{key value} logs{time data{key value}}";

  /**
   * Copies the lists and normalises nulls.
   *
   * @param traceId trace id
   * @param segmentId segment id
   * @param spanId span id
   * @param parentSpanId parent span id
   * @param refs refs
   * @param serviceCode service
   * @param serviceInstanceName instance
   * @param startTime start
   * @param endTime end
   * @param endpointName endpoint
   * @param type type
   * @param peer peer
   * @param component component
   * @param isError error flag
   * @param layer layer
   * @param tags tags
   * @param logs logs
   */
  public NativeSpan {
    refs = refs == null ? List.of() : List.copyOf(refs);
    tags = tags == null ? List.of() : List.copyOf(tags);
    logs = logs == null ? List.of() : List.copyOf(logs);
  }

  /**
   * Decodes one span of a GraphQL response (missing fields get neutral defaults).
   *
   * @param s JSON object
   * @return the span
   */
  public static NativeSpan fromJson(JsonNode s) {
    return new NativeSpan(
        JsonFields.str(s, "traceId"),
        JsonFields.str(s, "segmentId"),
        (int) JsonFields.num(s, "spanId", 0),
        (int) JsonFields.num(s, "parentSpanId", -1),
        JsonFields.objects(s, "refs", NativeSpanRef::fromJson),
        JsonFields.str(s, "serviceCode"),
        JsonFields.str(s, "serviceInstanceName"),
        JsonFields.num(s, "startTime", 0),
        JsonFields.num(s, "endTime", 0),
        JsonFields.str(s, "endpointName"),
        JsonFields.str(s, "type"),
        JsonFields.str(s, "peer"),
        JsonFields.str(s, "component"),
        JsonFields.bool(s, "isError"),
        JsonFields.str(s, "layer"),
        KeyValue.listFrom(s.get("tags")),
        JsonFields.objects(
            s,
            "logs",
            l ->
                new NativeSpanLog(JsonFields.num(l, "time", 0), KeyValue.listFrom(l.get("data")))));
  }

  /**
   * The SPEC span id of a native span.
   *
   * @param segmentId segment id
   * @param spanId span id within the segment
   * @return {@code <segmentId>-<spanId>}
   */
  public static String spanKey(String segmentId, int spanId) {
    return segmentId + "-" + spanId;
  }

  /**
   * Converts to the SPEC model: trace id verbatim; span id {@code <segmentId>-<spanId>}; the parent
   * is the same segment's parent span when {@code parentSpanId >= 0}, else {@code refs[0]}; further
   * refs become links (attribute {@code sw.ref_type}); kind Entry → server, Exit → client, Local →
   * internal (other: unspecified); name = endpoint name; times ms → µs; status error when {@code
   * isError}; attributes = tags plus {@code sw.component}, {@code sw.layer}, {@code sw.peer},
   * {@code sw.service_instance} (when set); span logs become events (name = the {@code event}
   * value, else {@code log}).
   *
   * @return the span
   */
  public Span toSpan() {
    Map<String, Object> attributes = new LinkedHashMap<>();
    for (KeyValue kv : tags) {
      attributes.put(kv.key(), kv.value());
    }
    putIfSet(attributes, "sw.component", component);
    putIfSet(attributes, "sw.layer", layer);
    putIfSet(attributes, "sw.peer", peer);
    putIfSet(attributes, "sw.service_instance", serviceInstanceName);
    long start = startTime * 1000;
    long end = Math.max(endTime, startTime) * 1000;
    Span.Builder b =
        Span.builder(traceId, spanKey(segmentId, spanId), endpointName)
            .kind(kind(type))
            .serviceName(serviceCode)
            .times(start, end)
            .attributes(attributes);
    if (isError) {
      b.status(Span.StatusCode.ERROR, null);
    }
    int firstLink = 0;
    if (parentSpanId >= 0) {
      b.parentSpanId(spanKey(segmentId, parentSpanId));
    } else if (!refs.isEmpty()) {
      NativeSpanRef r = refs.get(0);
      b.parentSpanId(spanKey(r.parentSegmentId(), r.parentSpanId()));
      firstLink = 1;
    }
    for (NativeSpanRef r : refs.subList(firstLink, refs.size())) {
      b.link(
          new Span.Link(
              r.traceId(),
              spanKey(r.parentSegmentId(), r.parentSpanId()),
              Map.of("sw.ref_type", r.type())));
    }
    for (NativeSpanLog log : logs) {
      Map<String, Object> data = new LinkedHashMap<>();
      String name = null;
      for (KeyValue kv : log.data()) {
        data.put(kv.key(), kv.value());
        if (name == null && kv.key().equals("event")) {
          name = kv.value();
        }
      }
      b.event(new Span.Event(name == null ? "log" : name, log.time() * 1000, data));
    }
    return b.build();
  }

  private static void putIfSet(Map<String, Object> m, String key, String value) {
    if (!value.isEmpty()) {
      m.put(key, value);
    }
  }

  private static Span.Kind kind(String type) {
    switch (type) {
      case "Entry":
        return Span.Kind.SERVER;
      case "Exit":
        return Span.Kind.CLIENT;
      case "Local":
        return Span.Kind.INTERNAL;
      default:
        return Span.Kind.UNSPECIFIED;
    }
  }
}
