package codes.grow.packages.skywalking;

import java.util.List;

/**
 * A service in the topology. The virtual {@code User} node has {@code isReal} false.
 *
 * @param id service id
 * @param name service name
 * @param type node type (component), may be empty
 * @param isReal false for virtual nodes
 * @param layers layers of the service
 */
public record TopologyNode(
    String id, String name, String type, boolean isReal, List<String> layers) {
  /**
   * Copies the layers.
   *
   * @param id id
   * @param name name
   * @param type type
   * @param isReal real flag
   * @param layers layers
   */
  public TopologyNode {
    layers = layers == null ? List.of() : List.copyOf(layers);
  }
}
