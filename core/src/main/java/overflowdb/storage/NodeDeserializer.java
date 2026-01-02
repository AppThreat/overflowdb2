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
import overflowdb.util.PropertyHelper;
import overflowdb.util.StringInterner;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class NodeDeserializer extends BookKeeper {
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
            for (int edgeIdx = 0; edgeIdx < edgeCount; edgeIdx++) {
                long adjacentNodeId = unpacker.unpackLong();
                NodeRef<?> adjacentNode = (NodeRef) graph.node(adjacentNodeId);
                Object[] edgeProperties = unpackProperties(unpacker);
                node.storeAdjacentNode(direction, edgeLabel, adjacentNode, edgeProperties);
            }
        }
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
        Object[] res = new Object[propertyCount * 2];
        int resIdx = 0;
        for (int propertyIdx = 0; propertyIdx < propertyCount; propertyIdx++) {
            int keyId = unpacker.unpackInt();
            Map<Integer, String> cache = stringCache.get();
            final String key = cache.computeIfAbsent(keyId,
                    storage::reverseLookupStringToIntMapping);
            final ImmutableValue unpackedValue = unpacker.unpackValue();
            final Object unpackedProperty = unpackValue(unpackedValue.asArrayValue());
            res[resIdx++] = key;
            res[resIdx++] = unpackedProperty;
        }
        return res;
    }

    private Object unpackValue(final ArrayValue packedValueAndType) {
        final Iterator<Value> iter = packedValueAndType.iterator();
        final byte valueTypeId = iter.next().asIntegerValue().asByte();
        final Value value = iter.next();

        return switch (ValueTypes.lookup(valueTypeId)) {
            case UNKNOWN -> null;
            case NODE_REF -> {
                long id = value.asIntegerValue().asLong();
                yield graph.node(id);
            }
            case BOOLEAN -> value.asBooleanValue().getBoolean();
            case STRING -> stringInterner.intern(value.asStringValue().asString());
            case BYTE -> value.asIntegerValue().asByte();
            case SHORT -> value.asIntegerValue().asShort();
            case INTEGER -> value.asIntegerValue().asInt();
            case LONG -> value.asIntegerValue().asLong();
            case FLOAT -> value.asFloatValue().toFloat();
            case DOUBLE -> value.asFloatValue().toDouble();
            case LIST -> deserializeList(value.asArrayValue());
            case CHARACTER -> (char) value.asIntegerValue().asInt();
            case ARRAY_BYTE -> deserializeArrayByte(value.asArrayValue());
            case ARRAY_SHORT -> deserializeArrayShort(value.asArrayValue());
            case ARRAY_INT -> deserializeArrayInt(value.asArrayValue());
            case ARRAY_LONG -> deserializeArrayLong(value.asArrayValue());
            case ARRAY_FLOAT -> deserializeArrayFloat(value.asArrayValue());
            case ARRAY_DOUBLE -> deserializeArrayDouble(value.asArrayValue());
            case ARRAY_CHAR -> deserializeArrayChar(value.asArrayValue());
            case ARRAY_BOOL -> deserializeArrayBoolean(value.asArrayValue());
            case ARRAY_OBJECT -> deserializeArrayObject(value.asArrayValue());
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

    private Object deserializeList(ArrayValue arrayValue) {
        final List deserializedList = new ArrayList(arrayValue.size());
        for (Value value : arrayValue) {
            deserializedList.add(unpackValue(value.asArrayValue()));
        }
        return deserializedList;
    }

    private byte[] deserializeArrayByte(ArrayValue arrayValue) {
        byte[] arr = new byte[arrayValue.size()];
        int i = 0; for(Value v : arrayValue) arr[i++] = v.asIntegerValue().asByte();
        return arr;
    }
    private short[] deserializeArrayShort(ArrayValue arrayValue) {
        short[] arr = new short[arrayValue.size()];
        int i = 0; for(Value v : arrayValue) arr[i++] = v.asIntegerValue().asShort();
        return arr;
    }
    private int[] deserializeArrayInt(ArrayValue arrayValue) {
        int[] arr = new int[arrayValue.size()];
        int i = 0; for(Value v : arrayValue) arr[i++] = v.asIntegerValue().asInt();
        return arr;
    }
    private long[] deserializeArrayLong(ArrayValue arrayValue) {
        long[] arr = new long[arrayValue.size()];
        int i = 0; for(Value v : arrayValue) arr[i++] = v.asIntegerValue().asLong();
        return arr;
    }
    private float[] deserializeArrayFloat(ArrayValue arrayValue) {
        float[] arr = new float[arrayValue.size()];
        int i = 0; for(Value v : arrayValue) arr[i++] = v.asFloatValue().toFloat();
        return arr;
    }
    private double[] deserializeArrayDouble(ArrayValue arrayValue) {
        double[] arr = new double[arrayValue.size()];
        int i = 0; for(Value v : arrayValue) arr[i++] = v.asFloatValue().toDouble();
        return arr;
    }
    private char[] deserializeArrayChar(ArrayValue arrayValue) {
        char[] arr = new char[arrayValue.size()];
        int i = 0; for(Value v : arrayValue) arr[i++] = (char) v.asIntegerValue().asInt();
        return arr;
    }
    private boolean[] deserializeArrayBoolean(ArrayValue arrayValue) {
        boolean[] arr = new boolean[arrayValue.size()];
        int i = 0; for(Value v : arrayValue) arr[i++] = v.asBooleanValue().getBoolean();
        return arr;
    }
    private Object[] deserializeArrayObject(ArrayValue arrayValue) {
        Object[] arr = new Object[arrayValue.size()];
        int i = 0; for(Value v : arrayValue) arr[i++] = unpackValue(v.asArrayValue());
        return arr;
    }
}