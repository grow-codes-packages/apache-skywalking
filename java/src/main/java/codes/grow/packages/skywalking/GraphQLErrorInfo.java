package codes.grow.packages.skywalking;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One entry of a GraphQL {@code errors} array.
 *
 * @param message server message
 * @param path field path the error belongs to (resolver errors; strings and indexes)
 * @param classification graphql-java classification ({@code ValidationError}, {@code
 *     DataFetchingException}, ...)
 */
public record GraphQLErrorInfo(String message, List<Object> path, Optional<String> classification) {
  /**
   * Copies the path and normalises nulls.
   *
   * @param message message
   * @param path path
   * @param classification classification
   */
  public GraphQLErrorInfo {
    message = message == null ? "" : message;
    path = path == null ? List.of() : List.copyOf(path);
    classification = classification == null ? Optional.empty() : classification;
  }

  /**
   * Decodes the {@code errors} array of a GraphQL response body.
   *
   * @param body response body (may be null)
   * @return the errors, empty when there are none
   */
  public static List<GraphQLErrorInfo> listFrom(JsonNode body) {
    List<GraphQLErrorInfo> out = new ArrayList<>();
    JsonNode errors = body == null ? null : body.get("errors");
    if (errors == null || !errors.isArray()) {
      return out;
    }
    for (JsonNode e : errors) {
      if (!e.isObject()) {
        continue;
      }
      JsonNode m = e.get("message");
      List<Object> path = new ArrayList<>();
      JsonNode p = e.get("path");
      if (p != null && p.isArray()) {
        for (JsonNode item : p) {
          path.add(item.isNumber() ? (Object) item.asInt() : item.asText());
        }
      }
      JsonNode ext = e.get("extensions");
      JsonNode cls = ext == null ? null : ext.get("classification");
      out.add(
          new GraphQLErrorInfo(
              m != null && m.isTextual() ? m.asText() : "",
              path,
              cls != null && cls.isTextual() ? Optional.of(cls.asText()) : Optional.empty()));
    }
    return out;
  }
}
