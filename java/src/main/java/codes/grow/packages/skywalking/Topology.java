package codes.grow.packages.skywalking;

import codes.grow.packages.skywalking.tracing.query.DependencyLink;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A service graph ({@code getGlobalTopology} / {@code getServiceTopology}). OAP reports no call
 * counts here.
 *
 * @param nodes services
 * @param calls edges
 */
public record Topology(List<TopologyNode> nodes, List<TopologyCall> calls) {
  /** GraphQL selection of a topology. */
  static final String FIELDS =
      "nodes{id name type isReal layers} "
          + "calls{id source target detectPoints sourceComponents targetComponents}";

  /**
   * Copies the lists.
   *
   * @param nodes nodes
   * @param calls calls
   */
  public Topology {
    nodes = nodes == null ? List.of() : List.copyOf(nodes);
    calls = calls == null ? List.of() : List.copyOf(calls);
  }

  static Topology fromJson(JsonNode t) {
    return new Topology(
        JsonFields.objects(
            t,
            "nodes",
            n ->
                new TopologyNode(
                    JsonFields.str(n, "id"),
                    JsonFields.str(n, "name"),
                    JsonFields.str(n, "type"),
                    JsonFields.bool(n, "isReal"),
                    JsonFields.strings(n, "layers"))),
        JsonFields.objects(
            t,
            "calls",
            c ->
                new TopologyCall(
                    JsonFields.str(c, "id"),
                    JsonFields.str(c, "source"),
                    JsonFields.str(c, "target"),
                    JsonFields.strings(c, "detectPoints"),
                    JsonFields.strings(c, "sourceComponents"),
                    JsonFields.strings(c, "targetComponents"))));
  }

  /**
   * The name of a node id.
   *
   * @param id node id
   * @return the name, or {@code ""} when unknown
   */
  public String nodeName(String id) {
    for (TopologyNode n : nodes) {
      if (n.id().equals(id)) {
        return n.name();
      }
    }
    return "";
  }

  /**
   * Dependency links: parent = source node name, child = target node name (the id when the node is
   * unknown), {@code callCount} 0 (the topology carries no counts); sorted by parent, then child.
   *
   * @return the links
   */
  public List<DependencyLink> toDependencies() {
    Map<String, String> names = new HashMap<>();
    for (TopologyNode n : nodes) {
      names.putIfAbsent(n.id(), n.name());
    }
    return calls.stream()
        .map(
            c ->
                new DependencyLink(
                    names.getOrDefault(c.source(), c.source()),
                    names.getOrDefault(c.target(), c.target()),
                    0))
        .sorted(Comparator.comparing(DependencyLink::parent).thenComparing(DependencyLink::child))
        .toList();
  }
}
