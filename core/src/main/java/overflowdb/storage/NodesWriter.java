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
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
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

    /**
     * Serialization runs on this pool, not the common pool. Graph users run their own work on the common pool (chen's
     * passes do), and every thread that creates a node or edge waits while an eviction is in progress
     * (Graph.applyBackpressureMaybe). With serialization queued behind them on the same pool, the eviction never
     * finished, and the threads waited for good. The threads are daemons, shared by all graphs, and created on demand.
     */
    private static final ForkJoinPool SERIALIZER_POOL = new ForkJoinPool(
        Math.max(1, Runtime.getRuntime().availableProcessors()),
        pool -> {
            final ForkJoinWorkerThread thread = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
            thread.setName("overflowdb-serializer-" + thread.getPoolIndex());
            thread.setDaemon(true);
            return thread;
        },
        null, false);

    private volatile long batchesWritten = 0;

    /** Batches written so far: lets a thread waiting for an eviction tell a slow one from one that is stuck. */
    public long batchesWritten() {
        return batchesWritten;
    }

    /** Nodes serialized per batch at most, and in the first batch. */
    static final int BATCH_SIZE = 8192;
    /** Later batches are sized from the bytes per node so far to hold about this much: two batches are in memory at
     * once (one being written, the next serializing), and eviction runs when the heap is already short. */
    static final long BATCH_BYTES = 4L << 20;
    static final int MIN_BATCH_SIZE = 64;

    /**
     * Writes all references to storage, blocks until complete.
     * Serialization happens in parallel, however writing to storage happens sequentially, to avoid lock contention in mvstore.
     *
     * <p>A stream's parallel/sequential mode applies to its whole pipeline, and the last call wins - the earlier
     * {@code parallel().map(serialize).sequential().forEach(persist)} therefore serialized on the calling thread too.
     * Nodes are now serialized in parallel one batch at a time, and each batch is written in iteration order (the order
     * of the old sequential pipeline) while the next batch serializes.
     *
     * <p>A node is only released from memory once its bytes are in storage: until then a concurrent reader that finds
     * its reference cleared would read nothing, or an older version of the node, from disk. A node that storage already
     * holds unchanged, e.g. one read back from disk and not changed since, is released without writing it again.
     *
     * <p>A node that fails to serialize, e.g. because a property has a type the serializer does not support, stays in
     * memory and does not stop the other nodes from being written. Failure to write to storage still throws.
     *
     * @return the nodes that failed to serialize, with the first failure; empty if all were written
     */
    public Failures writeAndClearBatched(Spliterator<? extends Node> nodes, int estimatedTotalCount) {
        final List<Node> all = new ArrayList<>(Math.max(estimatedTotalCount, 16));
        nodes.forEachRemaining(all::add);
        final int total = all.size();
        final Failures failures = new Failures();
        if (total == 0) return failures;

        int batchSize = Math.min(BATCH_SIZE, total);
        CompletableFuture<SerializedNode[]> next = serializeBatchAsync(all, 0, batchSize, failures);
        long nodesSerialized = 0;
        long bytesSerialized = 0;
        for (int from = 0; from < total; ) {
            final SerializedNode[] batch = await(next);
            final int nextFrom = from + batch.length;
            long batchBytes = 0;
            for (SerializedNode serializedNode : batch) {
                if (serializedNode != null && serializedNode.data != null) batchBytes += serializedNode.data.length;
            }
            nodesSerialized += batch.length;
            bytesSerialized += batchBytes;
            batchSize = nextBatchSize(nodesSerialized, bytesSerialized);
            next = nextFrom < total ? serializeBatchAsync(all, nextFrom, batchSize, failures) : null;
            for (int i = 0; i < batch.length; i++) {
                final SerializedNode serializedNode = batch[i];
                if (serializedNode != null) {
                    if (serializedNode.data != null) storage.persist(serializedNode.ref.id(), serializedNode.data);
                    serializedNode.release();
                    batch[i] = null; // the bytes can go as soon as they are in storage
                }
            }
            batchesWritten++;
            from = nextFrom;
        }
        return failures;
    }

    /** Nodes that could not be serialized during one {@link #writeAndClearBatched} call. Thread-safe. */
    public static final class Failures {
        private final List<NodeRef<?>> refs = new ArrayList<>();
        private RuntimeException first;
        private long firstId;

        synchronized void add(NodeRef<?> ref, RuntimeException e) {
            refs.add(ref);
            if (first == null) {
                first = e;
                firstId = ref.id();
            }
        }

        public synchronized boolean isEmpty() {
            return refs.isEmpty();
        }

        public synchronized List<NodeRef<?>> refs() {
            return new ArrayList<>(refs);
        }

        public synchronized RuntimeException first() {
            return first;
        }

        /** Throws the first failure, with the number of nodes affected, if there was any. */
        public synchronized void throwIfAny() {
            if (first != null) {
                throw new RuntimeException(
                    refs.size() + " node(s) could not be serialized, first: node " + firstId + ": " + first.getMessage(), first);
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

    static int nextBatchSize(long nodesSerialized, long bytesSerialized) {
        if (bytesSerialized <= 0) return BATCH_SIZE;
        final long bytesPerNode = Math.max(1, bytesSerialized / nodesSerialized);
        return (int) Math.max(MIN_BATCH_SIZE, Math.min(BATCH_SIZE, BATCH_BYTES / bytesPerNode));
    }

    private CompletableFuture<SerializedNode[]> serializeBatchAsync(List<Node> all, int from, int size, Failures failures) {
        final int to = Math.min(from + size, all.size());
        // a parallel stream started on a ForkJoinPool thread runs on that pool
        return CompletableFuture.supplyAsync(() -> {
            final SerializedNode[] out = new SerializedNode[to - from];
            IntStream.range(from, to).parallel().forEach(i -> out[i - from] = serializeIfDirty(all.get(i), failures));
            return out;
        }, SERIALIZER_POOL);
    }

    private SerializedNode serializeIfDirty(Node node, Failures failures) {
        NodeDb nodeDb = null;
        NodeRef<?> ref = null;
        if (node instanceof NodeDb) {
            nodeDb = (NodeDb) node;
            ref = nodeDb.ref;
        } else if (node instanceof NodeRef) {
            ref = (NodeRef) node;
            // not get(): a reference cleared meanwhile would load its node, and wait for this clearing to end
            nodeDb = ref.getOption().orElse(null);
        }

        if (nodeDb == null) return null;
        if (nodeDb.isDirty()) {
            try {
                /* read before serializing: a change after this read is newer than what gets written */
                final int version = nodeDb._changeCount();
                byte[] data = nodeSerializer.serialize(nodeDb);
                return new SerializedNode(ref, nodeDb, data, version);
            } catch (IOException | RuntimeException e) {
                /* serialize marked the node clean before it failed: mark it dirty again so it is not taken for saved */
                nodeDb.markAsDirty();
                failures.add(ref, e instanceof RuntimeException re ? re : new RuntimeException(e));
                return null;
            }
        }
        if (nodeDb.isPersistedAndClean()) return new SerializedNode(ref, nodeDb, null, -1);
        return null;
    }

    private static class SerializedNode {
        private final NodeRef<?> ref;
        private final NodeDb node;
        private final byte[] data;
        private final int version;

        private SerializedNode(NodeRef<?> ref, NodeDb node, byte[] data, int version) {
            this.ref = ref;
            this.node = node;
            this.data = data;
            this.version = version;
        }

        /** `data` is null for a node storage already holds unchanged, which is released without writing it.
         * Clears the reference once its node is persisted. A node changed since it was serialized is put back, so
         * that its newer state is written by a later clearing or when the graph closes: here if the change is already
         * marked, otherwise by the change itself (NodeDb.markAsDirty), which then finds the reference cleared. */
        @SuppressWarnings("unchecked")
        private void release() {
            if (data != null) node.markPersistedAt(version);
            if (!ref.clearIfHolds(node)) return;
            if (node.isDirty()) ((NodeRef<NodeDb>) ref).reattach(node);
        }
    }
}