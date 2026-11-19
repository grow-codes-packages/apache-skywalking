package codes.grow.packages.skywalking;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * A SkyWalking {@code {key, value}} pair (span tags, log tags, span log data); order and duplicates
 * are kept.
 *
 * @param key key
 * @param value value
 */
public record KeyValue(String key, String value) {
  /**
   * Normalises nulls to empty strings.
   *
   * @param key key
   * @param value value
   */
  public KeyValue {
    key = key == null ? "" : key;
    value = value == null ? "" : value;
  }

  /**
   * Decodes a {@code [{key, value}]} list (non-string values are rendered as JSON, null as {@code
   * ""}).
   *
   * @param node JSON array (may be null)
   * @return the pairs
   */
  public static List<KeyValue> listFrom(JsonNode node) {
    return JsonFields.objects(
        node,
        kv -> {
          JsonNode v = kv.get("value");
          String value = v == null || v.isNull() ? "" : v.isTextual() ? v.asText() : v.toString();
          return new KeyValue(JsonFields.str(kv, "key"), value);
        });
  }
}
