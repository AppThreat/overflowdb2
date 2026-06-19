package overflowdb;

import java.util.Objects;

public final class SymbolicKey {
    private final String key;

    public SymbolicKey(String key) {
        this.key = Objects.requireNonNull(key);
    }

    public String key() {
        return key;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SymbolicKey that = (SymbolicKey) o;
        return key.equals(that.key);
    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }

    @Override
    public String toString() {
        return key;
    }
}
