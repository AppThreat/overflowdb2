package overflowdb.storage;

public final class LocalNodeRef {
    public final int localId;

    public LocalNodeRef(int localId) {
        this.localId = localId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LocalNodeRef that = (LocalNodeRef) o;
        return localId == that.localId;
    }

    @Override
    public int hashCode() {
        return localId;
    }

    @Override
    public String toString() {
        return "LocalNodeRef(" + localId + ")";
    }
}
