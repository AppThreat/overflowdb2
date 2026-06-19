package overflowdb.algorithm;

import gnu.trove.map.hash.TLongIntHashMap;
import overflowdb.Node;

import java.util.*;
import java.util.function.Function;

/**
 * Computes Strongly Connected Components (SCC) using Tarjan's algorithm.
 * Time complexity: O(V + E), memory-optimized and stack-safe.
 */
public class StronglyConnectedComponents {
    private final Collection<Node> nodes;
    private final Function<Node, Iterator<Node>> getSuccessors;

    private final TLongIntHashMap dfn;
    private final TLongIntHashMap low;
    private final Set<Long> onStack;
    private final Deque<Node> stack;
    private final List<Set<Node>> sccs;
    private int index;

    private StronglyConnectedComponents(Collection<Node> nodes, Function<Node, Iterator<Node>> getSuccessors) {
        this.nodes = nodes;
        this.getSuccessors = getSuccessors;
        this.dfn = new TLongIntHashMap();
        this.low = new TLongIntHashMap();
        this.onStack = new HashSet<>();
        this.stack = new ArrayDeque<>();
        this.sccs = new ArrayList<>();
        this.index = 0;
    }

    /**
     * Finds all strongly connected components in the subgraph defined by the nodes and successors.
     * @param nodes Universe of nodes to scan.
     * @param getSuccessors Successor supplier.
     * @return List of sets of nodes, where each set represents a Strongly Connected Component.
     */
    public static List<Set<Node>> compute(Collection<Node> nodes, Function<Node, Iterator<Node>> getSuccessors) {
        StronglyConnectedComponents solver = new StronglyConnectedComponents(nodes, getSuccessors);
        solver.run();
        return solver.sccs;
    }

    private void run() {
        for (Node node : nodes) {
            if (!dfn.containsKey(node.id())) {
                dfsIterative(node);
            }
        }
    }

    private void dfsIterative(Node startNode) {
        class Frame {
            final Node node;
            final Iterator<Node> it;
            Frame(Node node, Iterator<Node> it) {
                this.node = node;
                this.it = it;
            }
        }

        Deque<Frame> callStack = new ArrayDeque<>();
        
        long startId = startNode.id();
        dfn.put(startId, index);
        low.put(startId, index);
        index++;
        stack.push(startNode);
        onStack.add(startId);
        callStack.push(new Frame(startNode, getSuccessors.apply(startNode)));

        while (!callStack.isEmpty()) {
            Frame frame = callStack.peek();
            Node u = frame.node;
            long uId = u.id();

            if (frame.it.hasNext()) {
                Node v = frame.it.next();
                long vId = v.id();

                if (!dfn.containsKey(vId)) {
                    dfn.put(vId, index);
                    low.put(vId, index);
                    index++;
                    stack.push(v);
                    onStack.add(vId);
                    callStack.push(new Frame(v, getSuccessors.apply(v)));
                } else if (onStack.contains(vId)) {
                    low.put(uId, Math.min(low.get(uId), dfn.get(vId)));
                }
            } else {
                callStack.pop();
                if (!callStack.isEmpty()) {
                    Node parent = callStack.peek().node;
                    long parentId = parent.id();
                    low.put(parentId, Math.min(low.get(parentId), low.get(uId)));
                }

                if (low.get(uId) == dfn.get(uId)) {
                    Set<Node> scc = new HashSet<>();
                    while (true) {
                        Node v = stack.pop();
                        long vId = v.id();
                        onStack.remove(vId);
                        scc.add(v);
                        if (vId == uId) {
                            break;
                        }
                    }
                    sccs.add(scc);
                }
            }
        }
    }
}
