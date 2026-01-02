package overflowdb.algorithm;

import gnu.trove.map.hash.TLongIntHashMap;
import overflowdb.Node;

import java.util.*;
import java.util.function.Function;

public class TopologicalSort {

    /**
     * Performs a topological sort on the provided nodes based on the relationships defined by `getSuccessors`.
     * @param nodes The universe of nodes to sort.
     * @param getSuccessors Function to retrieve outgoing neighbors for a given node.
     * @return A list of nodes in topological order.
     * @throws CycleDetectedException if the graph contains a cycle.
     */
    public static List<Node> sort(Collection<Node> nodes, Function<Node, Iterator<Node>> getSuccessors) {
        List<Node> result = new ArrayList<>(nodes.size());
        TLongIntHashMap inDegree = new TLongIntHashMap(nodes.size());
        Map<Long, Node> nodeById = new HashMap<>(nodes.size());
        for (Node node : nodes) {
            long id = node.id();
            nodeById.put(id, node);
            if (!inDegree.containsKey(id)) {
                inDegree.put(id, 0);
            }
            Iterator<Node> successors = getSuccessors.apply(node);
            while (successors.hasNext()) {
                Node successor = successors.next();
                long succId = successor.id();
                inDegree.adjustOrPutValue(succId, 1, 1);
            }
        }

        Queue<Node> queue = new ArrayDeque<>();
        for (Node node : nodes) {
            if (inDegree.get(node.id()) == 0) {
                queue.add(node);
            }
        }

        while (!queue.isEmpty()) {
            Node u = queue.poll();
            result.add(u);

            Iterator<Node> successors = getSuccessors.apply(u);
            while (successors.hasNext()) {
                Node v = successors.next();
                long vId = v.id();
                if (nodeById.containsKey(vId)) {
                    int newDegree = inDegree.get(vId) - 1;
                    inDegree.put(vId, newDegree);

                    if (newDegree == 0) {
                        queue.add(v);
                    }
                }
            }
        }

        if (result.size() != nodes.size()) {
            throw new CycleDetectedException("Graph contains a cycle, cannot sort topologically.");
        }

        return result;
    }

    public static class CycleDetectedException extends RuntimeException {
        public CycleDetectedException(String message) {
            super(message);
        }
    }
}