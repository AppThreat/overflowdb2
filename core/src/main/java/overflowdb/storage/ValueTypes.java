package overflowdb.storage;

public enum ValueTypes {
    BOOLEAN((byte) 0),
    STRING((byte) 1),
    BYTE((byte) 2),
    SHORT((byte) 3),
    INTEGER((byte) 4),
    LONG((byte) 5),
    FLOAT((byte) 6),
    DOUBLE((byte) 7),
    LIST((byte) 8),
    NODE_REF((byte) 9),
    UNKNOWN((byte) 10),
    CHARACTER((byte) 11),
    ARRAY_BYTE((byte) 12),
    ARRAY_SHORT((byte) 13),
    ARRAY_INT((byte) 14),
    ARRAY_LONG((byte) 15),
    ARRAY_FLOAT((byte) 16),
    ARRAY_DOUBLE((byte) 17),
    ARRAY_CHAR((byte) 18),
    ARRAY_BOOL((byte) 19),
    ARRAY_OBJECT((byte) 20);

    public final byte id;

    ValueTypes(byte id) {
        this.id = id;
    }

    private static final ValueTypes[] LOOKUP_CACHE = new ValueTypes[21];
    static {
        for (ValueTypes v : values()) {
            LOOKUP_CACHE[v.id] = v;
        }
    }

    public static ValueTypes lookup(byte id) {
        if (id >= 0 && id < LOOKUP_CACHE.length) {
            return LOOKUP_CACHE[id];
        }
        throw new IllegalArgumentException("unknown id type " + id);
    }
}