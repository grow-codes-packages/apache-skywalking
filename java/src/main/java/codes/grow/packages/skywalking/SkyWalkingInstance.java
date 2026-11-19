package codes.grow.packages.skywalking;

import java.util.List;

/**
 * A service instance.
 *
 * @param id {@code serviceId + "_" + base64(name)}
 * @param name instance name
 * @param language agent language, may be empty
 * @param instanceUUID instance UUID, may be empty
 * @param attributes reported properties
 */
public record SkyWalkingInstance(
    String id, String name, String language, String instanceUUID, List<Attribute> attributes) {
  /**
   * Copies the attributes.
   *
   * @param id id
   * @param name name
   * @param language language
   * @param instanceUUID UUID
   * @param attributes attributes
   */
  public SkyWalkingInstance {
    attributes = attributes == null ? List.of() : List.copyOf(attributes);
  }

  /**
   * One reported instance property.
   *
   * @param name property name
   * @param value property value
   */
  public record Attribute(String name, String value) {}
}
