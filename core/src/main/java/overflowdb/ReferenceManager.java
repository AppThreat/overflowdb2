package overflowdb;

import overflowdb.storage.NodesWriter;
import overflowdb.storage.OdbStorage;
import overflowdb.util.NamedThreadFactory;
import overflowdb.util.HeapUsageMonitor;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Optimized ReferenceManager using ConcurrentLinkedQueue to avoid O(N) array shifting
 * during batch removals.
 */
public class ReferenceManager implements AutoCloseable {

  public final int releaseCount = 100000;
  /** At most this many batches per trigger, i.e. per collection that found the heap above the threshold. */
  static final int MAX_BATCHES_PER_TRIGGER = 10;
  private final ExecutorService executorService;
  private final boolean shutdownExecutorOnClose;
  private int clearingProcessCount = 0;
  private final Object backPressureSyncObject = new Object();
  private final OdbStorage storage;
  private final NodesWriter nodesWriter;
  private volatile HeapUsageMonitor heapUsageMonitor;

  private final Queue<NodeRef<?>> clearableRefs = new ConcurrentLinkedQueue<>();

  private static final System.Logger LOGGER = System.getLogger(ReferenceManager.class.getName());
  private final java.util.concurrent.atomic.AtomicBoolean evictionFailureLogged = new java.util.concurrent.atomic.AtomicBoolean();
  private final java.util.concurrent.atomic.AtomicBoolean evictionPending = new java.util.concurrent.atomic.AtomicBoolean();

  public ReferenceManager(OdbStorage storage, NodesWriter nodesWriter) {
    this(storage, nodesWriter, Executors.newSingleThreadExecutor(new NamedThreadFactory("overflowdb-reference-manager")), true);
  }

  public ReferenceManager(OdbStorage storage, NodesWriter nodesWriter, ExecutorService executorService) {
    this(storage, nodesWriter, executorService, false);
  }

  private ReferenceManager(OdbStorage storage, NodesWriter nodesWriter, ExecutorService executorService, boolean shutdownExecutorOnClose) {
    this.storage = storage;
    this.nodesWriter = nodesWriter;
    this.executorService = executorService;
    this.shutdownExecutorOnClose = shutdownExecutorOnClose;
  }

  /** Evicts a batch of nodes on the eviction thread. A request made while a batch is pending is dropped: the heap
   * monitor now hears of every collection, and young collections come in quick succession. The monitor asks again
   * after the next collection if the heap is still above the threshold. */
  public void triggerAsynchronousEviction() {
    if (!evictionPending.compareAndSet(false, true)) return;
    try {
      executorService.submit(() -> {
        try {
          /* Batches go on while the old generation is nearly full: a collection that would report it again may be
           * far off, under G1 a concurrent cycle, and allocation goes on meanwhile. The bound keeps a heap that is
           * full of something other than nodes from making this spin. */
          for (int batch = 0; batch < MAX_BATCHES_PER_TRIGGER; batch++) {
            if (syncClearReferences(releaseCount) == 0) break;
            final HeapUsageMonitor monitor = heapUsageMonitor;
            if (monitor == null || !monitor.isNearlyFullNow()) break;
          }
        } finally {
          final HeapUsageMonitor monitor = heapUsageMonitor;
          if (monitor != null) monitor.evicted();
          evictionPending.set(false);
        }
      });
    } catch (java.util.concurrent.RejectedExecutionException e) {
      // shut down: the graph is closing
      evictionPending.set(false);
    }
  }

  public synchronized void installHeapUsageMonitor(int heapPercentageThreshold) {
    if (heapUsageMonitor != null) {
      heapUsageMonitor.close();
    }
    heapUsageMonitor = HeapUsageMonitor.install(this, heapPercentageThreshold);
  }

  /* Register NodeRef, so it can be cleared on low memory */
  public void registerRef(NodeRef<?> ref) {
    clearableRefs.add(ref);
  }

  /** A thread creating a node or edge stops waiting for an eviction that has written no batch for this long. */
  static final long MAX_BACKPRESSURE_WAIT_WITHOUT_PROGRESS_MILLIS = 10_000;

  /**
   * Slows down threads that create nodes or edges while an eviction is in progress, so that eviction keeps up with
   * them. A thread that waits here must not hold a lock the eviction needs: a node read back from disk skips the wait
   * (NodeDb), since its thread holds the ref's monitor, and serialization runs on its own pool (NodesWriter). As a
   * safety net, a thread stops waiting once the eviction has made no progress for 10 s: a caller may hold a node's
   * monitor, which the serializer takes. A slow eviction (a large batch, a slow disk) is waited for to the end:
   * resuming early only allocates more, and ran a heap out of memory under emulation on Windows.
   */
  public void applyBackpressureMaybe() {
    synchronized (backPressureSyncObject) {
      if (clearingProcessCount == 0) return;
      long progress = nodesWriter.batchesWritten();
      long lastProgressAt = System.nanoTime();
      while (clearingProcessCount > 0) {
        try {
          backPressureSyncObject.wait(1000);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
        final long now = System.nanoTime();
        final long current = nodesWriter.batchesWritten();
        if (current != progress) {
          progress = current;
          lastProgressAt = now;
        } else if (now - lastProgressAt > MAX_BACKPRESSURE_WAIT_WITHOUT_PROGRESS_MILLIS * 1_000_000L) {
          return;
        }
      }
    }
  }

  /** @return the number of references taken off the queue */
  private int syncClearReferences(final int releaseCount) {
    final List<NodeRef<?>> refsToClear = collectRefsToClear(releaseCount);
    if (!refsToClear.isEmpty()) {
      safelyClearReferences(refsToClear);
    }
    return refsToClear.size();
  }

  private List<NodeRef<?>> collectRefsToClear(int releaseCount) {
    final List<NodeRef<?>> refsToClear = new ArrayList<>(releaseCount);

    while (releaseCount > 0) {
      final NodeRef<?> ref = clearableRefs.poll();
      if (ref == null) {
        break;
      }
      refsToClear.add(ref);
      releaseCount--;
    }

    return refsToClear;
  }

  private void safelyClearReferences(final List<NodeRef<?>> refsToClear) {
    try {
      synchronized (backPressureSyncObject) {
        clearingProcessCount += 1;
      }
      final NodesWriter.Failures failures = nodesWriter.writeAndClearBatched(refsToClear.spliterator(), refsToClear.size());
      storage.flush();
      if (!failures.isEmpty()) {
        /* the nodes stay in memory; queue them again so a later eviction retries them, e.g. once a property they
         * could not be written with has changed */
        requeue(failures.refs());
        warnOnce("could not write " + failures.refs().size() + " node(s) to disk, they stay in memory", failures.first());
      }
    } catch (Exception | Error e) {
      /* storage failed partway, or ran out of memory: the nodes not cleared yet are still in memory and must stay
       * evictable */
      requeue(refsToClear);
      warnOnce("evicting nodes to disk failed, they stay in memory", e);
    } finally {
      synchronized (backPressureSyncObject) {
        clearingProcessCount -= 1;
        if (clearingProcessCount == 0) {
          backPressureSyncObject.notifyAll();
        }
      }
    }
  }

  private void requeue(List<NodeRef<?>> refs) {
    for (NodeRef<?> ref : refs) {
      if (ref.isSet()) clearableRefs.add(ref);
    }
  }

  /** Logs the first eviction failure of this graph; later ones would repeat it for every batch. */
  private void warnOnce(String message, Throwable cause) {
    if (evictionFailureLogged.compareAndSet(false, true)) {
      LOGGER.log(System.Logger.Level.WARNING, "overflowdb: " + message + " (further failures are not logged)", cause);
    }
  }

  /** Writes every node in memory to storage, e.g. when the graph is closed. Throws if a node cannot be written: its
   * state would otherwise be lost. */
  public void clearAllReferences() {
    List<NodeRef<?>> allRefs = new ArrayList<>(clearableRefs.size());
    NodeRef<?> ref;
    while ((ref = clearableRefs.poll()) != null) {
      allRefs.add(ref);
    }
    nodesWriter.writeAndClearBatched(allRefs.spliterator(), allRefs.size()).throwIfAny();
  }

  /** Number of nodes queued for eviction. For tests. */
  int queuedForEviction() {
    return clearableRefs.size();
  }

  @Override
  public synchronized void close() {
    // the GC MXBeans outlive this graph: their listeners must not keep us (and our storage) reachable
    if (heapUsageMonitor != null) {
      heapUsageMonitor.close();
      heapUsageMonitor = null;
    }
    if (shutdownExecutorOnClose) {
      executorService.shutdown();
    }
  }
}