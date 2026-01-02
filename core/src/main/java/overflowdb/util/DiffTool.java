package overflowdb.util;

import gnu.trove.list.array.TLongArrayList;
import overflowdb.Edge;
import overflowdb.Graph;
import overflowdb.Node;

import java.util.*;

public class DiffTool {

  /** compare two graphs element by element */
  public static List<String> compare(Graph graph1, Graph graph2) {
    final List<String> diff = new ArrayList<>();
    if (graph1.nodeCount() != graph2.nodeCount()) {
      diff.add("node count differs: graph1=" + graph1.nodeCount() + ", graph2=" + graph2.nodeCount());
    }
    if (graph1.edgeCount() != graph2.edgeCount()) {
      diff.add("edge count differs: graph1=" + graph1.edgeCount() + ", graph2=" + graph2.edgeCount());
    }

    TLongArrayList nodeIds = new TLongArrayList(graph1.nodeCount() + graph2.nodeCount());
    graph1.nodes().forEachRemaining(node -> nodeIds.add(node.id()));
    graph2.nodes().forEachRemaining(node -> nodeIds.add(node.id()));

    // Sort and remove duplicates to emulate SortedSet behavior efficiently
    nodeIds.sort();
    int uniqueCount = 0;
    if (nodeIds.size() > 0) {
      int dst = 0;
      for (int src = 1; src < nodeIds.size(); src++) {
        if (nodeIds.get(dst) != nodeIds.get(src)) {
          dst++;
          nodeIds.set(dst, nodeIds.get(src));
        }
      }
      uniqueCount = dst + 1;
    }

    for(int i = 0; i < uniqueCount; i++) {
      long nodeId = nodeIds.get(i);
      final Node node1 = graph1.node(nodeId);
      final Node node2 = graph2.node(nodeId);
      if (node1 == null) diff.add("node " + node2 + " only exists in graph2");
      else if (node2 == null) diff.add("node " + node1 + " only exists in graph1");
      else {
        if (!node1.label().equals(node2.label()))
          diff.add("different label for nodeId=" + nodeId + "; graph1=" + node1.label() + ", graph2=" + node2.label());

        final String context = "nodeId=" + nodeId;
        compareProperties(node1.propertiesMap(), node2.propertiesMap(), diff, context);
        compareEdges(node1.outE(), node2.outE(), diff, context + ".outE");
      }
    }

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
        diff.add(context + "; property '" + key + "' -> '" + value2 + "' only exists in graph2");
      } else if (value2 == null) {
        diff.add(context + "; property '" + key + "' -> '" + value1 + "' only exists in graph1");
      } else {
        if (value1.getClass().isArray() && value2.getClass().isArray()) {
          if (!arraysEqual(value1, value2)) {
            diff.add(context + "; array property '" + key + "' has different values: graph1='" + value1 + "', graph2='" + value2 + "'");
          }
        } else if (!value1.equals(value2)) {
          diff.add(context + "; property '" + key + "' has different values: graph1='" + value1 + "', graph2='" + value2 + "'");
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
      diff.add(context + "; different number of edges: graph1=" + edges1Sorted.size() + ", graph2=" + edges2Sorted.size());
    } else {
      Iterator<Edge> edges1SortedIter = edges1Sorted.iterator();
      Iterator<Edge> edges2SortedIter = edges2Sorted.iterator();
      while (edges1SortedIter.hasNext()) {
        Edge edge1 = edges1SortedIter.next();
        Edge edge2 = edges2SortedIter.next();

        if (!edge1.label().equals(edge2.label()))
          diff.add(context + "; different label for sorted edges; graph1=" + edge1.label() + ", graph2=" + edge2.label());
        else
          compareProperties(edge1.propertiesMap(), edge2.propertiesMap(), diff, context + "; edge label = " + edge1.label());
      }
    }
  }

  private static List<Edge> sort(Iterator<Edge> edges) {
    List<Edge> edgesSorted = new LinkedList<>();
    edges.forEachRemaining(edgesSorted::add);
    edgesSorted.sort(Comparator.comparing(edge ->
            edge.label() + " " + edge.propertiesMap().size()
    ));
    return edgesSorted;
  }


}