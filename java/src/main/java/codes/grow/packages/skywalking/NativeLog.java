package codes.grow.packages.skywalking;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Optional;

/**
 * A log record as OAP stores it (GraphQL {@code queryLogs}).
 *
 * @param serviceName service name
 * @param serviceId service id
 * @param serviceInstanceName instance name, may be empty
 * @param endpointName endpoint name, may be empty
 * @param traceId native or hex trace id; {@code ""} when the log has no trace context
 * @param timestamp epoch milliseconds
 * @param contentType {@code TEXT}, {@code JSON} or {@code YAML}
 * @param content log body
 * @param tags tags
 */
public record NativeLog(
    String serviceName,
    String serviceId,
    String serviceInstanceName,
    String endpointName,
    String traceId,
    long timestamp,
    String contentType,
    String content,
    List<KeyValue> tags) {
  /** GraphQL selection of a log. */
  static final String FIELDS =
      "serviceName serviceId serviceInstanceName endpointName traceId timestamp contentType "
          + "content tags{key value}";

  /**
   * Copies the tags.
   *
   * @param serviceName service
   * @param serviceId service id
   * @param serviceInstanceName instance
   * @param endpointName endpoint
   * @param traceId trace id
   * @param timestamp time
   * @param contentType content type
   * @param content content
   * @param tags tags
   */
  public NativeLog {
    tags = tags == null ? List.of() : List.copyOf(tags);
  }

  /**
   * Decodes one log (missing fields get neutral defaults).
   *
   * @param l JSON object
   * @return the log
   */
  public static NativeLog fromJson(JsonNode l) {
    return new NativeLog(
        JsonFields.str(l, "serviceName"),
        JsonFields.str(l, "serviceId"),
        JsonFields.str(l, "serviceInstanceName"),
        JsonFields.str(l, "endpointName"),
        JsonFields.str(l, "traceId"),
        JsonFields.num(l, "timestamp", 0),
        JsonFields.str(l, "contentType"),
        JsonFields.str(l, "content"),
        KeyValue.listFrom(l.get("tags")));
  }

  /**
   * The first value of a tag.
   *
   * @param key tag key
   * @return the value, or empty
   */
  public Optional<String> tag(String key) {
    return tags.stream().filter(t -> t.key().equals(key)).map(KeyValue::value).findFirst();
  }
}
