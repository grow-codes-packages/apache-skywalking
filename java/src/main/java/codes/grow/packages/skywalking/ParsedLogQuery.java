package codes.grow.packages.skywalking;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parsed form of a {@link SkyWalkingLogQueryClient} query: space-separated {@code key=value} terms
 * ({@code service=}, {@code instance=}, {@code endpoint=}, {@code trace_id=}/{@code traceId=} map
 * to the GraphQL condition; any other key is a server tag filter and must be in {@code
 * SW_SEARCHABLE_LOGS_TAG_KEYS}) plus free-text terms ({@code "quoted phrases"} allowed). Every
 * free-text term must occur (case-insensitive) in the content or in a tag value.
 *
 * @param service service name, or empty
 * @param instance instance name, or empty
 * @param endpoint endpoint name, or empty
 * @param traceId related trace id, or empty
 * @param tags server-side tag filters
 * @param text free-text terms, matched client side
 */
public record ParsedLogQuery(
    Optional<String> service,
    Optional<String> instance,
    Optional<String> endpoint,
    Optional<String> traceId,
    Map<String, String> tags,
    List<String> text) {
  private static final Pattern TOKEN =
      Pattern.compile("([A-Za-z_][\\w.-]*)=(?:\"([^\"]*)\"|(\\S+))|\"([^\"]*)\"|(\\S+)");

  /**
   * Copies collections and normalises nulls.
   *
   * @param service service
   * @param instance instance
   * @param endpoint endpoint
   * @param traceId trace id
   * @param tags tags
   * @param text text terms
   */
  public ParsedLogQuery {
    service = service == null ? Optional.empty() : service;
    instance = instance == null ? Optional.empty() : instance;
    endpoint = endpoint == null ? Optional.empty() : endpoint;
    traceId = traceId == null ? Optional.empty() : traceId;
    tags = tags == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(tags));
    text = text == null ? List.of() : List.copyOf(text);
  }

  /**
   * Parses a query (see the record description).
   *
   * @param query query text (null = everything)
   * @return the parsed query
   */
  public static ParsedLogQuery parse(String query) {
    String service = null;
    String instance = null;
    String endpoint = null;
    String traceId = null;
    Map<String, String> tags = new LinkedHashMap<>();
    List<String> text = new ArrayList<>();
    Matcher m = TOKEN.matcher(query == null ? "" : query);
    while (m.find()) {
      String key = m.group(1);
      if (key != null) {
        String value = m.group(2) != null ? m.group(2) : m.group(3);
        switch (key) {
          case "service":
            service = value;
            break;
          case "instance":
            instance = value;
            break;
          case "endpoint":
            endpoint = value;
            break;
          case "trace_id":
          case "traceId":
            traceId = value;
            break;
          default:
            tags.put(key, value);
            break;
        }
      } else {
        String t = m.group(4) != null ? m.group(4) : m.group(5);
        if (!t.isEmpty()) {
          text.add(t);
        }
      }
    }
    return new ParsedLogQuery(
        Optional.ofNullable(service),
        Optional.ofNullable(instance),
        Optional.ofNullable(endpoint),
        Optional.ofNullable(traceId),
        tags,
        text);
  }

  /**
   * Whether every free-text term occurs (case-insensitive) in the log content or a tag value.
   *
   * @param log stored log
   * @return true when the log matches
   */
  public boolean matchesText(NativeLog log) {
    if (text.isEmpty()) {
      return true;
    }
    StringBuilder sb = new StringBuilder(log.content());
    for (KeyValue kv : log.tags()) {
      sb.append('\n').append(kv.value());
    }
    String haystack = sb.toString().toLowerCase(Locale.ROOT);
    return text.stream().allMatch(t -> haystack.contains(t.toLowerCase(Locale.ROOT)));
  }
}
