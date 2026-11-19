package codes.grow.packages.skywalking;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * A service known to OAP (native agents, native logs, OTLP logs).
 *
 * @param id {@code base64(name) + ".1"} for real services
 * @param name name
 * @param group group, may be empty
 * @param shortName short name, may be empty
 * @param layers layers ({@code GENERAL}, ...)
 * @param normal false for virtual (conjectured) services
 */
public record SkyWalkingService(
    String id, String name, String group, String shortName, List<String> layers, boolean normal) {
  /** GraphQL selection of a service. */
  static final String FIELDS = "id name group shortName layers normal";

  /**
   * Copies the layers.
   *
   * @param id id
   * @param name name
   * @param group group
   * @param shortName short name
   * @param layers layers
   * @param normal normal flag
   */
  public SkyWalkingService {
    layers = layers == null ? List.of() : List.copyOf(layers);
  }

  static SkyWalkingService fromJson(JsonNode s) {
    JsonNode normal = s.get("normal");
    return new SkyWalkingService(
        JsonFields.str(s, "id"),
        JsonFields.str(s, "name"),
        JsonFields.str(s, "group"),
        JsonFields.str(s, "shortName"),
        JsonFields.strings(s, "layers"),
        normal == null || !normal.isBoolean() || normal.asBoolean());
  }
}
