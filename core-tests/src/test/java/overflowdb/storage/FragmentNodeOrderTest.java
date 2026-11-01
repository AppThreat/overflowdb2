package overflowdb.storage;

import org.junit.Test;
import overflowdb.*;
import overflowdb.testdomains.simple.DetachedTestNode;
import overflowdb.testdomains.simple.SimpleDomain;
import overflowdb.testdomains.simple.TestEdge;
import overflowdb.testdomains.simple.TestNode;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.Consumer;

import static org.junit.Assert.*;

/**
 * A graph built from a fragment must number its nodes like one built from the diff the fragment was encoded from:
 * callers such as chen build the same graph from a fresh parse or from a cached fragment, and analyses that walk
 * nodes in id order give different results when the order differs. applyDiff creates a node at its own change, at
 * the first edge naming it, or when it initializes a node with a property pointing to it, whichever comes first.
 *
 * Every test builds the same diff twice from a recipe, applies one copy with applyDiff and the fragment encoded from
 * the other with applyFragment, and compares the two graphs node by node in id order.
 */
public class FragmentNodeOrderTest {

    private static final long SCHEMA_HASH = 7L;

    private static final GraphFragmentCodec.PropertyReader READER =
            node -> node instanceof DetachedTestNode ? ((DetachedTestNode) node).properties() : null;

    private static final BoundaryResolver NO_BOUNDARY = new BoundaryResolver() {
        @Override
        public Optional<SymbolicKey> getSymbolicKey(NodeOrDetachedNode node) {
            return Optional.empty();
        }

        @Override
        public Optional<Node> resolve(SymbolicKey key) {
            return Optional.empty();
        }
    };

    private static DetachedNodeGeneric generic(String name) {
        return new DetachedNodeGeneric(TestNode.LABEL, TestNode.STRING_PROPERTY, name);
    }

    // ---- fixed scenarios -------------------------------------------------------------------------------------

    /** The case from PR 24: nodes that appear only as edge endpoints come before a later node change. */
    @Test
    public void edgeEndpointsBeforeALaterNodeChange() {
        assertSameOrder(diff -> {
            DetachedNodeGeneric src = generic("edge src");
            DetachedNodeGeneric dst = generic("edge dst");
            diff.addNode(generic("added before the edge"));
            diff.addEdge(src, dst, TestEdge.LABEL);
            diff.addNode(generic("added after the edge"));
        }, "added before the edge", "edge src", "edge dst", "added after the edge");
    }

    @Test
    public void nodeAddedExplicitlyAfterAnEdgeAlreadyCreatedIt() {
        assertSameOrder(diff -> {
            DetachedNodeGeneric a = generic("a");
            DetachedNodeGeneric b = generic("b");
            diff.addNode(generic("first"));
            diff.addEdge(a, b, TestEdge.LABEL);
            diff.addNode(b);
            diff.addNode(generic("last"));
            diff.addNode(a);
        }, "first", "a", "b", "last");
    }

    @Test
    public void edgeDstCreatedBeforeItIsEverASrc() {
        assertSameOrder(diff -> {
            DetachedNodeGeneric a = generic("a");
            DetachedNodeGeneric b = generic("b");
            DetachedNodeGeneric c = generic("c");
            diff.addEdge(b, a, TestEdge.LABEL);
            diff.addEdge(c, b, TestEdge.LABEL);
            diff.addEdge(a, c, TestEdge.LABEL, TestEdge.LONG_PROPERTY, 3L);
        }, "b", "a", "c");
    }

    @Test
    public void selfLoopAndRepeatedEdges() {
        assertSameOrder(diff -> {
            DetachedNodeGeneric a = generic("a");
            DetachedNodeGeneric b = generic("b");
            diff.addEdge(a, a, TestEdge.LABEL);
            diff.addNode(generic("between"));
            diff.addEdge(a, b, TestEdge.LABEL, TestEdge.LONG_PROPERTY, 1L);
            diff.addEdge(a, b, TestEdge.LABEL, TestEdge.LONG_PROPERTY, 2L);
            diff.addNode(a);
        }, "a", "between", "b");
    }

    /** applyDiff creates a node that only a property points to when it initializes the node holding the property;
     * the encoder used to refuse such a diff because the node looked external. */
    @Test
    public void nodeReachedOnlyThroughAProperty() {
        assertSameOrder(diff -> {
            diff.addNode(new DetachedTestNode("holder", new DetachedTestNode("held")));
            diff.addNode(generic("after"));
        }, "holder", "held", "after");
    }

    @Test
    public void propertyCreatesANodeBeforeItsOwnLaterChange() {
        assertSameOrder(diff -> {
            DetachedTestNode held = new DetachedTestNode("held");
            diff.addNode(new DetachedTestNode("holder", held));
            diff.addNode(generic("middle"));
            diff.addNode(held);
        }, "holder", "held", "middle");
    }

    /** Nodes created by an edge are initialized in creation order, and nodes their properties point to are created
     * after both endpoints: applyDiff queues initializers and drains them first in, first out. */
    @Test
    public void propertyReferencesAreCreatedAfterBothEdgeEndpoints() {
        assertSameOrder(diff -> {
            DetachedTestNode srcHeld = new DetachedTestNode("src held");
            DetachedTestNode dstHeld = new DetachedTestNode("dst held");
            DetachedTestNode src = new DetachedTestNode("src", srcHeld);
            DetachedTestNode dst = new DetachedTestNode("dst", dstHeld);
            diff.addEdge(src, dst, TestEdge.LABEL);
            diff.addNode(generic("after"));
        }, "src", "dst", "src held", "dst held", "after");
    }

    @Test
    public void chainOfPropertyReferences() {
        assertSameOrder(diff -> {
            DetachedTestNode c = new DetachedTestNode("c");
            DetachedTestNode b = new DetachedTestNode("b", c);
            DetachedTestNode a = new DetachedTestNode("a", b);
            DetachedNodeGeneric x = generic("x");
            diff.addEdge(x, c, TestEdge.LABEL);
            diff.addNode(a);
        }, "x", "c", "a", "b");
    }

    @Test
    public void propertyCycle() {
        assertSameOrder(diff -> {
            DetachedTestNode a = new DetachedTestNode("a");
            DetachedTestNode b = new DetachedTestNode("b", a);
            a.containedTestNode = b;
            diff.addNode(a);
        }, "a", "b");
    }

    /** A generic node's properties are attached as they are, so a detached node they hold is not created by
     * applyDiff. Such a diff is not encodable, as before. */
    @Test
    public void genericNodePropertyDoesNotCreateANode() {
        BatchedUpdate.DiffGraphBuilder diff = new BatchedUpdate.DiffGraphBuilder();
        diff.addNode(new DetachedNodeGeneric(TestNode.LABEL, "REF", generic("held")));
        assertEquals(Optional.empty(), GraphFragmentCodec.encode(diff, NO_BOUNDARY, SCHEMA_HASH, READER));
    }

    @Test
    public void readerIsAskedOncePerNode() {
        DetachedTestNode held = new DetachedTestNode("held");
        DetachedTestNode holder = new DetachedTestNode("holder", held);
        BatchedUpdate.DiffGraphBuilder diff = new BatchedUpdate.DiffGraphBuilder()
                .addNode(holder).addEdge(holder, held, TestEdge.LABEL).addNode(held);
        List<DetachedNodeData> asked = new ArrayList<>();
        Optional<byte[]> bytes = GraphFragmentCodec.encode(diff, NO_BOUNDARY, SCHEMA_HASH, node -> {
            asked.add(node);
            return READER.properties(node);
        });
        assertTrue(bytes.isPresent());
        assertEquals(Arrays.asList(holder, held), asked);
    }

    // ---- application variants --------------------------------------------------------------------------------

    @Test
    public void graphWithExistingNodesAndBoundaryEdges() throws Exception {
        try (Graph fromDiff = SimpleDomain.newGraph(); Graph fromFragment = SimpleDomain.newGraph()) {
            Node externalA = fromDiff.addNode(TestNode.LABEL, TestNode.STRING_PROPERTY, "external");
            Node externalB = fromFragment.addNode(TestNode.LABEL, TestNode.STRING_PROPERTY, "external");
            MapBoundary boundaryA = new MapBoundary().register(externalA, "ext");
            MapBoundary boundaryB = new MapBoundary().register(externalB, "ext");

            BatchedUpdate.DiffGraphBuilder diff = new BatchedUpdate.DiffGraphBuilder();
            DetachedNodeGeneric src = generic("src");
            DetachedNodeGeneric dst = generic("dst");
            diff.addNode(generic("first"));
            diff.addEdge(src, dst, TestEdge.LABEL);
            diff.addNode(generic("last"));
            diff.addEdge(dst, externalA, TestEdge.LABEL);

            byte[] bytes = GraphFragmentCodec.encode(diff, boundaryA, SCHEMA_HASH, READER).get();
            BatchedUpdate.applyDiff(fromDiff, diff);
            BatchedUpdate.applyFragment(fromFragment, bytes, SCHEMA_HASH, boundaryB, null);

            List<String> expected = Arrays.asList("external", "first", "src", "dst", "last");
            assertEquals(expected, names(fromDiff));
            assertEquals(describe(fromDiff), describe(fromFragment));
        }
    }

    @Test
    public void keyPoolHandsOutIdsInTheSameOrder() throws Exception {
        Consumer<BatchedUpdate.DiffGraphBuilder> recipe = diff -> {
            DetachedTestNode src = new DetachedTestNode("src", new DetachedTestNode("held"));
            diff.addNode(generic("first"));
            diff.addEdge(src, generic("dst"), TestEdge.LABEL);
            diff.addNode(generic("last"));
        };
        try (Graph fromDiff = SimpleDomain.newGraph(); Graph fromFragment = SimpleDomain.newGraph()) {
            BatchedUpdate.applyDiff(fromDiff, build(recipe), new CountingKeyPool(1000), null);
            BatchedUpdate.applyFragment(fromFragment, encode(recipe), SCHEMA_HASH, NO_BOUNDARY, new CountingKeyPool(1000));
            assertEquals(Arrays.asList(1000L, 1001L, 1002L, 1003L, 1004L), ids(fromDiff));
            assertEquals(Arrays.asList("first", "src", "dst", "held", "last"), names(fromDiff));
            assertEquals(describeWithIds(fromDiff), describeWithIds(fromFragment));
        }
    }

    @Test
    public void listenerSeesNodesInTheSameOrder() throws Exception {
        Consumer<BatchedUpdate.DiffGraphBuilder> recipe = diff -> {
            DetachedTestNode src = new DetachedTestNode("src", new DetachedTestNode("held"));
            diff.addNode(generic("first"));
            diff.addEdge(src, generic("dst"), TestEdge.LABEL);
            diff.addNode(generic("last"));
        };
        try (Graph fromDiff = SimpleDomain.newGraph(); Graph fromFragment = SimpleDomain.newGraph()) {
            RecordingListener diffListener = new RecordingListener();
            RecordingListener fragmentListener = new RecordingListener();
            BatchedUpdate.applyDiff(fromDiff, build(recipe), null, diffListener);
            BatchedUpdate.applyFragment(fromFragment, encode(recipe), SCHEMA_HASH, NO_BOUNDARY, null, fragmentListener);
            assertEquals(Arrays.asList("first", "src", "dst", "held", "last"), diffListener.initialized);
            assertEquals(diffListener.initialized, fragmentListener.initialized);
        }
    }

    /** chen rebuilds a decoded fragment into a diff of generic nodes, added in local order and then its edges. */
    @Test
    public void decodedFragmentRebuiltAsADiff() throws Exception {
        Consumer<BatchedUpdate.DiffGraphBuilder> recipe = diff -> {
            DetachedNodeGeneric a = generic("a");
            DetachedNodeGeneric b = generic("b");
            diff.addNode(generic("first"));
            diff.addEdge(a, b, TestEdge.LABEL, TestEdge.LONG_PROPERTY, 5L);
            diff.addNode(generic("last"));
            diff.addEdge(b, a, TestEdge.LABEL);
        };
        try (Graph fromDiff = SimpleDomain.newGraph(); Graph rebuilt = SimpleDomain.newGraph()) {
            BatchedUpdate.applyDiff(fromDiff, build(recipe));
            DecodedFragment decoded = GraphFragmentCodec.decode(encode(recipe));
            BatchedUpdate.DiffGraphBuilder diff = new BatchedUpdate.DiffGraphBuilder();
            List<DetachedNodeGeneric> nodes = new ArrayList<>();
            for (DecodedFragment.DecodedNode node : decoded.nodes) {
                DetachedNodeGeneric detached = new DetachedNodeGeneric(node.label, keyValues(node.properties));
                nodes.add(detached);
                diff.addNode(detached);
            }
            for (DecodedFragment.DecodedEdge edge : decoded.edges) {
                diff.addEdge(nodes.get(edge.srcLocal), nodes.get(edge.dstLocal), edge.label, keyValues(edge.properties));
            }
            BatchedUpdate.applyDiff(rebuilt, diff);
            assertEquals(describe(fromDiff), describe(rebuilt));
        }
    }

    /** Version 1 fragments number nodes the old way; reading one would build a graph in the old order. */
    @Test
    public void refusesVersionOneFragments() {
        byte[] bytes = encode(diff -> diff.addNode(generic("a")));
        ByteBuffer.wrap(bytes).putInt(4, 1);
        try {
            GraphFragmentCodec.peek(bytes);
            fail("a version 1 fragment was read");
        } catch (IllegalArgumentException e) {
            assertEquals("Unsupported format version: 1", e.getMessage());
        }
    }

    // ---- generated diffs -------------------------------------------------------------------------------------

    /** Random add-only diffs over a pool of generic and non-generic nodes, some never added explicitly, some
     * reachable only through properties, with property chains and cycles. */
    @Test
    public void randomDiffs() throws Exception {
        for (long seed = 0; seed < 500; seed++) {
            final long s = seed;
            Consumer<BatchedUpdate.DiffGraphBuilder> recipe = diff -> randomDiff(new Random(s), diff);
            try (Graph fromDiff = SimpleDomain.newGraph(); Graph fromFragment = SimpleDomain.newGraph()) {
                BatchedUpdate.applyDiff(fromDiff, build(recipe));
                BatchedUpdate.applyFragment(fromFragment, encode(recipe), SCHEMA_HASH, NO_BOUNDARY, null);
                assertEquals("seed " + seed, describe(fromDiff), describe(fromFragment));
            }
        }
    }

    private static void randomDiff(Random random, BatchedUpdate.DiffGraphBuilder diff) {
        int poolSize = 2 + random.nextInt(14);
        List<DetachedNodeData> pool = new ArrayList<>();
        for (int i = 0; i < poolSize; i++) {
            pool.add(random.nextInt(3) == 0 ? generic("n" + i) : new DetachedTestNode("n" + i));
        }
        for (DetachedNodeData node : pool) {
            if (node instanceof DetachedTestNode && random.nextInt(3) == 0) {
                ((DetachedTestNode) node).containedTestNode = pool.get(random.nextInt(poolSize));
            }
        }
        int changes = 1 + random.nextInt(25);
        for (int i = 0; i < changes; i++) {
            DetachedNodeData a = pool.get(random.nextInt(poolSize));
            if (random.nextInt(3) == 0) {
                diff.addNode(a);
            } else {
                DetachedNodeData b = pool.get(random.nextInt(poolSize));
                if (random.nextBoolean()) diff.addEdge(a, b, TestEdge.LABEL);
                else diff.addEdge(a, b, TestEdge.LABEL, TestEdge.LONG_PROPERTY, (long) i);
            }
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private static BatchedUpdate.DiffGraph build(Consumer<BatchedUpdate.DiffGraphBuilder> recipe) {
        BatchedUpdate.DiffGraphBuilder diff = new BatchedUpdate.DiffGraphBuilder();
        recipe.accept(diff);
        return diff.build();
    }

    private static byte[] encode(Consumer<BatchedUpdate.DiffGraphBuilder> recipe) {
        Optional<byte[]> bytes = GraphFragmentCodec.encode(build(recipe), NO_BOUNDARY, SCHEMA_HASH, READER);
        assertTrue("the diff was not encodable", bytes.isPresent());
        return bytes.get();
    }

    private static void assertSameOrder(Consumer<BatchedUpdate.DiffGraphBuilder> recipe, String... expected) {
        try (Graph fromDiff = SimpleDomain.newGraph(); Graph fromFragment = SimpleDomain.newGraph()) {
            BatchedUpdate.applyDiff(fromDiff, build(recipe));
            BatchedUpdate.applyFragment(fromFragment, encode(recipe), SCHEMA_HASH, NO_BOUNDARY, null);
            assertEquals("applyDiff", Arrays.asList(expected), names(fromDiff));
            assertEquals("applyFragment", describe(fromDiff), describe(fromFragment));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static List<Node> inIdOrder(Graph graph) {
        List<Node> nodes = new ArrayList<>();
        graph.nodes().forEachRemaining(nodes::add);
        nodes.sort(Comparator.comparingLong(Node::id));
        return nodes;
    }

    private static List<String> names(Graph graph) {
        List<String> names = new ArrayList<>();
        for (Node node : inIdOrder(graph)) names.add((String) node.property(TestNode.STRING_PROPERTY));
        return names;
    }

    private static List<Long> ids(Graph graph) {
        List<Long> ids = new ArrayList<>();
        for (Node node : inIdOrder(graph)) ids.add(node.id());
        return ids;
    }

    /** Each node in id order with its position instead of its id, its name, the position of the node its
     * property holds, and its outgoing edges in order. Equal descriptions mean equal graphs with the same relative
     * node order. */
    private static List<String> describe(Graph graph) {
        List<Node> nodes = inIdOrder(graph);
        Map<Long, Integer> position = new HashMap<>();
        for (int i = 0; i < nodes.size(); i++) position.put(nodes.get(i).id(), i);
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            Node node = nodes.get(i);
            StringBuilder line = new StringBuilder();
            line.append(i).append(' ').append(node.label()).append(' ').append(node.property(TestNode.STRING_PROPERTY));
            Object held = node.property(TestNode.CONTAINED_TESTNODE_PROPERTY);
            if (held != null) line.append(" holds ").append(position.get(((Node) held).id()));
            Iterator<Edge> edges = node.outE();
            while (edges.hasNext()) {
                Edge edge = edges.next();
                line.append(" -").append(edge.label());
                Object value = edge.property(TestEdge.LONG_PROPERTY);
                if (value != null) line.append('(').append(value).append(')');
                line.append("-> ").append(position.get(edge.inNode().id()));
            }
            lines.add(line.toString());
        }
        return lines;
    }

    private static List<String> describeWithIds(Graph graph) {
        List<String> lines = new ArrayList<>(describe(graph));
        lines.add(ids(graph).toString());
        return lines;
    }

    private static Object[] keyValues(Map<String, Object> properties) {
        Object[] keyValues = new Object[properties.size() * 2];
        int i = 0;
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            keyValues[i++] = entry.getKey();
            keyValues[i++] = entry.getValue();
        }
        return keyValues;
    }

    private static final class CountingKeyPool implements BatchedUpdate.KeyPool {
        private long next;

        CountingKeyPool(long first) {
            this.next = first;
        }

        @Override
        public long next() {
            return next++;
        }
    }

    private static final class RecordingListener implements BatchedUpdate.ModificationListener {
        final List<String> initialized = new ArrayList<>();

        @Override
        public void onAfterInitNewNode(Node node) {
            initialized.add((String) node.property(TestNode.STRING_PROPERTY));
        }

        @Override public void onAfterAddNewEdge(Edge edge) {}
        @Override public void onBeforePropertyChange(Node node, String key) {}
        @Override public void onAfterPropertyChange(Node node, String key, Object value) {}
        @Override public void onBeforeRemoveNode(Node node) {}
        @Override public void onBeforeRemoveEdge(Edge edge) {}
        @Override public void finish() {}
    }

    private static final class MapBoundary implements BoundaryResolver {
        private final Map<SymbolicKey, Node> byKey = new HashMap<>();
        private final Map<Long, SymbolicKey> byId = new HashMap<>();

        MapBoundary register(Node node, String key) {
            byKey.put(new SymbolicKey(key), node);
            byId.put(node.id(), new SymbolicKey(key));
            return this;
        }

        @Override
        public Optional<SymbolicKey> getSymbolicKey(NodeOrDetachedNode node) {
            return node instanceof Node ? Optional.ofNullable(byId.get(((Node) node).id())) : Optional.empty();
        }

        @Override
        public Optional<Node> resolve(SymbolicKey key) {
            return Optional.ofNullable(byKey.get(key));
        }
    }
}
