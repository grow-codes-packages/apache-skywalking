package codes.grow.packages.skywalking;

import codes.grow.packages.skywalking.core.Redaction;
import codes.grow.packages.skywalking.core.SkyWalkingException;
import codes.grow.packages.skywalking.core.UnsupportedException;
import codes.grow.packages.skywalking.core.ValidationException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Result of a raw GraphQL call ({@link SkyWalkingClient#graphql}): partial {@code data} is possible
 * next to {@code errors}.
 *
 * @param data the {@code data} object, or empty when absent or null
 * @param errors the GraphQL errors, empty on success
 */
public record GraphQLResponse(Optional<JsonNode> data, List<GraphQLErrorInfo> errors) {
  /**
   * Normalises nulls.
   *
   * @param data data
   * @param errors errors
   */
  public GraphQLResponse {
    data = data == null ? Optional.empty() : data;
    errors = errors == null ? List.of() : List.copyOf(errors);
  }

  /**
   * Decodes a response body.
   *
   * @param body the JSON body
   * @return the response
   */
  public static GraphQLResponse fromBody(JsonNode body) {
    JsonNode d = body == null ? null : body.get("data");
    return new GraphQLResponse(
        d != null && d.isObject() ? Optional.of(d) : Optional.empty(),
        GraphQLErrorInfo.listFrom(body));
  }

  /**
   * Whether the server reported errors.
   *
   * @return true when {@link #errors()} is not empty
   */
  public boolean hasErrors() {
    return !errors.isEmpty();
  }

  /**
   * Returns one field of {@code data}.
   *
   * @param name field name
   * @return the field, or empty when absent or null
   */
  public Optional<JsonNode> field(String name) {
    return data.map(d -> d.get(name)).filter(n -> !n.isNull());
  }

  /**
   * Throws the SDK error for {@link #errors()} (nothing when there are none).
   *
   * @param operation operation name for the message
   * @throws SkyWalkingException see {@link #toException}
   */
  public void throwIfErrors(String operation) {
    if (hasErrors()) {
      throw toException(operation, errors, UnaryOperator.identity());
    }
  }

  /**
   * Maps GraphQL errors to an SDK error: an {@code UnsupportedOperationException} on the server →
   * {@link UnsupportedException}, anything else → {@link ValidationException} (bad input caught by
   * the resolver). The message is redacted and truncated to 300 characters.
   *
   * @param operation operation name
   * @param errors the errors (not empty)
   * @param redact redaction of known secrets
   * @return the exception
   */
  public static SkyWalkingException toException(
      String operation, List<GraphQLErrorInfo> errors, UnaryOperator<String> redact) {
    String raw =
        errors.stream()
            .map(GraphQLErrorInfo::message)
            .filter(m -> !m.isEmpty())
            .findFirst()
            .orElse("GraphQL error");
    String text =
        Redaction.truncate(
            redact.apply(raw.replaceAll("\\s+", " ").trim()), Redaction.MAX_VENDOR_TEXT);
    if (raw.contains("UnsupportedOperationException")) {
      return new UnsupportedException(operation + ": " + text);
    }
    return new ValidationException(operation + ": " + text);
  }
}
