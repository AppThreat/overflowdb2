package overflowdb.algorithm;

import overflowdb.Node;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * An iterative Depth-First-Search (DFS) walker that uses the Heap instead of the JVM Stack.
 * Safe for processing deeply nested structures (like ASTs of generated code) that would
 * otherwise cause StackOverflowErrors with recursive traversals.
 */
public class HeapWalker implements Iterator<Node> {
    private final ArrayDeque<Node> stack = new ArrayDeque<>();
    private final String[] edgeLabels;

    private Node nextNode;

    /**
     * @param roots The starting nodes.
     * @param edgeLabels The edge labels to follow for children (e.g., "AST", "CFG").
     */
    public HeapWalker(Iterator<Node> roots, String... edgeLabels) {
        this.edgeLabels = edgeLabels;
        while (roots.hasNext()) {
            stack.push(roots.next());
        }
        advance();
    }

    public static HeapWalker forNode(Node root, String... edgeLabels) {
        return new HeapWalker(new SingleNodeIterator(root), edgeLabels);
    }

    private void advance() {
        if (stack.isEmpty()) {
            nextNode = null;
            return;
        }

        nextNode = stack.pop();

        // Add children to stack
        // We iterate children and push them.
        Iterator<Node> children = nextNode.out(edgeLabels);
        ArrayDeque<Node> childBuffer = new ArrayDeque<>();
        while(children.hasNext()) {
            childBuffer.push(children.next());
        }
        while(!childBuffer.isEmpty()) {
            stack.push(childBuffer.pop());
        }
    }

    @Override
    public boolean hasNext() {
        return nextNode != null;
    }

    @Override
    public Node next() {
        if (nextNode == null) throw new NoSuchElementException();
        Node ret = nextNode;
        advance();
        return ret;
    }

    private static class SingleNodeIterator implements Iterator<Node> {
        private Node node;
        SingleNodeIterator(Node n) { this.node = n; }
        public boolean hasNext() { return node != null; }
        public Node next() {
            Node n = node;
            node = null;
            return n;
        }
    }
}