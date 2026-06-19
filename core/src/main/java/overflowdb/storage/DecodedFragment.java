package overflowdb.storage;

import overflowdb.SymbolicKey;
import java.util.List;
import java.util.Map;

public final class DecodedFragment {
    public static class DecodedNode {
        public final String label;
        public final Map<String, Object> properties;

        public DecodedNode(String label, Map<String, Object> properties) {
            this.label = label;
            this.properties = properties;
        }
    }

    public static class DecodedEdge {
        public final int srcLocal;
        public final int dstLocal;
        public final String label;
        public final Map<String, Object> properties;

        public DecodedEdge(int srcLocal, int dstLocal, String label, Map<String, Object> properties) {
            this.srcLocal = srcLocal;
            this.dstLocal = dstLocal;
            this.label = label;
            this.properties = properties;
        }
    }

    public static class DecodedBoundaryRef {
        public final int srcLocal;
        public final String label;
        public final SymbolicKey key;

        public DecodedBoundaryRef(int srcLocal, String label, SymbolicKey key) {
            this.srcLocal = srcLocal;
            this.label = label;
            this.key = key;
        }
    }

    public static class DecodedExport {
        public final SymbolicKey key;
        public final int local;

        public DecodedExport(SymbolicKey key, int local) {
            this.key = key;
            this.local = local;
        }
    }

    public final List<DecodedNode> nodes;
    public final List<DecodedEdge> edges;
    public final List<DecodedBoundaryRef> boundaryRefs;
    public final List<DecodedExport> exports;

    public DecodedFragment(List<DecodedNode> nodes, List<DecodedEdge> edges, List<DecodedBoundaryRef> boundaryRefs, List<DecodedExport> exports) {
        this.nodes = nodes;
        this.edges = edges;
        this.boundaryRefs = boundaryRefs;
        this.exports = exports;
    }
}
