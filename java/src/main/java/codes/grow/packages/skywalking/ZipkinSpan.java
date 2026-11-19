package codes.grow.packages.skywalking;

import codes.grow.packages.skywalking.core.Json;
import codes.grow.packages.skywalking.core.ValidationException;
import codes.grow.packages.skywalking.tracing.query.Span;
import codes.grow.packages.skywalking.tracing.query.Trace;
import codes.grow.packages.skywalking.tracing.query.TraceIds;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A span as OAP's Zipkin query API returns it ({@code :9412/zipkin/api/v2}): the stored form of
 * spans exported over OTLP. OAP lower-cases span names on ingest and stores attribute values as
 * strings.
 *
 * @param traceId trace id (hex)
 * @param id span id (hex)
 * @param parentId parent span id, {@code ""} for roots
 * @param kind {@code CLIENT}, {@code SERVER}, {@code PRODUCER}, {@code CONSUMER} or {@code ""}
 *     (internal)
 * @param name span name (lower-cased by OAP)
 * @param timestamp start in epoch microseconds
 * @param duration duration in microseconds
 * @param localServiceName {@code localEndpoint.serviceName}
 * @param annotations events ({@code name|attributesJSON|droppedCount})
 * @param tags tags
 */
public record ZipkinSpan(
    String traceId,
    String id,
    String parentId,
    String kind,
    String name,
    long timestamp,
    long duration,
    String localServiceName,
    List<Annotation> annotations,
    Map<String, String> tags) {
  private static final Pattern HEX_ID = Pattern.compile("^(?:[0-9a-f]{16}|[0-9a-f]{32})$");
  private static final Pattern ZERO = Pattern.compile("^0+$");
  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

  /**
   * A Zipkin annotation.
   *
   * @param timestamp epoch microseconds
   * @param value value (OAP: {@code name|attributesJSON|droppedCount})
   */
  public record Annotation(long timestamp, String value) {}

  /**
   * Copies collections and normalises nulls.
   *
   * @param traceId trace id
   * @param id span id
   * @param parentId parent id
   * @param kind kind
   * @param name name
   * @param timestamp start
   * @param duration duration
   * @param localServiceName service
   * @param annotations annotations
   * @param tags tags
   */
  public ZipkinSpan {
    parentId = parentId == null ? "" : parentId;
    kind = kind == null ? "" : kind;
    name = name == null ? "" : name;
    localServiceName = localServiceName == null ? "" : localServiceName;
    annotations = annotations == null ? List.of() : List.copyOf(annotations);
    tags = tags == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(tags));
  }

  /**
   * Whether {@code id} is a 16- or 32-character hex id (OTLP/Zipkin), as opposed to a native
   * SkyWalking id.
   *
   * @param id trace id
   * @return true for hex ids
   */
  public static boolean isHexTraceId(String id) {
    return id != null && HEX_ID.matcher(id.trim().toLowerCase(Locale.ROOT)).matches();
  }

  /**
   * Lower-cases and validates a Zipkin trace id.
   *
   * @param id trace id
   * @param operation operation name for the error
   * @return the lower-case id
   * @throws ValidationException unless the id is 16 or 32 hex characters, not all zero
   */
  public static String zipkinTraceId(String id, String operation) {
    String lower = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
    if (!HEX_ID.matcher(lower).matches() || ZERO.matcher(lower).matches()) {
      throw new ValidationException(operation + ": trace id must be 16 or 32 hex characters");
    }
    return lower;
  }

  /**
   * Decodes a span; empty when {@code traceId} or {@code id} is missing.
   *
   * @param s JSON object
   * @return the span, or empty
   */
  public static Optional<ZipkinSpan> fromJson(JsonNode s) {
    if (s == null || !s.path("traceId").isTextual() || !s.path("id").isTextual()) {
      return Optional.empty();
    }
    List<Annotation> annotations = new ArrayList<>();
    for (JsonNode a : JsonFields.objects(s, "annotations", a -> a)) {
      annotations.add(
          new Annotation(JsonFields.num(a, "timestamp", 0), JsonFields.str(a, "value")));
    }
    Map<String, String> tags = new LinkedHashMap<>();
    JsonNode t = s.get("tags");
    if (t != null && t.isObject()) {
      t.properties().forEach(e -> tags.put(e.getKey(), e.getValue().asText()));
    }
    return Optional.of(
        new ZipkinSpan(
            s.get("traceId").asText(),
            s.get("id").asText(),
            JsonFields.str(s, "parentId"),
            JsonFields.str(s, "kind"),
            JsonFields.str(s, "name"),
            JsonFields.num(s, "timestamp", 0),
            JsonFields.num(s, "duration", 0),
            JsonFields.str(s.get("localEndpoint"), "serviceName"),
            annotations,
            tags));
  }

  /**
   * Decodes the well-formed spans of a {@code [span...]} body.
   *
   * @param body JSON array
   * @return the spans
   */
  public static List<ZipkinSpan> listFrom(JsonNode body) {
    List<ZipkinSpan> out = new ArrayList<>();
    if (body != null && body.isArray()) {
      for (JsonNode s : body) {
        fromJson(s).ifPresent(out::add);
      }
    }
    return out;
  }

  /**
   * Whether a tag is OAP bookkeeping ({@code otel.*}, {@code error}, {@code w3c.tracestate}); such
   * tags become status fields or are dropped from the attributes.
   *
   * @param key tag key
   * @return true for bookkeeping tags
   */
  public static boolean isBookkeepingTag(String key) {
    return key.startsWith("otel.") || key.equals("error") || key.equals("w3c.tracestate");
  }

  /**
   * Decodes an OAP annotation into an event: {@code name|{"k":"v"}|0} (split on the first and last
   * {@code |}) → name and attributes; any other value is the event name without attributes.
   *
   * @param a annotation
   * @return the event
   */
  public static Span.Event toEvent(Annotation a) {
    String value = a.value();
    int first = value.indexOf('|');
    int last = value.lastIndexOf('|');
    if (first > 0 && last > first) {
      String json = value.substring(first + 1, last);
      try {
        JsonNode parsed = Json.mapper().readTree(json.isEmpty() ? "{}" : json);
        if (parsed != null && parsed.isObject()) {
          Map<String, Object> attrs = Json.mapper().convertValue(parsed, MAP);
          return new Span.Event(value.substring(0, first), a.timestamp(), attrs);
        }
      } catch (Exception e) {
        // not the OAP form: the whole value is the name
      }
    }
    return new Span.Event(value, a.timestamp(), Map.of());
  }

  /**
   * Converts to the SPEC model: ids lower-case (trace id padded to 32), kind lower-case (absent →
   * internal, unknown → unspecified), service = {@code localEndpoint.serviceName}, status from
   * {@code otel.status_code} ({@code STATUS_CODE_ERROR} → error with {@code
   * otel.status_description}, {@code STATUS_CODE_OK} → ok, an {@code error} tag alone → error),
   * attributes = tags without the bookkeeping tags, annotations → events.
   *
   * @return the span
   */
  public Span toSpan() {
    Map<String, Object> attributes = new LinkedHashMap<>();
    tags.forEach(
        (k, v) -> {
          if (!isBookkeepingTag(k)) {
            attributes.put(k, v);
          }
        });
    Span.Builder b =
        Span.builder(TraceIds.normalizeTraceId(traceId), id.toLowerCase(Locale.ROOT), name)
            .kind(spanKind())
            .serviceName(localServiceName)
            .times(timestamp, timestamp + duration)
            .durationMicros(duration)
            .attributes(attributes);
    String code = tags.getOrDefault("otel.status_code", "").toUpperCase(Locale.ROOT);
    if (code.equals("STATUS_CODE_ERROR") || code.equals("ERROR")) {
      String message = tags.get("otel.status_description");
      b.status(Span.StatusCode.ERROR, message == null || message.isEmpty() ? null : message);
    } else if (code.equals("STATUS_CODE_OK") || code.equals("OK")) {
      b.status(Span.StatusCode.OK, null);
    } else if (tags.containsKey("error")) {
      b.status(Span.StatusCode.ERROR, null);
    }
    if (!parentId.isEmpty()) {
      b.parentSpanId(parentId.toLowerCase(Locale.ROOT));
    }
    for (Annotation a : annotations) {
      b.event(toEvent(a));
    }
    return b.build();
  }

  private Span.Kind spanKind() {
    switch (kind.toUpperCase(Locale.ROOT)) {
      case "":
        return Span.Kind.INTERNAL;
      case "SERVER":
        return Span.Kind.SERVER;
      case "CLIENT":
        return Span.Kind.CLIENT;
      case "PRODUCER":
        return Span.Kind.PRODUCER;
      case "CONSUMER":
        return Span.Kind.CONSUMER;
      default:
        return Span.Kind.UNSPECIFIED;
    }
  }

  /**
   * Converts the spans of one Zipkin trace into a SPEC trace (spans sorted).
   *
   * @param spans spans of one trace
   * @return the trace, or empty when there are no spans
   */
  public static Optional<Trace> toTrace(List<ZipkinSpan> spans) {
    if (spans.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        new Trace(
            TraceIds.normalizeTraceId(spans.get(0).traceId()),
            spans.stream().map(ZipkinSpan::toSpan).toList()));
  }
}
