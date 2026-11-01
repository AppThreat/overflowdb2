package overflowdb.storage;

import overflowdb.SymbolicKey;
import java.util.List;
import java.util.Map;

public final class DecodedFragment {
    public static class DecodedNode {
        public final String label;
        /** In the order applyDiff sets them. */
        public final Map<String, Object> properties;
        /** The id the diff asked for through a Long refOrId, or null to let the graph or a KeyPool choose. */
        public final Long requestedId;

        public DecodedNode(String label, Map<String, Object> properties) {
            this(label, properties, null);
        }

        public DecodedNode(String label, Map<String, Object> properties, Long requestedId) {
            this.label = label;
            this.properties = properties;
            this.requestedId = requestedId;
        }
    }

    public static class DecodedEdge {
        public final int srcLocal;
        public final int dstLocal;
        public final String label;
        public final Map<String, Object> properties;
        /** Position among all edges of the diff, internal and boundary, or -1 if unknown. */
        public final int position;
        /** How many of the fragment's nodes applyDiff creates before adding this edge, or -1 if unknown. */
        public final int nodesBefore;

        public DecodedEdge(int srcLocal, int dstLocal, String label, Map<String, Object> properties) {
            this(srcLocal, dstLocal, label, properties, -1, -1);
        }

        public DecodedEdge(int srcLocal, int dstLocal, String label, Map<String, Object> properties, int position, int nodesBefore) {
            this.srcLocal = srcLocal;
            this.dstLocal = dstLocal;
            this.label = label;
            this.properties = properties;
            this.position = position;
            this.nodesBefore = nodesBefore;
        }
    }

    public static class DecodedBoundaryRef {
        public final int srcLocal;
        public final String label;
        public final SymbolicKey key;
        public final Map<String, Object> properties;
        /** Position among all edges of the diff, internal and boundary, or -1 if unknown. */
        public final int position;
        /** How many of the fragment's nodes applyDiff creates before adding this edge, or -1 if unknown. */
        public final int nodesBefore;

        public DecodedBoundaryRef(int srcLocal, String label, SymbolicKey key) {
            this(srcLocal, label, key, Map.of(), -1, -1);
        }

        public DecodedBoundaryRef(int srcLocal, String label, SymbolicKey key, Map<String, Object> properties, int position, int nodesBefore) {
            this.srcLocal = srcLocal;
            this.label = label;
            this.key = key;
            this.properties = properties;
            this.position = position;
            this.nodesBefore = nodesBefore;
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
