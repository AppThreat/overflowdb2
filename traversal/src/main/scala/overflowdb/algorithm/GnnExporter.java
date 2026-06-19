package overflowdb.algorithm;

import overflowdb.Edge;
import overflowdb.Node;
import gnu.trove.list.array.TLongArrayList;

import java.util.*;

/**
 * Exporter utility to extract graph structures into flat primitive arrays,
 * optimized for consumption by Graph Neural Network (GNN) frameworks.
 */
public class GnnExporter {

    public static class GnnExport {
        public final long[] nodeIds;
        public final String[] nodeLabels;
        public final long[] edgeSrcIds;
        public final long[] edgeDstIds;
        public final String[] edgeLabels;

        public GnnExport(long[] nodeIds, String[] nodeLabels, long[] edgeSrcIds, long[] edgeDstIds, String[] edgeLabels) {
            this.nodeIds = nodeIds;
            this.nodeLabels = nodeLabels;
            this.edgeSrcIds = edgeSrcIds;
            this.edgeDstIds = edgeDstIds;
            this.edgeLabels = edgeLabels;
        }
    }

    /**
     * Exports the subgraph induced by the given collection of nodes into a flat primitive representation.
     * @param nodes Collection of nodes forming the induced subgraph.
     * @return A GnnExport instance containing parallel primitive arrays.
     */
    public static GnnExport exportGraph(Collection<Node> nodes) {
        int nodeCount = nodes.size();
        long[] nodeIds = new long[nodeCount];
        String[] nodeLabels = new String[nodeCount];
        
        Set<Long> nodeSet = new HashSet<>(nodeCount);
        int idx = 0;
        for (Node node : nodes) {
            long id = node.id();
            nodeIds[idx] = id;
            nodeLabels[idx] = node.label();
            nodeSet.add(id);
            idx++;
        }

        TLongArrayList srcList = new TLongArrayList();
        TLongArrayList dstList = new TLongArrayList();
        List<String> labelList = new ArrayList<>();

        for (Node node : nodes) {
            Iterator<Edge> outEdges = node.outE();
            while (outEdges.hasNext()) {
                Edge edge = outEdges.next();
                Node inNode = edge.inNode();
                long inId = inNode.id();
                if (nodeSet.contains(inId)) {
                    srcList.add(node.id());
                    dstList.add(inId);
                    labelList.add(edge.label());
                }
            }
        }

        return new GnnExport(
            nodeIds,
            nodeLabels,
            srcList.toArray(),
            dstList.toArray(),
            labelList.toArray(new String[0])
        );
    }
}
