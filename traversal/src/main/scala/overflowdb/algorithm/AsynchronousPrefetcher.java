package overflowdb.algorithm;

import overflowdb.Node;
import overflowdb.NodeRef;

import java.util.Collection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Utility for asynchronously pre-fetching evicted graph nodes from backing storage.
 * By loading nodes in a background thread pool, long traversals do not experience sync I/O blocking.
 */
public class AsynchronousPrefetcher {
    private final ExecutorService executor;

    public AsynchronousPrefetcher() {
        this(Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
    }

    public AsynchronousPrefetcher(int numThreads) {
        this.executor = Executors.newFixedThreadPool(numThreads, r -> {
            Thread t = new Thread(r, "overflowdb-prefetcher");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Submits a collection of nodes for asynchronous pre-fetching.
     * @param nodes The target nodes to pre-fetch. Only evicted NodeRef elements are loaded.
     */
    public void prefetch(Collection<Node> nodes) {
        for (Node node : nodes) {
            if (node instanceof NodeRef) {
                NodeRef<?> ref = (NodeRef<?>) node;
                if (ref.isCleared()) {
                    executor.submit(ref::get);
                }
            }
        }
    }

    /**
     * Shuts down the prefetcher thread pool.
     */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
