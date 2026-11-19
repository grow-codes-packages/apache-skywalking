package codes.grow.packages.skywalking;

import java.util.Optional;

/**
 * Narrows {@link SkyWalkingQueryClient#topology}.
 *
 * @param service only this service's neighbourhood ({@code getServiceTopology})
 * @param layer only this layer (global topology)
 */
public record TopologyOptions(Optional<String> service, Optional<String> layer) {
  /** The global topology of every layer. */
  public static final TopologyOptions GLOBAL = new TopologyOptions(null, null);

  /**
   * Normalises nulls.
   *
   * @param service service
   * @param layer layer
   */
  public TopologyOptions {
    service = service == null ? Optional.empty() : service;
    layer = layer == null ? Optional.empty() : layer;
  }

  /**
   * One service's neighbourhood.
   *
   * @param service service name
   * @return options
   */
  public static TopologyOptions forService(String service) {
    return new TopologyOptions(Optional.ofNullable(service), null);
  }

  /**
   * The global topology of one layer.
   *
   * @param layer layer, e.g. {@code GENERAL}
   * @return options
   */
  public static TopologyOptions forLayer(String layer) {
    return new TopologyOptions(null, Optional.ofNullable(layer));
  }
}
