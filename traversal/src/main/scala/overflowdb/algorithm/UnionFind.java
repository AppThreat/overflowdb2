package overflowdb.algorithm;

import gnu.trove.map.hash.TLongLongHashMap;
import gnu.trove.map.hash.TLongIntHashMap;

/**
 * A memory-efficient Union-Find (Disjoint Set Union) data structure backed by Trove primitives.
 * Uses Path Compression and Union by Rank.
 */
public class UnionFind {
    // Maps NodeID -> ParentID
    private final TLongLongHashMap parent;
    // Maps NodeID -> Rank
    private final TLongIntHashMap rank;

    public UnionFind() {
        this(100);
    }

    public UnionFind(int initialCapacity) {
        this.parent = new TLongLongHashMap(initialCapacity);
        this.rank = new TLongIntHashMap(initialCapacity);
    }

    /**
     * Establishes a new set for the given node ID.
     */
    public void makeSet(long nodeId) {
        if (!parent.containsKey(nodeId)) {
            parent.put(nodeId, nodeId);
            rank.put(nodeId, 0);
        }
    }

    /**
     * Finds the representative (root) of the set containing nodeId.
     * Performs path compression iteratively.
     */
    public long find(long nodeId) {
        if (!parent.containsKey(nodeId)) {
            parent.put(nodeId, nodeId);
            rank.put(nodeId, 0);
            return nodeId;
        }

        long curr = nodeId;
        long p = parent.get(curr);
        while (p != curr) {
            curr = p;
            p = parent.get(curr);
        }
        long root = curr;

        // Path compression
        curr = nodeId;
        p = parent.get(curr);
        while (p != root) {
            parent.put(curr, root);
            curr = p;
            p = parent.get(curr);
        }

        return root;
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

        int rankA = rank.get(rootA);
        int rankB = rank.get(rootB);

        if (rankA < rankB) {
            parent.put(rootA, rootB);
        } else if (rankA > rankB) {
            parent.put(rootB, rootA);
        } else {
            parent.put(rootB, rootA);
            rank.put(rootA, rankA + 1);
        }
        return true;
    }

    public void clear() {
        parent.clear();
        rank.clear();
    }
}