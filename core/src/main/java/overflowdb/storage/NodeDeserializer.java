package overflowdb.storage;

import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.value.ArrayValue;
import org.msgpack.value.ImmutableValue;
import org.msgpack.value.Value;
import overflowdb.Direction;
import overflowdb.Graph;
import overflowdb.NodeDb;
import overflowdb.NodeFactory;
import overflowdb.NodeRef;
import overflowdb.NodeLayoutInformation;
import overflowdb.util.PropertyHelper;
import overflowdb.util.StringInterner;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class NodeDeserializer extends BookKeeper {
    /** Shared, immutable (zero-length) result for nodes and edges that have no stored properties.
     * Property-less AST/CFG edges and nodes are the common case on large graphs, so reusing one
     * empty array avoids millions of throwaway allocations on the deserialization hot path. */
    private static final Object[] EMPTY_PROPERTIES = new Object[0];

    protected final Graph graph;
    private final Map<String, NodeFactory<?>> nodeFactoryByLabel;
    private final OdbStorage storage;
    private final StringInterner stringInterner;
    private final ThreadLocal<Map<Integer, String>> stringCache = ThreadLocal.withInitial(HashMap::new);

    public NodeDeserializer(Graph graph, Map<String, NodeFactory<?>> nodeFactoryByLabel, boolean statsEnabled, OdbStorage storage) {
        super(statsEnabled);
        this.graph = graph;
        this.stringInterner = graph.getStringInterner();
        this.nodeFactoryByLabel = nodeFactoryByLabel;
        this.storage = storage;
    }

    public final NodeDb deserialize(byte[] bytes) throws  IOException {
        return deserialize(bytes, null);
    }

    public final NodeDb deserialize(byte[] bytes, NodeRef ref) throws IOException {
        long startTimeNanos = getStartTimeNanos();
        if (null == bytes)
            return null;

        try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(bytes)) {
            final long id = unpacker.unpackLong();
            final int labelStringId = unpacker.unpackInt();
            final Object[] properties = unpackProperties(unpacker);

            Map<Integer, String> cache = stringCache.get();
            final String label = cache.computeIfAbsent(labelStringId,
                    storage::reverseLookupStringToIntMapping);
            NodeDb node = getNodeFactory(label).createNode(graph, id, ref);
            PropertyHelper.attachProperties(node, properties);

            deserializeEdges(unpacker, node, Direction.OUT);
            deserializeEdges(unpacker, node, Direction.IN);

            node.markAsClean();

            if (statsEnabled) recordStatistics(startTimeNanos);
            return node;
        } finally {
            stringCache.get().clear();
        }
    }

    private void deserializeEdges(MessageUnpacker unpacker, NodeDb node, Direction direction) throws IOException {
        int edgeTypesCount = unpacker.unpackInt();
        for (int edgeTypeIdx = 0; edgeTypeIdx < edgeTypesCount; edgeTypeIdx++) {
            int edgeLabelId = unpacker.unpackInt();
            Map<Integer, String> cache = stringCache.get();
            String edgeLabel = cache.computeIfAbsent(edgeLabelId,
                    storage::reverseLookupStringToIntMapping);
            int edgeCount = unpacker.unpackInt();
            boolean hasProperties = hasEdgeProperties(node, edgeLabel);
            for (int edgeIdx = 0; edgeIdx < edgeCount; edgeIdx++) {
                long adjacentNodeId = unpacker.unpackLong();
                NodeRef<?> adjacentNode = (NodeRef) graph.node(adjacentNodeId);
                Object[] edgeProperties = hasProperties ? unpackProperties(unpacker) : EMPTY_PROPERTIES;
                node.storeAdjacentNode(direction, edgeLabel, adjacentNode, edgeProperties);
            }
        }
    }

    private boolean hasEdgeProperties(NodeDb node, String edgeLabel) {
        NodeLayoutInformation layout = node.layoutInformation();
        if (layout != null) {
            Set<String> keys = layout.edgePropertyKeys(edgeLabel);
            return keys != null && !keys.isEmpty();
        }
        return false;
    }

    /**
     * only deserialize the part we're keeping in memory, used during startup when initializing from disk
     */
    public final NodeRef<?> deserializeRef(byte[] bytes) throws IOException {
        try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(bytes)) {
            long id = unpacker.unpackLong();
            int labelStringId = unpacker.unpackInt();
            String label = storage.reverseLookupStringToIntMapping(labelStringId);
            return createNodeRef(id, label);
        }
    }

    private Object[] unpackProperties(MessageUnpacker unpacker) throws IOException {
        int propertyCount = unpacker.unpackMapHeader();
        if (propertyCount == 0) return EMPTY_PROPERTIES;
        Object[] res = new Object[propertyCount * 2];
        int resIdx = 0;
        for (int propertyIdx = 0; propertyIdx < propertyCount; propertyIdx++) {
            int keyId = unpacker.unpackInt();
            Map<Integer, String> cache = stringCache.get();
            final String key = cache.computeIfAbsent(keyId,
                    storage::reverseLookupStringToIntMapping);
            final Object unpackedProperty = unpackDirectValue(unpacker);
            res[resIdx++] = key;
            res[resIdx++] = unpackedProperty;
        }
        return res;
    }

    private Object unpackDirectValue(final MessageUnpacker unpacker) throws IOException {
        unpacker.unpackArrayHeader(); // array structure is always size 2: [typeId, value]
        final byte valueTypeId = unpacker.unpackByte();
        return switch (ValueTypes.lookup(valueTypeId)) {
            case UNKNOWN -> {
                unpacker.unpackNil();
                yield null;
            }
            case NODE_REF -> {
                long id = unpacker.unpackLong();
                yield graph.node(id);
            }
            case BOOLEAN -> unpacker.unpackBoolean();
            case STRING -> stringInterner.intern(unpacker.unpackString());
            case BYTE -> unpacker.unpackByte();
            case SHORT -> unpacker.unpackShort();
            case INTEGER -> unpacker.unpackInt();
            case LONG -> unpacker.unpackLong();
            case FLOAT -> unpacker.unpackFloat();
            case DOUBLE -> unpacker.unpackDouble();
            case LIST -> {
                int size = unpacker.unpackArrayHeader();
                final List<Object> deserializedList = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    deserializedList.add(unpackDirectValue(unpacker));
                }
                yield deserializedList;
            }
            case CHARACTER -> (char) unpacker.unpackInt();
            case ARRAY_BYTE -> {
                int size = unpacker.unpackArrayHeader();
                byte[] arr = new byte[size];
                for (int i = 0; i < size; i++) arr[i] = unpacker.unpackByte();
                yield arr;
            }
            case ARRAY_SHORT -> {
                int size = unpacker.unpackArrayHeader();
                short[] arr = new short[size];
                for (int i = 0; i < size; i++) arr[i] = unpacker.unpackShort();
                yield arr;
            }
            case ARRAY_INT -> {
                int size = unpacker.unpackArrayHeader();
                int[] arr = new int[size];
                for (int i = 0; i < size; i++) arr[i] = unpacker.unpackInt();
                yield arr;
            }
            case ARRAY_LONG -> {
                int size = unpacker.unpackArrayHeader();
                long[] arr = new long[size];
                for (int i = 0; i < size; i++) arr[i] = unpacker.unpackLong();
                yield arr;
            }
            case ARRAY_FLOAT -> {
                int size = unpacker.unpackArrayHeader();
                float[] arr = new float[size];
                for (int i = 0; i < size; i++) arr[i] = unpacker.unpackFloat();
                yield arr;
            }
            case ARRAY_DOUBLE -> {
                int size = unpacker.unpackArrayHeader();
                double[] arr = new double[size];
                for (int i = 0; i < size; i++) arr[i] = unpacker.unpackDouble();
                yield arr;
            }
            case ARRAY_CHAR -> {
                int size = unpacker.unpackArrayHeader();
                char[] arr = new char[size];
                for (int i = 0; i < size; i++) arr[i] = (char) unpacker.unpackInt();
                yield arr;
            }
            case ARRAY_BOOL -> {
                int size = unpacker.unpackArrayHeader();
                boolean[] arr = new boolean[size];
                for (int i = 0; i < size; i++) arr[i] = unpacker.unpackBoolean();
                yield arr;
            }
            case ARRAY_OBJECT -> {
                int size = unpacker.unpackArrayHeader();
                Object[] arr = new Object[size];
                for (int i = 0; i < size; i++) arr[i] = unpackDirectValue(unpacker);
                yield arr;
            }
        };
    }

    protected final NodeRef<?> createNodeRef(long id, String label) {
        return getNodeFactory(label).createNodeRef(graph, id);
    }

    private NodeFactory<?> getNodeFactory(String label) {
        if (!nodeFactoryByLabel.containsKey(label))
            throw new AssertionError(String.format("nodeFactory not found for label=%s", label));

        return nodeFactoryByLabel.get(label);
    }
}