package codes.grow.packages.skywalking;

import java.util.List;

/**
 * An edge of the service topology (node ids).
 *
 * @param id edge id
 * @param source source node id
 * @param target target node id
 * @param detectPoints {@code CLIENT} and/or {@code SERVER}
 * @param sourceComponents components seen on the source side
 * @param targetComponents components seen on the target side
 */
public record TopologyCall(
    String id,
    String source,
    String target,
    List<String> detectPoints,
    List<String> sourceComponents,
    List<String> targetComponents) {
  /**
   * Copies the lists.
   *
   * @param id id
   * @param source source
   * @param target target
   * @param detectPoints detect points
   * @param sourceComponents source components
   * @param targetComponents target components
   */
  public TopologyCall {
    detectPoints = detectPoints == null ? List.of() : List.copyOf(detectPoints);
    sourceComponents = sourceComponents == null ? List.of() : List.copyOf(sourceComponents);
    targetComponents = targetComponents == null ? List.of() : List.copyOf(targetComponents);
  }
}
