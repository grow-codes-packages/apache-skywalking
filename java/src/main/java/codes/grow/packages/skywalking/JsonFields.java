package codes.grow.packages.skywalking;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Lenient JSON accessors for GraphQL and Zipkin responses (missing fields get neutral values). */
final class JsonFields {
  private JsonFields() {}

  static String str(JsonNode node, String field) {
    JsonNode v = node == null ? null : node.get(field);
    return v != null && v.isTextual() ? v.asText() : "";
  }

  static long num(JsonNode node, String field, long fallback) {
    JsonNode v = node == null ? null : node.get(field);
    return v != null && v.isNumber() ? v.asLong() : fallback;
  }

  static boolean bool(JsonNode node, String field) {
    JsonNode v = node == null ? null : node.get(field);
    return v != null && v.isBoolean() && v.asBoolean();
  }

  static List<String> strings(JsonNode node, String field) {
    JsonNode v = node == null ? null : node.get(field);
    return strings(v);
  }

  static List<String> strings(JsonNode v) {
    List<String> out = new ArrayList<>();
    if (v != null && v.isArray()) {
      for (JsonNode item : v) {
        if (item.isTextual()) {
          out.add(item.asText());
        }
      }
    }
    return out;
  }

  static <T> List<T> objects(JsonNode v, Function<JsonNode, T> decode) {
    List<T> out = new ArrayList<>();
    if (v != null && v.isArray()) {
      for (JsonNode item : v) {
        if (item.isObject()) {
          out.add(decode.apply(item));
        }
      }
    }
    return out;
  }

  static <T> List<T> objects(JsonNode node, String field, Function<JsonNode, T> decode) {
    return objects(node == null ? null : node.get(field), decode);
  }
}
