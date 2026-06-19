package overflowdb;

import overflowdb.util.IteratorUtils;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class BatchedUpdate {

    public static final Object[] emptyArray = new Object[]{};

    public static AppliedDiff applyDiff(Graph graph, DiffOrBuilder diff) {
        return new DiffGraphApplier(graph, diff, null, null).run();
    }

    public static AppliedDiff applyDiff(Graph graph, DiffOrBuilder diff, KeyPool keyPool, ModificationListener listener) {
        return new DiffGraphApplier(graph, diff, keyPool, listener).run();
    }

    public static AppliedDiff applyFragment(Graph graph, byte[] fragmentBytes, long expectedSchemaHash, BoundaryResolver boundaryResolver, KeyPool keyPool) {
        return applyFragment(graph, fragmentBytes, expectedSchemaHash, boundaryResolver, keyPool, null);
    }

    public static AppliedDiff applyFragment(Graph graph, byte[] fragmentBytes, long expectedSchemaHash, BoundaryResolver boundaryResolver, KeyPool keyPool, ModificationListener listener) {
        overflowdb.storage.GraphFragmentCodec.FragmentHeader header = overflowdb.storage.GraphFragmentCodec.peek(fragmentBytes);
        if (header.schemaHash != expectedSchemaHash) {
            throw new IllegalArgumentException("Schema hash mismatch: fragment schema hash is " + header.schemaHash + ", expected " + expectedSchemaHash);
        }

        overflowdb.storage.DecodedFragment decoded = overflowdb.storage.GraphFragmentCodec.decode(fragmentBytes);

        Node[] localToGlobalNode = new Node[decoded.nodes.size()];
        DetachedNodeGeneric[] detachedNodes = new DetachedNodeGeneric[decoded.nodes.size()];

        for (int i = 0; i < decoded.nodes.size(); i++) {
            overflowdb.storage.DecodedFragment.DecodedNode dNode = decoded.nodes.get(i);
            Node liveNode;
            if (keyPool == null) {
                liveNode = graph.addNode(dNode.label);
            } else {
                liveNode = graph.addNode(keyPool.next(), dNode.label);
            }
            localToGlobalNode[i] = liveNode;
        }

        for (int i = 0; i < decoded.nodes.size(); i++) {
            overflowdb.storage.DecodedFragment.DecodedNode dNode = decoded.nodes.get(i);
            Object[] keyvalues = new Object[dNode.properties.size() * 2];
            int kvIdx = 0;
            for (Map.Entry<String, Object> entry : dNode.properties.entrySet()) {
                keyvalues[kvIdx++] = entry.getKey();
                keyvalues[kvIdx++] = remapProperty(entry.getValue(), localToGlobalNode);
            }
            DetachedNodeGeneric detachedNode = new DetachedNodeGeneric(dNode.label, keyvalues);
            detachedNode.setRefOrId(localToGlobalNode[i]);
            detachedNodes[i] = detachedNode;
        }

        // Manually initialize the pre-allocated nodes to set properties
        for (int i = 0; i < localToGlobalNode.length; i++) {
            Node liveNode = localToGlobalNode[i];
            DetachedNodeGeneric detachedNode = detachedNodes[i];
            Node.initializeFromDetached(liveNode, detachedNode, (dn) -> {
                for (int j = 0; j < detachedNodes.length; j++) {
                    if (detachedNodes[j] == dn) {
                        return localToGlobalNode[j];
                    }
                }
                return null;
            });
            if (listener != null) {
                listener.onAfterInitNewNode(liveNode);
            }
        }

        List<Change> changes = new ArrayList<>();

        for (DetachedNodeGeneric dn : detachedNodes) {
            changes.add(dn);
        }

        for (overflowdb.storage.DecodedFragment.DecodedEdge dEdge : decoded.edges) {
            DetachedNodeGeneric src = detachedNodes[dEdge.srcLocal];
            DetachedNodeGeneric dst = detachedNodes[dEdge.dstLocal];
            Object[] properties = new Object[dEdge.properties.size() * 2];
            int kvIdx = 0;
            for (Map.Entry<String, Object> entry : dEdge.properties.entrySet()) {
                properties[kvIdx++] = entry.getKey();
                properties[kvIdx++] = remapProperty(entry.getValue(), localToGlobalNode);
            }
            changes.add(new CreateEdge(dEdge.label, src, dst, properties));
        }

        for (overflowdb.storage.DecodedFragment.DecodedBoundaryRef dRef : decoded.boundaryRefs) {
            DetachedNodeGeneric src = detachedNodes[dRef.srcLocal];
            Optional<Node> resolvedDstOpt = boundaryResolver.resolve(dRef.key);
            if (resolvedDstOpt != null && resolvedDstOpt.isPresent()) {
                Node resolvedDst = resolvedDstOpt.get();
                changes.add(new CreateEdge(dRef.label, src, resolvedDst, null));
            }
        }

        DiffGraph diff = new DiffGraph(changes.toArray(new Change[0]));
        return applyDiff(graph, diff, keyPool, listener);
    }

    private static Object remapProperty(Object value, Node[] localToGlobalNode) {
        if (value instanceof overflowdb.storage.LocalNodeRef) {
            return localToGlobalNode[((overflowdb.storage.LocalNodeRef) value).localId];
        } else if (value instanceof List) {
            List<?> list = (List<?>) value;
            List<Object> copy = new ArrayList<>(list.size());
            for (Object o : list) {
                copy.add(remapProperty(o, localToGlobalNode));
            }
            return copy;
        } else if (value instanceof Object[]) {
            Object[] arr = (Object[]) value;
            Object[] copy = new Object[arr.length];
            for (int i = 0; i < arr.length; i++) {
                copy[i] = remapProperty(arr[i], localToGlobalNode);
            }
            return copy;
        }
        return value;
    }

    public interface KeyPool {
        long next();
    }

    public interface DiffOrBuilder {
        int size();

        Iterator<Change> iterator();
    }

    public interface ModificationListener {
        void onAfterInitNewNode(Node node);

        void onAfterAddNewEdge(Edge edge);

        void onBeforePropertyChange(Node node, String key);

        void onAfterPropertyChange(Node node, String key, Object value);

        void onBeforeRemoveNode(Node node);

        void onBeforeRemoveEdge(Edge edge);

        void finish();
    }


    //Interface Change
    public interface Change {
    }

    public static class DiffGraph implements DiffOrBuilder {
        public final Change[] changes;

        DiffGraph(Change[] changes) {
            this.changes = changes;
        }

        @Override
        public int size() {
            return changes.length;
        }

        @Override
        public Iterator<Change> iterator() {
            return new IteratorUtils.ArrayIterator<>(changes);
        }

    }

    public static class DiffGraphBuilder implements DiffOrBuilder {
        private ArrayDeque<Change> _buffer = new ArrayDeque<>();

        public DiffGraphBuilder() {
        }


        public DiffGraph build() {
            DiffGraph res = new DiffGraph(_buffer.toArray(new Change[]{}));
            this._buffer = null;
            return res;
        }

        public int size() {
            return _buffer.size();
        }

        public Iterator<Change> iterator() {
            return _buffer.iterator();
        }

        public DiffGraphBuilder absorb(DiffGraphBuilder other) {
            if (this._buffer.size() > other._buffer.size()) {
                _buffer.addAll(other._buffer);
                other._buffer = null;
            } else {
                ArrayDeque<Change> tmp = this._buffer;
                this._buffer = other._buffer;
                other._buffer = null;
                for (Iterator<Change> it = tmp.descendingIterator(); it.hasNext(); ) {
                    Change change = it.next();
                    _buffer.addFirst(change);
                }
            }
            return this;
        }

        public DiffGraphBuilder addNode(DetachedNodeData node) {
            _buffer.addLast(node);
            return this;
        }

        public DiffGraphBuilder addNode(String label, Object... keyvalues) {
            _buffer.addLast(new DetachedNodeGeneric(label, keyvalues));
            return this;
        }

        public DiffGraphBuilder addEdge(NodeOrDetachedNode src, NodeOrDetachedNode dst, String label) {
            _buffer.addLast(new CreateEdge(label, src, dst, null));
            return this;
        }

        public DiffGraphBuilder addEdge(NodeOrDetachedNode src, NodeOrDetachedNode dst, String label, Object... properties) {
            _buffer.addLast(new CreateEdge(label, src, dst, properties.length > 0 ? properties : null));
            return this;
        }

        public DiffGraphBuilder setNodeProperty(Node node, String label, Object property) {
            _buffer.addLast(new SetNodeProperty(label, node, property));
            return this;
        }

        public DiffGraphBuilder removeNode(Node node) {
            _buffer.addLast(new RemoveNode(node));
            return this;
        }

        public DiffGraphBuilder removeEdge(Edge edge) {
            _buffer.addLast(new RemoveEdge(edge));
            return this;
        }
        //missing API functions (not implemented because not needed at this time):
        //setEdgeProperty etc.
    }

    public static class AppliedDiff {
        public DiffOrBuilder diffGraph;
        private final ModificationListener listener;
        private final int transitiveModifications;
        private final Graph graph;

        AppliedDiff(Graph graph, DiffOrBuilder diffGraph, ModificationListener listener, int transitiveModifications) {
            this.graph = graph;
            this.diffGraph = diffGraph;
            this.listener = listener;
            this.transitiveModifications = transitiveModifications;
        }

        public DiffGraph getDiffGraph() {
            if (diffGraph instanceof DiffGraphBuilder) {
                this.diffGraph = ((DiffGraphBuilder) diffGraph).build();
            }
            return (DiffGraph) diffGraph;
        }

        public ModificationListener getListener() {
            return listener;
        }

        public int explicitModifications() {
            return diffGraph.size();
        }

        public int transitiveModifications() {
            return transitiveModifications;
        }
    }

    private static class RemoveEdge implements Change {
        public Edge edge;

        public RemoveEdge(Edge edge) {
            this.edge = edge;
        }
    }

    public static class CreateNode implements Change {
        public String label;
        public Object[] ProprtiesAndKeys;
        public long id; // 0 means that the label is not unknown

        public CreateNode(String label) {
            this.label = label;
        }
    }

    public static class RemoveNode implements Change {
        public Node node;

        public RemoveNode(Node node) {
            this.node = node;
        }
    }

    public static class CreateEdge implements Change {
        public String label;
        public NodeOrDetachedNode src;
        public NodeOrDetachedNode dst;
        public Object[] propertiesAndKeys;

        public CreateEdge(String label, NodeOrDetachedNode src, NodeOrDetachedNode dst, Object[] propertiesAndKeys) {
            this.label = label;
            this.src = src;
            this.dst = dst;
            this.propertiesAndKeys = propertiesAndKeys;
        }
    }

    public static class SetNodeProperty implements Change {
        public String label;
        public Node node;
        public Object value;

        public SetNodeProperty(String label, Node node, Object value) {
            this.label = label;
            this.node = node;
            this.value = value;
        }
    }

    private static class DiffGraphApplier {
        private final DiffOrBuilder diff;
        private final KeyPool keyPool;
        private final ModificationListener listener;
        private final ArrayDeque<DetachedNodeData> deferredInitializers = new ArrayDeque<>();
        private final Graph graph;
        private int nChanges = 0;

        DiffGraphApplier(Graph graph, DiffOrBuilder diff, KeyPool keyPool, ModificationListener listener) {
            this.diff = diff;
            this.keyPool = keyPool;
            this.listener = listener;
            this.graph = graph;
        }

        AppliedDiff run() {
            try {
                for (Iterator<Change> it = diff.iterator(); it.hasNext(); ) {
                    Change change = it.next();
                    applyChange(change);
                }
            } finally {
                if (listener != null)
                    listener.finish();
            }
            return new AppliedDiff(graph, diff, listener, nChanges);
        }


        private Node mapDetached(DetachedNodeData detachedNode) {
            Object linkedNode = detachedNode.getRefOrId();
            if (linkedNode == null || linkedNode instanceof Long) {
                if (linkedNode == null) {
                    if (keyPool == null) {
                        linkedNode = graph.addNode(detachedNode.label());
                    } else {
                        linkedNode = graph.addNode(keyPool.next(), detachedNode.label());
                    }
                } else {
                    linkedNode = graph.addNode((Long) linkedNode, detachedNode.label());
                }
                detachedNode.setRefOrId(linkedNode);
                deferredInitializers.addLast(detachedNode);
            }
            return (Node) linkedNode;
        }

        private void drainDeferred() {
            while (!deferredInitializers.isEmpty()) {
                DetachedNodeData detachedNode = deferredInitializers.removeFirst();
                Node actualNode = (Node) detachedNode.getRefOrId();
                Node.initializeFromDetached(actualNode, detachedNode, this::mapDetached);
                nChanges += 1;
                if (listener != null) {
                    listener.onAfterInitNewNode(actualNode);
                }
            }
        }

        private void applyChange(Change change) {
            if (change instanceof DetachedNodeData) {
                mapDetached((DetachedNodeData) change);
                drainDeferred();
            } else if (change instanceof CreateEdge create) {
                nChanges += 1;
                Node src = create.src instanceof DetachedNodeData ? mapDetached((DetachedNodeData) create.src) : (Node) create.src;
                Node dst = create.dst instanceof DetachedNodeData ? mapDetached((DetachedNodeData) create.dst) : (Node) create.dst;
                drainDeferred();
                Object[] properties = create.propertiesAndKeys == null ? emptyArray : create.propertiesAndKeys;
                if (listener != null) {
                    Edge edge = src.addEdgeInternal(create.label, dst, properties);
                    listener.onAfterAddNewEdge(edge);
                } else {
                    src.addEdgeSilentInternal(create.label, dst, properties);
                }
            } else if (change instanceof RemoveEdge remove) {
                nChanges += 1;
                if (listener != null)
                    listener.onBeforeRemoveEdge(remove.edge);
                remove.edge.removeInternal();
            } else if (change instanceof RemoveNode remove) {
                nChanges += 1;
                if (listener != null)
                    listener.onBeforeRemoveNode(remove.node);
                remove.node.removeInternal();

            } else if (change instanceof SetNodeProperty setProp) {
                nChanges += 1;
                if (listener != null)
                    listener.onBeforePropertyChange(setProp.node, setProp.label);
                setProp.node.setPropertyInternal(setProp.label, setProp.value);
                if (listener != null)
                    listener.onAfterPropertyChange(setProp.node, setProp.label, setProp.value);
                drainDeferred();
            }
        }
    }


}
