package overflowdb.algorithm;

import gnu.trove.map.hash.TLongLongHashMap;

/**
 * A memory-efficient Union-Find (Disjoint Set Union) data structure backed by Trove primitives.
 * Uses Path Compression and Union by Rank (implicitly via path compression).
 */
public class UnionFind {
    // Maps NodeID -> ParentID
    private final TLongLongHashMap parent;

    public UnionFind() {
        this(100);
    }

    public UnionFind(int initialCapacity) {
        this.parent = new TLongLongHashMap(initialCapacity);
    }

    /**
     * Establishes a new set for the given node ID.
     */
    public void makeSet(long nodeId) {
        if (!parent.containsKey(nodeId)) {
            parent.put(nodeId, nodeId);
        }
    }

    /**
     * Finds the representative (root) of the set containing nodeId.
     * Performs path compression.
     */
    public long find(long nodeId) {
        if (!parent.containsKey(nodeId)) {
            parent.put(nodeId, nodeId);
            return nodeId;
        }

        long p = parent.get(nodeId);
        if (p != nodeId) {
            long root = find(p);
            parent.put(nodeId, root);
            return root;
        }
        return p;
    }

    /**
     * Unifies the sets containing nodeA and nodeB.
     * @return true if the sets were different and are now merged, false if they were already same.
     */
    public boolean union(long nodeA, long nodeB) {
        long rootA = find(nodeA);
        long rootB = find(nodeB);

        if (rootA == rootB) {
            return false;
        }
        parent.put(rootB, rootA);
        return true;
    }

    public void clear() {
        parent.clear();
    }
}