package overflowdb;

import java.util.Optional;

public interface BoundaryResolver {
    // encode side: classify an edge target as internal (returns empty key) or boundary (returns a key)
    Optional<SymbolicKey> getSymbolicKey(NodeOrDetachedNode node);

    // decode/apply side: resolve a SymbolicKey to a live Node, or empty for external
    Optional<Node> resolve(SymbolicKey key);
}
