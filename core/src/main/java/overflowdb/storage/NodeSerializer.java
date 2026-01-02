package overflowdb.storage;

import overflowdb.AdjacentNodes;
import overflowdb.Node;
import overflowdb.NodeLayoutInformation;
import overflowdb.NodeDb;
import overflowdb.NodeRef;
import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public class NodeSerializer extends BookKeeper {
    private final OdbStorage storage;
    private final Function<Object, Object> convertPropertyForPersistence;
    private final ThreadLocal<MessageBufferPacker> packerPool = ThreadLocal.withInitial(MessagePack::newDefaultBufferPacker);

    public NodeSerializer(boolean statsEnabled, OdbStorage storage, Function<Object, Object> convertPropertyForPersistence) {
        super(statsEnabled);
        this.storage = storage;
        this.convertPropertyForPersistence = convertPropertyForPersistence;
    }

    public NodeSerializer(boolean statsEnabled, OdbStorage storage) {
        this(statsEnabled, storage, null);
    }

    public byte[] serialize(NodeDb node) throws IOException {
        long startTimeNanos = getStartTimeNanos();
        MessageBufferPacker packer = packerPool.get();
        try (packer) {
            packer.clear();
            NodeLayoutInformation layoutInformation = node.layoutInformation();
            node.markAsClean();

            packer.packLong(node.ref.id());

            final int labelId = storage.lookupOrCreateStringToIntMapping(layoutInformation.label);
            packer.packInt(labelId);

            packProperties(packer, node.propertiesMapForStorage());
            packEdges(packer, node);

            if (statsEnabled) recordStatistics(startTimeNanos);
            return packer.toByteArray();
        }
    }

    private void packProperties(MessageBufferPacker packer, Map<String, Object> properties) throws IOException {
        packer.packMapHeader(properties.size());
        for (Map.Entry<String, Object> property : properties.entrySet()) {
            int propertyKeyId = storage.lookupOrCreateStringToIntMapping(property.getKey());
            packer.packInt(propertyKeyId);
            Object value = property.getValue();
            Object valueMaybeConverted = convertPropertyForPersistence == null ? value : convertPropertyForPersistence.apply(value);
            packTypedValue(packer, valueMaybeConverted);
        }
    }

    private void packEdges(MessageBufferPacker packer, NodeDb node) throws IOException {
        NodeLayoutInformation layoutInformation = node.layoutInformation();
        AdjacentNodes adjacentNodes = node.getAdjacentNodes();

        packEdgesForOneDirection(packer, node, adjacentNodes, layoutInformation.allowedOutEdgeLabels(), layoutInformation::outEdgeToOffsetPosition);
        packEdgesForOneDirection(packer, node, adjacentNodes, layoutInformation.allowedInEdgeLabels(), layoutInformation::inEdgeToOffsetPosition);
    }

    private void packEdgesForOneDirection(MessageBufferPacker packer, NodeDb node, AdjacentNodes adjacentNodes,
                                          String[] allowedEdgeLabels, Function<String, Integer> edgeToOffsetPosition) throws IOException {

        int edgeTypeCount = 0;
        for (String edgeLabel : allowedEdgeLabels) {
            int offsetPos = edgeToOffsetPosition.apply(edgeLabel);
            int count = adjacentNodes.getOffset(offsetPos * 2 + 1);
            if (count > 0) {
                edgeTypeCount++;
            }
        }
        packer.packInt(edgeTypeCount);
        for (String edgeLabel : allowedEdgeLabels) {
            int offsetPos = edgeToOffsetPosition.apply(edgeLabel);
            int count = adjacentNodes.getOffset(offsetPos * 2 + 1);
            if (count > 0) {
                packEdgesForOneLabel(packer, node, adjacentNodes, edgeLabel, offsetPos);
            }
        }
    }

    private void packEdgesForOneLabel(MessageBufferPacker packer, NodeDb node, AdjacentNodes adjacentNodes, String edgeLabel, int offsetPos) throws IOException {
        NodeLayoutInformation layoutInformation = node.layoutInformation();
        Object[] adjacentNodesWithEdgeProperties = adjacentNodes.nodesWithEdgeProperties;
        final Set<String> edgePropertyNames = layoutInformation.edgePropertyKeys(edgeLabel);

        // pointers into adjacentNodesWithEdgeProperties
        int start = node.startIndex(adjacentNodes, offsetPos);
        int blockLength = node.blockLength(adjacentNodes, offsetPos);
        int strideSize = node.getStrideSize(edgeLabel);

        int edgeCount = 0;
        int currIdx = start;
        int endIdx = start + blockLength;
        while (currIdx < endIdx) {
            Node adjacentNode = (Node) adjacentNodesWithEdgeProperties[currIdx];
            if (adjacentNode != null) {
                edgeCount++;
            }
            currIdx += strideSize;
        }

        int labelId = storage.lookupOrCreateStringToIntMapping(edgeLabel);
        packer.packInt(labelId);
        packer.packInt(edgeCount);

        currIdx = start;
        while (currIdx < endIdx) {
            Node adjacentNode = (Node) adjacentNodesWithEdgeProperties[currIdx];
            if (adjacentNode != null) {
                packer.packLong(adjacentNode.id());
                packer.packMapHeader(edgePropertyNames.size());
                for (String propertyName : edgePropertyNames) {
                    int propertyKeyId = storage.lookupOrCreateStringToIntMapping(propertyName);
                    packer.packInt(propertyKeyId);
                    int edgePropertyOffset = layoutInformation.getEdgePropertyOffsetRelativeToAdjacentNodeRef(edgeLabel, propertyName);
                    Object property = adjacentNodesWithEdgeProperties[currIdx + edgePropertyOffset];
                    Object valueMaybeConverted = convertPropertyForPersistence == null ? property : convertPropertyForPersistence.apply(property);
                    packTypedValue(packer, valueMaybeConverted);
                }
            }
            currIdx += strideSize;
        }
    }

    /**
     * format: `[ValueType.id, value]`
     */
    private void packTypedValue(final MessageBufferPacker packer, final Object value) throws IOException {
        packer.packArrayHeader(2);
        switch (value) {
            case null -> {
                packer.packByte(ValueTypes.UNKNOWN.id);
                packer.packNil();
            }
            case NodeRef<?> nodeRef -> {
                packer.packByte(ValueTypes.NODE_REF.id);
                packer.packLong(nodeRef.id());
            }
            case Boolean aBoolean -> {
                packer.packByte(ValueTypes.BOOLEAN.id);
                packer.packBoolean(aBoolean);
            }
            case String string -> {
                packer.packByte(ValueTypes.STRING.id);
                packer.packString(string);
            }
            case Byte aByte -> {
                packer.packByte(ValueTypes.BYTE.id);
                packer.packByte((byte) value);
            }
            case Short aShort -> {
                packer.packByte(ValueTypes.SHORT.id);
                packer.packShort((short) value);
            }
            case Integer integer -> {
                packer.packByte(ValueTypes.INTEGER.id);
                packer.packInt((int) value);
            }
            case Long aLong -> {
                packer.packByte(ValueTypes.LONG.id);
                packer.packLong((long) value);
            }
            case Float f -> {
                packer.packByte(ValueTypes.FLOAT.id);
                packer.packFloat((float) value);
            }
            case Double d -> {
                packer.packByte(ValueTypes.DOUBLE.id);
                packer.packDouble((double) value);
            }
            case Character character -> {
                packer.packByte(ValueTypes.CHARACTER.id);
                packer.packInt(character);
            }
            case java.util.List list -> {
                packer.packByte(ValueTypes.ARRAY_OBJECT.id);
                packer.packArrayHeader(list.size());
                for (Object o : list) packTypedValue(packer, o);
            }
            case Object[] array -> {
                packer.packByte(ValueTypes.ARRAY_OBJECT.id);
                packer.packArrayHeader(array.length);
                for (Object o : array) packTypedValue(packer, o);
            }
            case byte[] array -> {
                packer.packByte(ValueTypes.ARRAY_BYTE.id);
                packer.packArrayHeader(array.length);
                for (byte b : array) packer.packByte(b);
            }
            case short[] array -> {
                packer.packByte(ValueTypes.ARRAY_SHORT.id);
                packer.packArrayHeader(array.length);
                for (short s : array) packer.packShort(s);
            }
            case int[] array -> {
                packer.packByte(ValueTypes.ARRAY_INT.id);
                packer.packArrayHeader(array.length);
                for (int i : array) packer.packInt(i);
            }
            case long[] array -> {
                packer.packByte(ValueTypes.ARRAY_LONG.id);
                packer.packArrayHeader(array.length);
                for (long l : array) packer.packLong(l);
            }
            case float[] array -> {
                packer.packByte(ValueTypes.ARRAY_FLOAT.id);
                packer.packArrayHeader(array.length);
                for (float f : array) packer.packFloat(f);
            }
            case double[] array -> {
                packer.packByte(ValueTypes.ARRAY_DOUBLE.id);
                packer.packArrayHeader(array.length);
                for (double d : array) packer.packDouble(d);
            }
            case char[] array -> {
                packer.packByte(ValueTypes.ARRAY_CHAR.id);
                packer.packArrayHeader(array.length);
                for (char c : array) packer.packInt(c);
            }
            case boolean[] array -> {
                packer.packByte(ValueTypes.ARRAY_BOOL.id);
                packer.packArrayHeader(array.length);
                for (boolean b : array) packer.packBoolean(b);
            }
            default -> {
                String fullMessage = getFullMessageString(value);
                throw new UnsupportedOperationException(fullMessage);
            }
        }
    }

    private String getFullMessageString(Object value) {
        String baseMessage = String.format("value of type %s not supported for serialization - ", value.getClass());
        String extendedMessage =
                convertPropertyForPersistence == null ?
                        "there is no `convertPropertyForPersistence` function defined, you might want to do so during graph initialisation..." :
                        "there is a `convertPropertyForPersistence`, but it doesn't convert the given type to one of the supported types";
        return String.format("%s - %s", baseMessage, extendedMessage);
    }
}