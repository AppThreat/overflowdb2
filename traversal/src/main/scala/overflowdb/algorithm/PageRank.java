package overflowdb.algorithm;

import gnu.trove.map.hash.TLongDoubleHashMap;
import gnu.trove.map.hash.TLongIntHashMap;
import overflowdb.Node;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Function;

/**
 * Ranking utilities for a directed graph defined by a node universe and a successor function.
 *
 * <p>Two measures are provided:
 * <ul>
 *   <li>{@link #inDegree} counts, for every node, how many edges from the universe point at it.
 *       It is cheap and useful as a quick "how heavily referenced is this" signal.</li>
 *   <li>{@link #compute} runs the classic PageRank iteration, which spreads importance along the
 *       edges so that a node referenced by important nodes is itself ranked highly.</li>
 * </ul>
 *
 * <p>Both measures only consider edges whose endpoints are inside the given node collection, so the
 * caller can rank an arbitrary subgraph (for example a call graph) without first materialising it.
 */
public class PageRank {

    /** Damping factor used by most PageRank implementations. */
    public static final double DEFAULT_DAMPING_FACTOR = 0.85;
    /** Maximum number of iterations before the computation stops. */
    public static final int DEFAULT_MAX_ITERATIONS = 100;
    /** Convergence threshold: stop once the total rank change between two iterations is smaller. */
    public static final double DEFAULT_TOLERANCE = 1.0e-6;

    private PageRank() {
    }

    /**
     * Counts the incoming edges for every node, considering only edges whose source is also part of
     * the given collection.
     *
     * @param nodes         the universe of nodes to score
     * @param getSuccessors function returning the outgoing neighbours of a node
     * @return a map from node id to its in-degree within the universe
     */
    public static Map<Long, Integer> inDegree(Collection<Node> nodes,
                                               Function<Node, Iterator<Node>> getSuccessors) {
        TLongIntHashMap degree = new TLongIntHashMap(nodes.size());
        for (Node node : nodes) {
            if (!degree.containsKey(node.id())) {
                degree.put(node.id(), 0);
            }
        }
        for (Node node : nodes) {
            Iterator<Node> successors = getSuccessors.apply(node);
            while (successors.hasNext()) {
                long succId = successors.next().id();
                if (degree.containsKey(succId)) {
                    degree.increment(succId);
                }
            }
        }
        Map<Long, Integer> result = new HashMap<>(nodes.size());
        degree.forEachEntry((id, value) -> {
            result.put(id, value);
            return true;
        });
        return result;
    }

    /**
     * Runs PageRank with the default damping factor, iteration cap and tolerance.
     *
     * @param nodes         the universe of nodes to score
     * @param getSuccessors function returning the outgoing neighbours of a node
     * @return a map from node id to its PageRank score; scores over the universe sum to roughly 1.0
     */
    public static Map<Long, Double> compute(Collection<Node> nodes,
                                            Function<Node, Iterator<Node>> getSuccessors) {
        return compute(nodes, getSuccessors, DEFAULT_DAMPING_FACTOR, DEFAULT_MAX_ITERATIONS,
                DEFAULT_TOLERANCE);
    }

    /**
     * Runs PageRank with explicit parameters.
     *
     * @param nodes          the universe of nodes to score
     * @param getSuccessors  function returning the outgoing neighbours of a node
     * @param dampingFactor  probability of following an edge rather than jumping at random, commonly 0.85
     * @param maxIterations  upper bound on the number of iterations
     * @param tolerance      stop once the summed absolute rank change drops below this value
     * @return a map from node id to its PageRank score
     */
    public static Map<Long, Double> compute(Collection<Node> nodes,
                                            Function<Node, Iterator<Node>> getSuccessors,
                                            double dampingFactor,
                                            int maxIterations,
                                            double tolerance) {
        int count = nodes.size();
        Map<Long, Double> result = new HashMap<>(count);
        if (count == 0) {
            return result;
        }

        // Precompute out-degrees restricted to the universe so dangling-node handling is correct.
        TLongIntHashMap outDegree = new TLongIntHashMap(count);
        for (Node node : nodes) {
            outDegree.put(node.id(), 0);
        }
        for (Node node : nodes) {
            int degree = 0;
            Iterator<Node> successors = getSuccessors.apply(node);
            while (successors.hasNext()) {
                if (outDegree.containsKey(successors.next().id())) {
                    degree++;
                }
            }
            outDegree.put(node.id(), degree);
        }

        double initial = 1.0 / count;
        TLongDoubleHashMap rank = new TLongDoubleHashMap(count);
        for (Node node : nodes) {
            rank.put(node.id(), initial);
        }

        double baseRank = (1.0 - dampingFactor) / count;
        TLongDoubleHashMap nextRank = new TLongDoubleHashMap(count);
        for (int iteration = 0; iteration < maxIterations; iteration++) {
            // Rank held by dangling nodes (no outgoing edges) is redistributed evenly.
            double danglingSum = 0.0;
            for (Node node : nodes) {
                if (outDegree.get(node.id()) == 0) {
                    danglingSum += rank.get(node.id());
                }
            }
            double danglingContribution = dampingFactor * danglingSum / count;

            for (Node node : nodes) {
                nextRank.put(node.id(), baseRank + danglingContribution);
            }
            for (Node node : nodes) {
                int degree = outDegree.get(node.id());
                if (degree == 0) {
                    continue;
                }
                double share = dampingFactor * rank.get(node.id()) / degree;
                Iterator<Node> successors = getSuccessors.apply(node);
                while (successors.hasNext()) {
                    long succId = successors.next().id();
                    if (nextRank.containsKey(succId)) {
                        nextRank.put(succId, nextRank.get(succId) + share);
                    }
                }
            }

            double delta = 0.0;
            for (Node node : nodes) {
                delta += Math.abs(nextRank.get(node.id()) - rank.get(node.id()));
            }
            TLongDoubleHashMap swap = rank;
            rank = nextRank;
            nextRank = swap;

            if (delta < tolerance) {
                break;
            }
        }

        for (Node node : nodes) {
            result.put(node.id(), rank.get(node.id()));
        }
        return result;
    }
}
