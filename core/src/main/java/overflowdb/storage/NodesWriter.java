package overflowdb.storage;

import overflowdb.Node;
import overflowdb.NodeDb;
import overflowdb.NodeRef;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Spliterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.IntStream;

/**
 * Persists collections of nodes in bulk to disk. Used either by ReferenceManager (if overflow to disk is enabled),
 * or alternatively when closing the graph (if storage to disk is enabled).
 */
public class NodesWriter {
    private final NodeSerializer nodeSerializer;
    private final OdbStorage storage;

    public NodesWriter(NodeSerializer nodeSerializer, OdbStorage storage) {
        this.nodeSerializer = nodeSerializer;
        this.storage = storage;
    }

    /** Nodes serialized per batch: bounds the serialized bytes held at once to a few MB. */
    static final int BATCH_SIZE = 8192;

    /**
     * Writes all references to storage, blocks until complete.
     * Serialization happens in parallel, however writing to storage happens sequentially, to avoid lock contention in mvstore.
     *
     * <p>A stream's parallel/sequential mode applies to its whole pipeline, and the last call wins - the earlier
     * {@code parallel().map(serialize).sequential().forEach(persist)} therefore serialized on the calling thread too.
     * Nodes are now serialized in parallel one batch at a time, and each batch is written in iteration order (the order
     * of the old sequential pipeline) while the next batch serializes.
     */
    public void writeAndClearBatched(Spliterator<? extends Node> nodes, int estimatedTotalCount) {
        final List<Node> all = new ArrayList<>(Math.max(estimatedTotalCount, 16));
        nodes.forEachRemaining(all::add);
        final int total = all.size();
        if (total == 0) return;

        CompletableFuture<SerializedNode[]> next = serializeBatchAsync(all, 0);
        for (int from = 0; from < total; from += BATCH_SIZE) {
            final SerializedNode[] batch = await(next);
            final int nextFrom = from + BATCH_SIZE;
            next = nextFrom < total ? serializeBatchAsync(all, nextFrom) : null;
            for (SerializedNode serializedNode : batch) {
                if (serializedNode != null) storage.persist(serializedNode.id, serializedNode.data);
            }
        }
    }

    /** Rethrows a serialization failure as the RuntimeException it was, not wrapped in a CompletionException. */
    private static SerializedNode[] await(CompletableFuture<SerializedNode[]> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) throw cause;
            throw e;
        }
    }

    private CompletableFuture<SerializedNode[]> serializeBatchAsync(List<Node> all, int from) {
        final int to = Math.min(from + BATCH_SIZE, all.size());
        return CompletableFuture.supplyAsync(() -> {
            final SerializedNode[] out = new SerializedNode[to - from];
            IntStream.range(from, to).parallel().forEach(i -> out[i - from] = serializeIfDirty(all.get(i)));
            return out;
        });
    }

    private SerializedNode serializeIfDirty(Node node) {
        NodeDb nodeDb = null;
        NodeRef<?> ref = null;
        if (node instanceof NodeDb) {
            nodeDb = (NodeDb) node;
            ref = nodeDb.ref;
        } else if (node instanceof NodeRef) {
            ref = (NodeRef) node;
            if (ref.isSet()) nodeDb = ref.get();
        }

        if (nodeDb != null && nodeDb.isDirty()) {
            try {
                byte[] data = nodeSerializer.serialize(nodeDb);
                NodeRef.clear(ref);
                return new SerializedNode(ref.id(), data);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return null;
    }

    private static class SerializedNode {
        private final long id;
        private final byte[] data;

        private SerializedNode(long id, byte[] data) {
            this.id = id;
            this.data = data;
        }
    }
}