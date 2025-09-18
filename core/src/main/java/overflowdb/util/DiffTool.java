package overflowdb.util;

import overflowdb.Edge;
import overflowdb.Graph;
import overflowdb.Node;

import java.util.*;

public class DiffTool {

  /** compare two graphs element by element
   * identity of nodes is given by their id, i.e. if the graphs have the same nodes/edges/properties, but use different
   * node ids, a lot of differences will be reported and the results will be useless
   */
  public static List<String> compare(Graph graph1, Graph graph2) {
    final List<String> diff = new ArrayList<>();
    if (graph1.nodeCount() != graph2.nodeCount()) {
      diff.add(String.format("node count differs: graph1=%d, graph2=%d", graph1.nodeCount(), graph2.nodeCount()));
    }
    if (graph1.edgeCount() != graph2.edgeCount()) {
      diff.add(String.format("edge count differs: graph1=%d, graph2=%d", graph1.edgeCount(), graph2.edgeCount()));
    }

    SortedSet<Long> nodeIds = new TreeSet<>();
    graph1.nodes().forEachRemaining(node -> nodeIds.add(node.id()));
    graph2.nodes().forEachRemaining(node -> nodeIds.add(node.id()));

    nodeIds.forEach(nodeId -> {
      final Node node1 = graph1.node(nodeId);
      final Node node2 = graph2.node(nodeId);
      if (node1 == null) diff.add(String.format("node %s only exists in graph2", node2));
      else if (node2 == null) diff.add(String.format("node %s only exists in graph1", node1));
      else {
        if (!node1.label().equals(node2.label()))
          diff.add(String.format("different label for nodeId=%d; graph1=%s, graph2=%s ", nodeId, node1.label(), node2.label()));

        final String context = "nodeId=" + nodeId;
        compareProperties(node1.propertiesMap(), node2.propertiesMap(), diff, context);
        compareEdges(node1.outE(), node2.outE(), diff, context + ".outE");
      }
    });

    return diff;
  }

  private static void compareProperties(Map<String, Object> properties1, Map<String, Object> properties2, List<String> diff, String context) {
    SortedSet<String> propertyKeys = new TreeSet<>();
    propertyKeys.addAll(properties1.keySet());
    propertyKeys.addAll(properties2.keySet());

    propertyKeys.forEach(key -> {
      Object value1 = properties1.get(key);
      Object value2 = properties2.get(key);

      if (value1 == null) {
        diff.add(String.format("%s; property '%s' -> '%s' only exists in graph2", context, key, value2));
      } else if (value2 == null) {
        diff.add(String.format("%s; property '%s' -> '%s' only exists in graph1", context, key, value1));
      } else { // both values are not null
        if (value1.getClass().isArray() && value2.getClass().isArray()) { // both values are arrays
          if (!arraysEqual(value1, value2)) {
            diff.add(String.format("%s; array property '%s' has different values: graph1='%s', graph2='%s'", context, key, value1, value2));
          }
        } else if (!value1.equals(value2)) { // not both values are arrays
          diff.add(String.format("%s; property '%s' has different values: graph1='%s', graph2='%s'", context, key, value1, value2));
        }
      }
    });
  }


  /**
   * Compare given objects, assuming they are both arrays of the same type.
   * This is required because arrays don't support `.equals`, and is quite lengthy because java has one array type for each data type
   */
  public static boolean arraysEqual(Object value1, Object value2) {
    // need to check all array types unfortunately
      return switch (value1) {
          case Object[] objects when value2 instanceof Object[] -> Arrays.deepEquals(objects, (Object[]) value2);
          case boolean[] booleans when value2 instanceof int[] -> Arrays.equals(booleans, (boolean[]) value2);
          case byte[] bytes when value2 instanceof byte[] -> Arrays.equals(bytes, (byte[]) value2);
          case char[] chars when value2 instanceof char[] -> Arrays.equals(chars, (char[]) value2);
          case short[] shorts when value2 instanceof short[] -> Arrays.equals(shorts, (short[]) value2);
          case int[] ints when value2 instanceof int[] -> Arrays.equals(ints, (int[]) value2);
          case long[] longs when value2 instanceof long[] -> Arrays.equals(longs, (long[]) value2);
          case float[] floats when value2 instanceof float[] -> Arrays.equals(floats, (float[]) value2);
          case double[] doubles when value2 instanceof double[] -> Arrays.equals(doubles, (double[]) value2);
          default -> throw new AssertionError(String.format(
                  "unable to compare given objects (%s of type %s; %s of type %s)",
                  value1, value1.getClass(), value2, value2.getClass()));
      };
  }

  private static void compareEdges(Iterator<Edge> edges1, Iterator<Edge> edges2, List<String> diff, String context) {
    List<Edge> edges1Sorted = sort(edges1);
    List<Edge> edges2Sorted = sort(edges2);

    if (edges1Sorted.size() != edges2Sorted.size()) {
      diff.add(String.format("%s; different number of edges: graph1=%d, graph2=%d", context, edges1Sorted.size(), edges2Sorted.size()));
    } else {
      Iterator<Edge> edges1SortedIter = edges1Sorted.iterator();
      Iterator<Edge> edges2SortedIter = edges2Sorted.iterator();
      while (edges1SortedIter.hasNext()) {
        Edge edge1 = edges1SortedIter.next();
        Edge edge2 = edges2SortedIter.next();

        if (!edge1.label().equals(edge2.label()))
          diff.add(String.format("%s; different label for sorted edges; graph1=%s, graph2=%s ", context, edge1.label(), edge2.label()));
        else
          compareProperties(edge1.propertiesMap(), edge2.propertiesMap(), diff, String.format("%s; edge label = %s", context, edge1.label()));
      }
    }
  }

  private static List<Edge> sort(Iterator<Edge> edges) {
    List<Edge> edgesSorted = new LinkedList();
    edges.forEachRemaining(edgesSorted::add);
    edgesSorted.sort(Comparator.comparing(edge ->
      String.format("%s %d", edge.label(), edge.propertiesMap().size())
    ));
    return edgesSorted;
  }


}
