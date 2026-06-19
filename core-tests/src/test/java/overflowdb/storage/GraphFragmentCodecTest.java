package overflowdb.storage;

import org.junit.Test;
import overflowdb.*;
import overflowdb.testdomains.simple.SimpleDomain;
import overflowdb.testdomains.simple.TestEdge;
import overflowdb.testdomains.simple.TestNode;
import overflowdb.util.DiffTool;

import java.io.IOException;
import java.util.*;

import static org.junit.Assert.*;

public class GraphFragmentCodecTest {

    private static final long SCHEMA_HASH = 123456789L;

    private static class DummyBoundaryResolver implements BoundaryResolver {
        private final Map<SymbolicKey, Node> keyToNode = new HashMap<>();
        private final Map<NodeOrDetachedNode, SymbolicKey> nodeToKey = new IdentityHashMap<>();

        public void registerBoundary(Node node, String keyStr) {
            SymbolicKey key = new SymbolicKey(keyStr);
            keyToNode.put(key, node);
            nodeToKey.put(node, key);
        }

        public void registerExport(DetachedNodeData node, String keyStr) {
            SymbolicKey key = new SymbolicKey(keyStr);
            nodeToKey.put(node, key);
        }

        @Override
        public Optional<SymbolicKey> getSymbolicKey(NodeOrDetachedNode node) {
            SymbolicKey key = nodeToKey.get(node);
            if (key == null && node instanceof NodeDb) {
                key = nodeToKey.get(((NodeDb) node).ref);
            }
            if (key == null && node instanceof NodeRef) {
                key = nodeToKey.get(((NodeRef<?>) node).get());
            }
            return Optional.ofNullable(key);
        }

        @Override
        public Optional<Node> resolve(SymbolicKey key) {
            return Optional.ofNullable(keyToNode.get(key));
        }
    }

    @Test
    public void testRoundTripAndDiffTool() throws IOException {
        try (Graph graph1 = SimpleDomain.newGraph();
             Graph graph2 = SimpleDomain.newGraph()) {

            // Create original graph using DiffGraphBuilder
            BatchedUpdate.DiffGraphBuilder diffBuilder = new BatchedUpdate.DiffGraphBuilder();
            DetachedNodeGeneric node1 = new DetachedNodeGeneric(TestNode.LABEL, TestNode.STRING_PROPERTY, "Node 1", TestNode.INT_PROPERTY, 42);
            DetachedNodeGeneric node2 = new DetachedNodeGeneric(TestNode.LABEL, TestNode.STRING_PROPERTY, "Node 2", TestNode.INT_PROPERTY, 100);
            diffBuilder.addNode(node1);
            diffBuilder.addNode(node2);
            diffBuilder.addEdge(node1, node2, TestEdge.LABEL, TestEdge.LONG_PROPERTY, 999L);

            BatchedUpdate.DiffGraph diffGraph = diffBuilder.build();
            BatchedUpdate.applyDiff(graph1, diffGraph);

            // Encode using codec
            DummyBoundaryResolver boundary = new DummyBoundaryResolver();
            Optional<byte[]> encodedBytesOpt = GraphFragmentCodec.encode(diffGraph, boundary, SCHEMA_HASH);
            assertTrue(encodedBytesOpt.isPresent());
            byte[] encodedBytes = encodedBytesOpt.get();

            // Peek & Validate
            GraphFragmentCodec.FragmentHeader header = GraphFragmentCodec.peek(encodedBytes);
            assertEquals(GraphFragmentCodec.FORMAT_VERSION, header.formatVersion);
            assertEquals(SCHEMA_HASH, header.schemaHash);

            // Apply fragment into graph2
            BatchedUpdate.applyFragment(graph2, encodedBytes, SCHEMA_HASH, boundary, null);

            // Verify they are identical modulo IDs using DiffTool
            List<String> diff = DiffTool.compare(graph1, graph2);
            assertTrue("Graphs should be structurally identical: " + diff, diff.isEmpty());
        }
    }

    @Test
    public void testBoundaryRefResolution() throws IOException {
        try (Graph graph = SimpleDomain.newGraph()) {
            // Add a live node to the graph representing an external node
            Node externalNode = graph.addNode(TestNode.LABEL, TestNode.STRING_PROPERTY, "ExternalNode");

            DummyBoundaryResolver boundary = new DummyBoundaryResolver();
            boundary.registerBoundary(externalNode, "external-symbol-key");

            // Build a fragment with a node that has an outgoing edge to the external node
            BatchedUpdate.DiffGraphBuilder diffBuilder = new BatchedUpdate.DiffGraphBuilder();
            DetachedNodeGeneric internalNode = new DetachedNodeGeneric(TestNode.LABEL, TestNode.STRING_PROPERTY, "InternalNode");
            diffBuilder.addNode(internalNode);
            diffBuilder.addEdge(internalNode, externalNode, TestEdge.LABEL);

            // Encode the fragment
            Optional<byte[]> encodedBytesOpt = GraphFragmentCodec.encode(diffBuilder, boundary, SCHEMA_HASH);
            assertTrue(encodedBytesOpt.isPresent());
            byte[] encodedBytes = encodedBytesOpt.get();

            // Verify boundary refs decoded count
            DecodedFragment decoded = GraphFragmentCodec.decode(encodedBytes);
            assertEquals(1, decoded.boundaryRefs.size());
            assertEquals("external-symbol-key", decoded.boundaryRefs.get(0).key.key());

            // Apply fragment to graph
            BatchedUpdate.applyFragment(graph, encodedBytes, SCHEMA_HASH, boundary, null);

            // Check that the internal node was created and connected to the external node
            assertEquals(2, graph.nodeCount());
            Node internalNodeApplied = null;
            Iterator<Node> it = graph.nodes();
            while (it.hasNext()) {
                Node n = it.next();
                if (n.id() != externalNode.id()) {
                    internalNodeApplied = n;
                }
            }
            assertNotNull(internalNodeApplied);
            assertEquals("InternalNode", internalNodeApplied.property(TestNode.STRING_PROPERTY));

            // Verify edge creation
            Iterator<Edge> edges = internalNodeApplied.outE(TestEdge.LABEL);
            assertTrue(edges.hasNext());
            Edge edge = edges.next();
            assertEquals(externalNode.id(), edge.inNode().id());
        }
    }

    @Test
    public void testHeaderGuardFailure() throws IOException {
        try (Graph graph = SimpleDomain.newGraph()) {
            BatchedUpdate.DiffGraphBuilder diffBuilder = new BatchedUpdate.DiffGraphBuilder();
            DetachedNodeGeneric node = new DetachedNodeGeneric(TestNode.LABEL, TestNode.STRING_PROPERTY, "Test");
            diffBuilder.addNode(node);

            DummyBoundaryResolver boundary = new DummyBoundaryResolver();
            byte[] encodedBytes = GraphFragmentCodec.encode(diffBuilder, boundary, SCHEMA_HASH).get();

            // 1. Wrong schema hash
            try {
                BatchedUpdate.applyFragment(graph, encodedBytes, 9999999L, boundary, null);
                fail("Should throw mismatch schema hash");
            } catch (IllegalArgumentException e) {
                assertTrue(e.getMessage().contains("Schema hash mismatch"));
            }

            // 2. Corrupt CRC32C checksum
            byte[] corruptCrcBytes = encodedBytes.clone();
            corruptCrcBytes[16] ^= 0xFF; // Corrupt a byte in CRC checksum field
            try {
                GraphFragmentCodec.peek(corruptCrcBytes);
                fail("Should throw checksum mismatch");
            } catch (IllegalArgumentException e) {
                assertTrue(e.getMessage().contains("CRC32C checksum mismatch"));
            }

            // 3. Wrong format version
            byte[] corruptVersionBytes = encodedBytes.clone();
            corruptVersionBytes[7] ^= 0xFF; // Modify format version field
            try {
                GraphFragmentCodec.peek(corruptVersionBytes);
                fail("Should throw format version mismatch");
            } catch (IllegalArgumentException e) {
                assertTrue(e.getMessage().contains("Unsupported format version"));
            }
        }
    }

    @Test
    public void testLosslessValues() throws IOException {
        try (Graph graph = SimpleDomain.newGraph()) {
            BatchedUpdate.DiffGraphBuilder diffBuilder = new BatchedUpdate.DiffGraphBuilder();

            long largeLong = (long) Math.pow(2, 54) + 12345L;
            char charVal = 'Ω';
            byte byteVal = 127;
            short shortVal = 32767;
            int[] intArray = new int[]{1, 2, 3, 4};
            String[] strArray = new String[]{"hello", "world"};

            DetachedNodeGeneric node = new DetachedNodeGeneric(
                    TestNode.LABEL,
                    TestNode.STRING_PROPERTY, "Lossless test",
                    TestNode.INT_PROPERTY,LargeLongToIntFallback(largeLong), // simpledomain only has specific properties, but we check map
                    TestNode.STRING_LIST_PROPERTY, Arrays.asList(strArray)
            );
            // Put other properties via Map/reflection-compatible generic list if SimpleDomain allows
            // Wait, we can verify raw properties decoding of DecodedFragment to ensure lossless types roundtrip perfectly!
            diffBuilder.addNode(node);

            DummyBoundaryResolver boundary = new DummyBoundaryResolver();
            byte[] encodedBytes = GraphFragmentCodec.encode(diffBuilder, boundary, SCHEMA_HASH).get();

            DecodedFragment decoded = GraphFragmentCodec.decode(encodedBytes);
            assertEquals(1, decoded.nodes.size());
            DecodedFragment.DecodedNode decodedNode = decoded.nodes.get(0);

            // Re-packing a custom list of properties to test all kinds of primitive values directly
            Object[] keyvalues = new Object[]{
                    "largeLong", largeLong,
                    "charVal", charVal,
                    "byteVal", byteVal,
                    "shortVal", shortVal,
                    "intArray", intArray,
                    "strArray", strArray
            };
            DetachedNodeGeneric customNode = new DetachedNodeGeneric(TestNode.LABEL, keyvalues);
            BatchedUpdate.DiffGraphBuilder customBuilder = new BatchedUpdate.DiffGraphBuilder().addNode(customNode);
            byte[] customBytes = GraphFragmentCodec.encode(customBuilder, boundary, SCHEMA_HASH).get();

            DecodedFragment customDecoded = GraphFragmentCodec.decode(customBytes);
            Map<String, Object> props = customDecoded.nodes.get(0).properties;

            assertEquals(largeLong, props.get("largeLong"));
            assertEquals(charVal, props.get("charVal"));
            assertEquals(byteVal, props.get("byteVal"));
            assertEquals(shortVal, props.get("shortVal"));
            assertArrayEquals(intArray, (int[]) props.get("intArray"));
            assertArrayEquals(strArray, (Object[]) props.get("strArray"));
        }
    }

    private static int LargeLongToIntFallback(long val) {
        return (int) (val % Integer.MAX_VALUE);
    }
}
