package overflowdb.storage;

import overflowdb.BatchedUpdate;
import overflowdb.BoundaryResolver;
import overflowdb.SymbolicKey;
import overflowdb.Node;
import overflowdb.NodeRef;
import overflowdb.DetachedNodeData;
import overflowdb.DetachedNodeGeneric;
import overflowdb.NodeOrDetachedNode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.ToIntFunction;
import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;

public class GraphFragmentCodec {
    public static final int FORMAT_VERSION = 1;
    public static final int MAGIC = 0x46524147; // "FRAG"

    public static class FragmentHeader {
        public final int formatVersion;
        public final long schemaHash;
        public final int crc32;
        public final int payloadLength;

        public FragmentHeader(int formatVersion, long schemaHash, int crc32, int payloadLength) {
            this.formatVersion = formatVersion;
            this.schemaHash = schemaHash;
            this.crc32 = crc32;
            this.payloadLength = payloadLength;
        }
    }

    public static Optional<byte[]> encode(BatchedUpdate.DiffOrBuilder diff, BoundaryResolver boundary, long schemaHash) {
        // Only allow DetachedNodeData and CreateEdge changes in add-only fragments
        for (Iterator<BatchedUpdate.Change> it = diff.iterator(); it.hasNext(); ) {
            BatchedUpdate.Change change = it.next();
            if (!(change instanceof DetachedNodeData) && !(change instanceof BatchedUpdate.CreateEdge)) {
                return Optional.empty();
            }
        }

        Map<Object, Integer> nodeToLocalId = new IdentityHashMap<>();
        List<DetachedNodeData> localNodes = new ArrayList<>();
        for (Iterator<BatchedUpdate.Change> it = diff.iterator(); it.hasNext(); ) {
            BatchedUpdate.Change change = it.next();
            if (change instanceof DetachedNodeData) {
                DetachedNodeData node = (DetachedNodeData) change;
                if (!nodeToLocalId.containsKey(node)) {
                    nodeToLocalId.put(node, localNodes.size());
                    localNodes.add(node);
                }
            }
        }

        List<String> glossaryList = new ArrayList<>();
        Map<String, Integer> glossaryMap = new HashMap<>();

        ToIntFunction<String> getGlossaryId = (str) -> {
            if (str == null) return -1;
            return glossaryMap.computeIfAbsent(str, (s) -> {
                int id = glossaryList.size();
                glossaryList.add(s);
                return id;
            });
        };

        List<DecodedFragment.DecodedExport> exports = new ArrayList<>();
        for (int i = 0; i < localNodes.size(); i++) {
            DetachedNodeData node = localNodes.get(i);
            Optional<SymbolicKey> keyOpt = boundary.getSymbolicKey(node);
            if (keyOpt != null && keyOpt.isPresent()) {
                exports.add(new DecodedFragment.DecodedExport(keyOpt.get(), i));
            }
        }

        List<BatchedUpdate.CreateEdge> internalEdges = new ArrayList<>();
        List<DecodedFragment.DecodedBoundaryRef> boundaryRefs = new ArrayList<>();

        for (Iterator<BatchedUpdate.Change> it = diff.iterator(); it.hasNext(); ) {
            BatchedUpdate.Change change = it.next();
            if (change instanceof BatchedUpdate.CreateEdge) {
                BatchedUpdate.CreateEdge create = (BatchedUpdate.CreateEdge) change;
                Integer srcLocal = nodeToLocalId.get(create.src);
                if (srcLocal == null && create.src instanceof NodeRef) {
                    srcLocal = nodeToLocalId.get(((NodeRef<?>) create.src).get());
                }
                if (srcLocal == null) {
                    continue;
                }

                Integer dstLocal = nodeToLocalId.get(create.dst);
                if (dstLocal == null && create.dst instanceof NodeRef) {
                    dstLocal = nodeToLocalId.get(((NodeRef<?>) create.dst).get());
                }

                if (dstLocal != null) {
                    internalEdges.add(create);
                } else {
                    NodeOrDetachedNode dstNode = create.dst;
                    Optional<SymbolicKey> keyOpt = boundary.getSymbolicKey(dstNode);
                    if ((keyOpt == null || !keyOpt.isPresent()) && dstNode instanceof NodeRef) {
                        keyOpt = boundary.getSymbolicKey(((NodeRef<?>) dstNode).get());
                    }
                    if (keyOpt != null && keyOpt.isPresent()) {
                        boundaryRefs.add(new DecodedFragment.DecodedBoundaryRef(srcLocal, create.label, keyOpt.get()));
                    }
                }
            }
        }

        try (MessageBufferPacker packer = MessagePack.newDefaultBufferPacker()) {
            packer.packArrayHeader(5);

            // 1. Pack glossary (pre-populated by traversing headers later, or we build it incrementally. 
            //    Since we need index lookups, we register all strings during traversal, then serialize)
            //    Wait, to write glossary first, we can do a pre-scan to fill glossary, or we serialize into a temporary buffer first.
            //    A pre-scan is simple:
            for (DetachedNodeData node : localNodes) {
                getGlossaryId.applyAsInt(node.label());
                Map<String, Object> props = getProperties(node);
                for (String key : props.keySet()) {
                    getGlossaryId.applyAsInt(key);
                }
            }
            for (BatchedUpdate.CreateEdge edge : internalEdges) {
                getGlossaryId.applyAsInt(edge.label);
                if (edge.propertiesAndKeys != null) {
                    for (int i = 0; i < edge.propertiesAndKeys.length; i += 2) {
                        getGlossaryId.applyAsInt((String) edge.propertiesAndKeys[i]);
                    }
                }
            }
            for (DecodedFragment.DecodedBoundaryRef ref : boundaryRefs) {
                getGlossaryId.applyAsInt(ref.label);
            }

            packer.packArrayHeader(glossaryList.size());
            for (String s : glossaryList) {
                packer.packString(s);
            }

            // 2. Pack nodes
            packer.packArrayHeader(localNodes.size());
            for (DetachedNodeData node : localNodes) {
                packer.packArrayHeader(2);
                packer.packInt(getGlossaryId.applyAsInt(node.label()));

                Map<String, Object> properties = getProperties(node);
                packer.packMapHeader(properties.size());
                for (Map.Entry<String, Object> entry : properties.entrySet()) {
                    packer.packInt(getGlossaryId.applyAsInt(entry.getKey()));
                    packTypedValue(packer, entry.getValue(), nodeToLocalId, getGlossaryId);
                }
            }

            // 3. Pack edges
            packer.packArrayHeader(internalEdges.size());
            for (BatchedUpdate.CreateEdge edge : internalEdges) {
                packer.packArrayHeader(4);
                Integer srcLocal = nodeToLocalId.get(edge.src);
                if (srcLocal == null && edge.src instanceof NodeRef) srcLocal = nodeToLocalId.get(((NodeRef<?>) edge.src).get());
                Integer dstLocal = nodeToLocalId.get(edge.dst);
                if (dstLocal == null && edge.dst instanceof NodeRef) dstLocal = nodeToLocalId.get(((NodeRef<?>) edge.dst).get());

                packer.packInt(srcLocal);
                packer.packInt(dstLocal);
                packer.packInt(getGlossaryId.applyAsInt(edge.label));

                Object[] properties = edge.propertiesAndKeys;
                if (properties == null || properties.length == 0) {
                    packer.packMapHeader(0);
                } else {
                    packer.packMapHeader(properties.length / 2);
                    for (int i = 0; i < properties.length; i += 2) {
                        packer.packInt(getGlossaryId.applyAsInt((String) properties[i]));
                        packTypedValue(packer, properties[i + 1], nodeToLocalId, getGlossaryId);
                    }
                }
            }

            // 4. Pack boundaryRefs
            packer.packArrayHeader(boundaryRefs.size());
            for (DecodedFragment.DecodedBoundaryRef ref : boundaryRefs) {
                packer.packArrayHeader(3);
                packer.packInt(ref.srcLocal);
                packer.packInt(getGlossaryId.applyAsInt(ref.label));
                packer.packString(ref.key.key());
            }

            // 5. Pack exports
            packer.packArrayHeader(exports.size());
            for (DecodedFragment.DecodedExport export : exports) {
                packer.packArrayHeader(2);
                packer.packString(export.key.key());
                packer.packInt(export.local);
            }

            byte[] payload = packer.toByteArray();

            java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
            crc.update(payload, 0, payload.length);
            long crcValue = crc.getValue();

            ByteBuffer header = ByteBuffer.allocate(24);
            header.putInt(MAGIC);
            header.putInt(FORMAT_VERSION);
            header.putLong(schemaHash);
            header.putInt((int) crcValue);
            header.putInt(payload.length);

            byte[] result = new byte[24 + payload.length];
            System.arraycopy(header.array(), 0, result, 0, 24);
            System.arraycopy(payload, 0, result, 24, payload.length);

            return Optional.of(result);

        } catch (IOException e) {
            throw new RuntimeException("Failed to encode GraphFragment", e);
        }
    }

    public static FragmentHeader peek(byte[] bytes) {
        if (bytes == null || bytes.length < 24) {
            throw new IllegalArgumentException("Invalid fragment bytes: too short");
        }
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        int magic = buf.getInt();
        if (magic != MAGIC) {
            throw new IllegalArgumentException("Invalid magic bytes: " + String.format("0x%08X", magic));
        }
        int formatVersion = buf.getInt();
        if (formatVersion != FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported format version: " + formatVersion);
        }
        long schemaHash = buf.getLong();
        int crcValue = buf.getInt();
        int payloadLength = buf.getInt();

        if (bytes.length < 24 + payloadLength) {
            throw new IllegalArgumentException("Fragment payload is truncated");
        }

        java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
        crc.update(bytes, 24, payloadLength);
        if ((int) crc.getValue() != crcValue) {
            throw new IllegalArgumentException("CRC32C checksum mismatch");
        }

        return new FragmentHeader(formatVersion, schemaHash, crcValue, payloadLength);
    }

    public static DecodedFragment decode(byte[] bytes) {
        FragmentHeader header = peek(bytes);

        try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(bytes, 24, header.payloadLength)) {
            int outerSize = unpacker.unpackArrayHeader();
            if (outerSize != 5) {
                throw new IOException("Invalid outer array size: " + outerSize);
            }

            int glossarySize = unpacker.unpackArrayHeader();
            String[] glossary = new String[glossarySize];
            for (int i = 0; i < glossarySize; i++) {
                glossary[i] = unpacker.unpackString();
            }

            int nodesSize = unpacker.unpackArrayHeader();
            List<DecodedFragment.DecodedNode> nodes = new ArrayList<>(nodesSize);
            for (int i = 0; i < nodesSize; i++) {
                int nodeArraySize = unpacker.unpackArrayHeader();
                if (nodeArraySize != 2) {
                    throw new IOException("Invalid node array size: " + nodeArraySize);
                }
                String label = glossary[unpacker.unpackInt()];

                int propertyCount = unpacker.unpackMapHeader();
                Map<String, Object> properties = new HashMap<>(propertyCount);
                for (int p = 0; p < propertyCount; p++) {
                    String key = glossary[unpacker.unpackInt()];
                    Object value = unpackDirectValue(unpacker, glossary);
                    properties.put(key, value);
                }
                nodes.add(new DecodedFragment.DecodedNode(label, properties));
            }

            int edgesSize = unpacker.unpackArrayHeader();
            List<DecodedFragment.DecodedEdge> edges = new ArrayList<>(edgesSize);
            for (int i = 0; i < edgesSize; i++) {
                int edgeArraySize = unpacker.unpackArrayHeader();
                if (edgeArraySize != 4) {
                    throw new IOException("Invalid edge array size: " + edgeArraySize);
                }
                int srcLocal = unpacker.unpackInt();
                int dstLocal = unpacker.unpackInt();
                String label = glossary[unpacker.unpackInt()];

                int propertyCount = unpacker.unpackMapHeader();
                Map<String, Object> properties = new HashMap<>(propertyCount);
                for (int p = 0; p < propertyCount; p++) {
                    String key = glossary[unpacker.unpackInt()];
                    Object value = unpackDirectValue(unpacker, glossary);
                    properties.put(key, value);
                }
                edges.add(new DecodedFragment.DecodedEdge(srcLocal, dstLocal, label, properties));
            }

            int boundaryRefsSize = unpacker.unpackArrayHeader();
            List<DecodedFragment.DecodedBoundaryRef> boundaryRefs = new ArrayList<>(boundaryRefsSize);
            for (int i = 0; i < boundaryRefsSize; i++) {
                int boundaryRefArraySize = unpacker.unpackArrayHeader();
                if (boundaryRefArraySize != 3) {
                    throw new IOException("Invalid boundaryRef array size: " + boundaryRefArraySize);
                }
                int srcLocal = unpacker.unpackInt();
                String label = glossary[unpacker.unpackInt()];
                SymbolicKey key = new SymbolicKey(unpacker.unpackString());
                boundaryRefs.add(new DecodedFragment.DecodedBoundaryRef(srcLocal, label, key));
            }

            int exportsSize = unpacker.unpackArrayHeader();
            List<DecodedFragment.DecodedExport> exports = new ArrayList<>(exportsSize);
            for (int i = 0; i < exportsSize; i++) {
                int exportArraySize = unpacker.unpackArrayHeader();
                if (exportArraySize != 2) {
                    throw new IOException("Invalid export array size: " + exportArraySize);
                }
                SymbolicKey key = new SymbolicKey(unpacker.unpackString());
                int local = unpacker.unpackInt();
                exports.add(new DecodedFragment.DecodedExport(key, local));
            }

            return new DecodedFragment(nodes, edges, boundaryRefs, exports);
        } catch (IOException e) {
            throw new RuntimeException("Failed to decode GraphFragment", e);
        }
    }

    private static Object unpackDirectValue(MessageUnpacker unpacker, String[] glossary) throws IOException {
        unpacker.unpackArrayHeader();
        byte valueTypeId = unpacker.unpackByte();
        return switch (ValueTypes.lookup(valueTypeId)) {
            case UNKNOWN -> {
                unpacker.unpackNil();
                yield null;
            }
            case NODE_REF -> {
                long localId = unpacker.unpackLong();
                yield new LocalNodeRef((int) localId);
            }
            case BOOLEAN -> unpacker.unpackBoolean();
            case STRING -> unpacker.unpackString();
            case BYTE -> unpacker.unpackByte();
            case SHORT -> unpacker.unpackShort();
            case INTEGER -> unpacker.unpackInt();
            case LONG -> unpacker.unpackLong();
            case FLOAT -> unpacker.unpackFloat();
            case DOUBLE -> unpacker.unpackDouble();
            case LIST -> {
                int size = unpacker.unpackArrayHeader();
                List<Object> deserializedList = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    deserializedList.add(unpackDirectValue(unpacker, glossary));
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
                for (int i = 0; i < size; i++) arr[i] = unpackDirectValue(unpacker, glossary);
                yield arr;
            }
        };
    }

    private static void packTypedValue(MessageBufferPacker packer, Object value, Map<Object, Integer> nodeToLocalId, ToIntFunction<String> getGlossaryId) throws IOException {
        packer.packArrayHeader(2);
        if (value == null) {
            packer.packByte(ValueTypes.UNKNOWN.id);
            packer.packNil();
        } else if (value instanceof NodeOrDetachedNode || value instanceof NodeRef) {
            packer.packByte(ValueTypes.NODE_REF.id);
            Integer localId = nodeToLocalId.get(value);
            if (localId == null && value instanceof NodeRef) {
                localId = nodeToLocalId.get(((NodeRef<?>) value).get());
            }
            if (localId == null) {
                throw new UnsupportedOperationException("Cannot serialize reference to external node in properties: " + value);
            }
            packer.packLong(localId);
        } else if (value instanceof Boolean) {
            packer.packByte(ValueTypes.BOOLEAN.id);
            packer.packBoolean((Boolean) value);
        } else if (value instanceof String) {
            packer.packByte(ValueTypes.STRING.id);
            packer.packString((String) value);
        } else if (value instanceof Byte) {
            packer.packByte(ValueTypes.BYTE.id);
            packer.packByte((Byte) value);
        } else if (value instanceof Short) {
            packer.packByte(ValueTypes.SHORT.id);
            packer.packShort((Short) value);
        } else if (value instanceof Integer) {
            packer.packByte(ValueTypes.INTEGER.id);
            packer.packInt((Integer) value);
        } else if (value instanceof Long) {
            packer.packByte(ValueTypes.LONG.id);
            packer.packLong((Long) value);
        } else if (value instanceof Float) {
            packer.packByte(ValueTypes.FLOAT.id);
            packer.packFloat((Float) value);
        } else if (value instanceof Double) {
            packer.packByte(ValueTypes.DOUBLE.id);
            packer.packDouble((Double) value);
        } else if (value instanceof Character) {
            packer.packByte(ValueTypes.CHARACTER.id);
            packer.packInt((Character) value);
        } else if (value instanceof java.util.List) {
            packer.packByte(ValueTypes.ARRAY_OBJECT.id);
            java.util.List<?> list = (java.util.List<?>) value;
            packer.packArrayHeader(list.size());
            for (Object o : list) packTypedValue(packer, o, nodeToLocalId, getGlossaryId);
        } else if (value instanceof Object[]) {
            packer.packByte(ValueTypes.ARRAY_OBJECT.id);
            Object[] array = (Object[]) value;
            packer.packArrayHeader(array.length);
            for (Object o : array) packTypedValue(packer, o, nodeToLocalId, getGlossaryId);
        } else if (value instanceof byte[]) {
            packer.packByte(ValueTypes.ARRAY_BYTE.id);
            byte[] array = (byte[]) value;
            packer.packArrayHeader(array.length);
            for (byte b : array) packer.packByte(b);
        } else if (value instanceof short[]) {
            packer.packByte(ValueTypes.ARRAY_SHORT.id);
            short[] array = (short[]) value;
            packer.packArrayHeader(array.length);
            for (short s : array) packer.packShort(s);
        } else if (value instanceof int[]) {
            packer.packByte(ValueTypes.ARRAY_INT.id);
            int[] array = (int[]) value;
            packer.packArrayHeader(array.length);
            for (int i : array) packer.packInt(i);
        } else if (value instanceof long[]) {
            packer.packByte(ValueTypes.ARRAY_LONG.id);
            long[] array = (long[]) value;
            packer.packArrayHeader(array.length);
            for (long l : array) packer.packLong(l);
        } else if (value instanceof float[]) {
            packer.packByte(ValueTypes.ARRAY_FLOAT.id);
            float[] array = (float[]) value;
            packer.packArrayHeader(array.length);
            for (float f : array) packer.packFloat(f);
        } else if (value instanceof double[]) {
            packer.packByte(ValueTypes.ARRAY_DOUBLE.id);
            double[] array = (double[]) value;
            packer.packArrayHeader(array.length);
            for (double d : array) packer.packDouble(d);
        } else if (value instanceof char[]) {
            packer.packByte(ValueTypes.ARRAY_CHAR.id);
            char[] array = (char[]) value;
            packer.packArrayHeader(array.length);
            for (char c : array) packer.packInt(c);
        } else if (value instanceof boolean[]) {
            packer.packByte(ValueTypes.ARRAY_BOOL.id);
            boolean[] array = (boolean[]) value;
            packer.packArrayHeader(array.length);
            for (boolean b : array) packer.packBoolean(b);
        } else {
            throw new UnsupportedOperationException("value of type " + value.getClass() + " not supported for serialization");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> getProperties(DetachedNodeData node) {
        if (node instanceof DetachedNodeGeneric) {
            Object[] keyvalues = ((DetachedNodeGeneric) node).keyvalues;
            Map<String, Object> map = new HashMap<>();
            for (int i = 0; i < keyvalues.length; i += 2) {
                map.put((String) keyvalues[i], keyvalues[i + 1]);
            }
            return map;
        }

        try {
            try {
                java.lang.reflect.Method method = node.getClass().getMethod("properties");
                Object result = method.invoke(node);
                if (result instanceof Map) {
                    return (Map<String, Object>) result;
                }
                if (result != null && result.getClass().getName().startsWith("scala.collection.")) {
                    try {
                        Class<?> convertersClass = Class.forName("scala.jdk.javaapi.CollectionConverters");
                        java.lang.reflect.Method asJavaMethod = convertersClass.getMethod("asJava", Class.forName("scala.collection.Map"));
                        return (Map<String, Object>) asJavaMethod.invoke(null, result);
                    } catch (Exception ex) {
                        Class<?> convertersClass = Class.forName("scala.collection.JavaConverters");
                        java.lang.reflect.Method mapAsJavaMapMethod = convertersClass.getMethod("mapAsJavaMap", Class.forName("scala.collection.Map"));
                        return (Map<String, Object>) mapAsJavaMapMethod.invoke(null, result);
                    }
                }
            } catch (NoSuchMethodException e) {
                try {
                    java.lang.reflect.Method method = node.getClass().getMethod("propertiesMap");
                    return (Map<String, Object>) method.invoke(node);
                } catch (NoSuchMethodException e2) {
                    java.lang.reflect.Method method = node.getClass().getMethod("keyvalues");
                    Object[] keyvalues = (Object[]) method.invoke(node);
                    Map<String, Object> map = new HashMap<>();
                    for (int i = 0; i < keyvalues.length; i += 2) {
                        map.put((String) keyvalues[i], keyvalues[i + 1]);
                    }
                    return map;
                }
            }
        } catch (Exception e) {
            return Collections.emptyMap();
        }
        return Collections.emptyMap();
    }
}
