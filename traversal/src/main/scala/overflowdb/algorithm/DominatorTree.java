package overflowdb.algorithm;

import gnu.trove.map.hash.TLongIntHashMap;
import gnu.trove.map.hash.TLongLongHashMap;
import overflowdb.Node;

import java.util.*;
import java.util.function.Function;

/**
 * Computes dominator and post-dominator trees using the Lengauer-Tarjan algorithm.
 * Time complexity: O(E * alpha(V)), virtually linear.
 */
public class DominatorTree {
    private final Node root;
    private final Function<Node, Iterator<Node>> getSuccessors;

    // Node ID -> DFS index (1-based)
    private final TLongIntHashMap dfnum;
    // DFS index -> Node
    private final List<Node> vertex;
    // Node ID -> Parent Node ID in DFS tree
    private final TLongLongHashMap parent;
    // Node ID -> Semi-dominator Node ID
    private final TLongLongHashMap sdom;
    // Node ID -> Immediate dominator Node ID
    private final TLongLongHashMap idom;

    // Link-Eval data structures
    private final TLongLongHashMap ancestor;
    private final TLongLongHashMap label;

    // Predecessors list built during the initial DFS walk
    private final Map<Long, List<Long>> predecessors;

    private DominatorTree(Node root, Function<Node, Iterator<Node>> getSuccessors) {
        this.root = root;
        this.getSuccessors = getSuccessors;
        this.dfnum = new TLongIntHashMap();
        this.vertex = new ArrayList<>();
        this.parent = new TLongLongHashMap();
        this.sdom = new TLongLongHashMap();
        this.idom = new TLongLongHashMap();
        this.ancestor = new TLongLongHashMap();
        this.label = new TLongLongHashMap();
        this.predecessors = new HashMap<>();
    }

    /**
     * Computes immediate dominators for all nodes reachable from the root node.
     * @param root Entry node of the graph.
     * @param getSuccessors Successor supplier.
     * @return Map of Node ID to its Immediate Dominator Node ID.
     */
    public static Map<Long, Long> computeDominators(Node root, Function<Node, Iterator<Node>> getSuccessors) {
        DominatorTree dt = new DominatorTree(root, getSuccessors);
        dt.run();
        
        Map<Long, Long> result = new HashMap<>();
        dt.idom.forEachEntry((nodeId, domId) -> {
            result.put(nodeId, domId);
            return true;
        });
        return result;
    }

    /**
     * Computes immediate post-dominators starting from the exit node.
     * @param exit Terminal node of the graph.
     * @param getPredecessors Predecessor supplier (acting as successors in the reversed graph).
     * @return Map of Node ID to its Immediate Post-Dominator Node ID.
     */
    public static Map<Long, Long> computePostDominators(Node exit, Function<Node, Iterator<Node>> getPredecessors) {
        return computeDominators(exit, getPredecessors);
    }

    private void run() {
        computeDfsAndPredecessors();

        int n = vertex.size();
        if (n <= 1) {
            return;
        }

        @SuppressWarnings("unchecked")
        List<Long>[] bucket = new List[n + 1];
        for (int i = 1; i <= n; i++) {
            bucket[i] = new ArrayList<>();
        }

        for (int i = 0; i < n; i++) {
            long id = vertex.get(i).id();
            sdom.put(id, id);
        }

        for (int i = n - 1; i >= 1; i--) {
            Node w = vertex.get(i);
            long wId = w.id();
            long pId = parent.get(wId);

            List<Long> preds = predecessors.get(wId);
            if (preds != null) {
                for (long vId : preds) {
                    long u = eval(vId);
                    if (dfnum.containsKey(u) && dfnum.get(sdom.get(u)) < dfnum.get(sdom.get(wId))) {
                        sdom.put(wId, sdom.get(u));
                    }
                }
            }

            long sdomW = sdom.get(wId);
            int sdomWIndex = dfnum.get(sdomW);
            bucket[sdomWIndex].add(wId);

            link(pId, wId);

            int pIndex = dfnum.get(pId);
            for (long vId : bucket[pIndex]) {
                long u = eval(vId);
                idom.put(vId, dfnum.get(sdom.get(u)) < dfnum.get(sdom.get(vId)) ? u : pId);
            }
            bucket[pIndex].clear();
        }

        for (int i = 1; i < n; i++) {
            Node w = vertex.get(i);
            long wId = w.id();
            long idomW = idom.get(wId);
            if (idomW != sdom.get(wId)) {
                idom.put(wId, idom.get(idomW));
            }
        }
    }

    private void computeDfsAndPredecessors() {
        class Frame {
            final Node node;
            final Iterator<Node> it;
            Frame(Node node, Iterator<Node> it) {
                this.node = node;
                this.it = it;
            }
        }

        Deque<Frame> stack = new ArrayDeque<>();
        dfnum.put(root.id(), 1);
        vertex.add(root);
        label.put(root.id(), root.id());
        stack.push(new Frame(root, getSuccessors.apply(root)));

        while (!stack.isEmpty()) {
            Frame curr = stack.peek();
            if (curr.it.hasNext()) {
                Node v = curr.it.next();
                long vId = v.id();
                long uId = curr.node.id();
                predecessors.computeIfAbsent(vId, k -> new ArrayList<>()).add(uId);

                if (!dfnum.containsKey(vId)) {
                    dfnum.put(vId, vertex.size() + 1);
                    vertex.add(v);
                    label.put(vId, vId);
                    parent.put(vId, uId);
                    stack.push(new Frame(v, getSuccessors.apply(v)));
                }
            } else {
                stack.pop();
            }
        }
    }

    private void link(long v, long w) {
        ancestor.put(w, v);
    }

    private long eval(long v) {
        long anc = ancestor.get(v);
        if (anc == 0) {
            return label.get(v);
        }
        compress(v);
        return label.get(v);
    }

    private void compress(long v) {
        long anc = ancestor.get(v);
        if (ancestor.get(anc) != 0) {
            compress(anc);
            long labelV = label.get(v);
            long labelAnc = label.get(anc);
            if (dfnum.get(sdom.get(labelAnc)) < dfnum.get(sdom.get(labelV))) {
                label.put(v, labelAnc);
            }
            ancestor.put(v, ancestor.get(anc));
        }
    }
}
