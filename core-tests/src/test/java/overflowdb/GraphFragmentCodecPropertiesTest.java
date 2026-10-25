package overflowdb;

import org.junit.Test;
import overflowdb.storage.GraphFragmentCodec;
import overflowdb.testdomains.simple.SimpleDomain;
import overflowdb.testdomains.simple.TestNode;

import java.util.*;

import static org.junit.Assert.*;

/**
 * GraphFragmentCodec.encode writes a fragment only if it can write every node and edge exactly as given; otherwise it
 * returns empty, so that a caller caching fragments recomputes instead of building later graphs from lossy ones
 * (https://github.com/AppThreat/overflowdb2/issues/21).
 */
public class GraphFragmentCodecPropertiesTest {

    private static final long SCHEMA_HASH = 42L;

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

    /** A detached node class the codec does not know, like cpg2's generated New* nodes. */
    public static class ForeignNode implements DetachedNodeData {
        private Object refOrId;
        final Map<String, Object> props;

        public ForeignNode(Map<String, Object> props) {
            this.props = props;
        }

        @Override
        public String label() {
            return "TEST_NODE";
        }

        public Map<String, Object> properties() {
            return props;
        }

        @Override
        public Object getRefOrId() {
            return refOrId;
        }

        @Override
        public void setRefOrId(Object refOrId) {
            this.refOrId = refOrId;
        }
    }

    /** The reproduction from issue 21: reflection cannot call `properties()` on a private class. */
    private static final class HiddenNode implements DetachedNodeData {
        private Object refOrId;

        @Override
        public String label() {
            return "TEST_NODE";
        }

        public Map<String, Object> properties() {
            return Map.of("NAME", "app");
        }

        @Override
        public Object getRefOrId() {
            return refOrId;
        }

        @Override
        public void setRefOrId(Object refOrId) {
            this.refOrId = refOrId;
        }
    }

    /** No `properties()` at all. */
    public static class BareNode implements DetachedNodeData {
        private Object refOrId;

        @Override
        public String label() {
            return "TEST_NODE";
        }

        @Override
        public Object getRefOrId() {
            return refOrId;
        }

        @Override
        public void setRefOrId(Object refOrId) {
            this.refOrId = refOrId;
        }
    }

    public static class ThrowingNode extends ForeignNode {
        public ThrowingNode() {
            super(null);
        }

        @Override
        public Map<String, Object> properties() {
            throw new IllegalStateException("not readable");
        }
    }

    /** `properties()` that returns a scala Map, as cpg2's New* nodes do. */
    public static class ScalaPropertiesNode extends BareNode {
        public scala.collection.Map<String, Object> properties() {
            return scala.jdk.javaapi.CollectionConverters.asScala(Map.<String, Object>of("NAME", "app"));
        }
    }

    private static Optional<byte[]> encode(DetachedNodeData... nodes) {
        BatchedUpdate.DiffGraphBuilder diff = new BatchedUpdate.DiffGraphBuilder();
        for (DetachedNodeData node : nodes) diff.addNode(node);
        return GraphFragmentCodec.encode(diff, NO_BOUNDARY, SCHEMA_HASH);
    }

    private static Map<String, Object> decodedProperties(byte[] bytes, int node) {
        return GraphFragmentCodec.decode(bytes).nodes.get(node).properties;
    }

    @Test
    public void refusesANodeWhoseClassReflectionCannotAccess() {
        assertEquals(Optional.empty(), encode(new HiddenNode()));
    }

    @Test
    public void refusesANodeWithoutProperties() {
        assertEquals(Optional.empty(), encode(new BareNode()));
    }

    @Test
    public void refusesANodeWhosePropertiesThrow() {
        assertEquals(Optional.empty(), encode(new ThrowingNode()));
    }

    /** Readable by reflection on the JVM but not in a native image: the codec must behave the same in both. */
    @Test
    public void refusesAForeignNodeEvenWhenReflectionCouldReadIt() {
        assertEquals(Optional.empty(), encode(new ForeignNode(Map.of("NAME", "app"))));
        assertEquals(Optional.empty(), encode(new ScalaPropertiesNode()));
    }

    @Test
    public void refusesAForeignNodeReachedOnlyAsAnEdgeEndpoint() {
        DetachedNodeGeneric known = new DetachedNodeGeneric(TestNode.LABEL, TestNode.STRING_PROPERTY, "a");
        BatchedUpdate.DiffGraphBuilder diff = new BatchedUpdate.DiffGraphBuilder()
                .addNode(known)
                .addEdge(known, new HiddenNode(), "EDGE");
        assertEquals(Optional.empty(), GraphFragmentCodec.encode(diff, NO_BOUNDARY, SCHEMA_HASH));
    }

    @Test
    public void refusesAScalaCollectionValue() {
        scala.collection.immutable.List<String> names = scala.jdk.javaapi.CollectionConverters.asScala(List.of("a", "b")).toList();
        assertEquals(Optional.empty(), encode(new DetachedNodeGeneric(TestNode.LABEL, TestNode.STRING_LIST_PROPERTY, names)));
    }

    @Test
    public void refusesAValueOfAnUnsupportedType() {
        assertEquals(Optional.empty(), encode(new DetachedNodeGeneric(TestNode.LABEL, "SET", Set.of("a"))));
        assertEquals(Optional.empty(), encode(new DetachedNodeGeneric(TestNode.LABEL, "LIST", List.of(Optional.of(1)))));
    }

    @Test
    public void refusesAnEdgePropertyOfAnUnsupportedType() {
        DetachedNodeGeneric a = new DetachedNodeGeneric(TestNode.LABEL);
        DetachedNodeGeneric b = new DetachedNodeGeneric(TestNode.LABEL);
        BatchedUpdate.DiffGraphBuilder diff = new BatchedUpdate.DiffGraphBuilder()
                .addNode(a).addNode(b).addEdge(a, b, "EDGE", "WHEN", new Date());
        assertEquals(Optional.empty(), GraphFragmentCodec.encode(diff, NO_BOUNDARY, SCHEMA_HASH));
    }

    @Test
    public void refusesAPropertyThatRefersToANodeOutsideTheFragment() throws Exception {
        try (Graph graph = SimpleDomain.newGraph()) {
            Node outside = graph.addNode(TestNode.LABEL);
            assertEquals(Optional.empty(), encode(new DetachedNodeGeneric(TestNode.LABEL, "REF", outside)));
        }
    }

    @Test
    public void refusesMalformedKeyValues() {
        assertEquals(Optional.empty(), encode(new DetachedNodeGeneric(TestNode.LABEL, "NAME")));
        assertEquals(Optional.empty(), encode(new DetachedNodeGeneric(TestNode.LABEL, 1, "app")));
    }

    @Test
    public void readsForeignNodesThroughTheCallersReader() {
        BatchedUpdate.DiffGraphBuilder diff = new BatchedUpdate.DiffGraphBuilder()
                .addNode(new HiddenNode())
                .addNode(new DetachedNodeGeneric(TestNode.LABEL, TestNode.STRING_PROPERTY, "generic"));
        List<DetachedNodeData> asked = new ArrayList<>();
        Optional<byte[]> encoded = GraphFragmentCodec.encode(diff, NO_BOUNDARY, SCHEMA_HASH, node -> {
            asked.add(node);
            return ((HiddenNode) node).properties();
        });
        assertTrue(encoded.isPresent());
        assertEquals(Map.of("NAME", "app"), decodedProperties(encoded.get(), 0));
        assertEquals(Map.of(TestNode.STRING_PROPERTY, "generic"), decodedProperties(encoded.get(), 1));
        // generic nodes are read directly, and every other node exactly once
        assertEquals(1, asked.size());
    }

    @Test
    public void refusesWhenTheReaderDoesNotKnowTheNode() {
        BatchedUpdate.DiffGraphBuilder diff = new BatchedUpdate.DiffGraphBuilder().addNode(new BareNode());
        assertEquals(Optional.empty(), GraphFragmentCodec.encode(diff, NO_BOUNDARY, SCHEMA_HASH, node -> null));
    }
}
