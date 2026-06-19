package overflowdb.algorithm;

import overflowdb.Node;

import java.util.*;
import java.util.function.Function;

/**
 * Context-sensitive path finder using matching call/return parentheses logic.
 * Useful for high-precision inter-procedural taint analysis.
 */
public class ContextSensitivePathFinder {
    
    public static class ContextEdge {
        public enum Type { OPEN, CLOSE, NEUTRAL }

        public final Node target;
        public final Type type;
        public final long contextId;

        public ContextEdge(Node target, Type type, long contextId) {
            this.target = target;
            this.type = type;
            this.contextId = contextId;
        }
    }

    public static class Path {
        public final List<Node> nodes;
        public Path(List<Node> nodes) { this.nodes = nodes; }
    }

    private static class SearchState {
        final Node node;
        final List<Long> stack;
        final List<Node> path;

        SearchState(Node node, List<Long> stack, List<Node> path) {
            this.node = node;
            this.stack = stack;
            this.path = path;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof SearchState)) return false;
            SearchState that = (SearchState) o;
            return node.id() == that.node.id() && Objects.equals(stack, that.stack);
        }

        @Override
        public int hashCode() {
            return Objects.hash(node.id(), stack);
        }
    }

    /**
     * Finds a context-sensitive path from source to target.
     * @param source Starting node.
     * @param target Destination node.
     * @param getEdges Function mapping a node to its context-sensitive outgoing transitions.
     * @param maxStackDepth Maximum call-stack depth limit to prevent infinite loops.
     * @return An Optional containing the Path if found, otherwise empty.
     */
    public static Optional<Path> findPath(
            Node source,
            Node target,
            Function<Node, Iterator<ContextEdge>> getEdges,
            int maxStackDepth) {

        Queue<SearchState> queue = new ArrayDeque<>();
        Set<SearchState> visited = new HashSet<>();

        SearchState startState = new SearchState(source, Collections.emptyList(), List.of(source));
        queue.add(startState);
        visited.add(startState);

        while (!queue.isEmpty()) {
            SearchState curr = queue.poll();
            if (curr.node.id() == target.id()) {
                return Optional.of(new Path(curr.path));
            }

            Iterator<ContextEdge> edges = getEdges.apply(curr.node);
            while (edges.hasNext()) {
                ContextEdge edge = edges.next();
                List<Long> nextStack = computeNextStack(curr.stack, edge.type, edge.contextId, maxStackDepth);
                if (nextStack == null) {
                    continue;
                }

                List<Node> nextPath = new ArrayList<>(curr.path);
                nextPath.add(edge.target);

                SearchState nextState = new SearchState(edge.target, nextStack, nextPath);
                if (visited.add(nextState)) {
                    queue.add(nextState);
                }
            }
        }

        return Optional.empty();
    }

    private static List<Long> computeNextStack(List<Long> stack, ContextEdge.Type type, long contextId, int maxDepth) {
        if (type == ContextEdge.Type.NEUTRAL) {
            return stack;
        } else if (type == ContextEdge.Type.OPEN) {
            if (stack.size() >= maxDepth) {
                return null;
            }
            List<Long> nextStack = new ArrayList<>(stack.size() + 1);
            nextStack.addAll(stack);
            nextStack.add(contextId);
            return nextStack;
        } else { // CLOSE
            if (stack.isEmpty()) {
                return stack;
            }
            long top = stack.get(stack.size() - 1);
            if (top == contextId) {
                List<Long> nextStack = new ArrayList<>(stack.size() - 1);
                for (int i = 0; i < stack.size() - 1; i++) {
                    nextStack.add(stack.get(i));
                }
                return nextStack;
            }
            return null;
        }
    }
}
