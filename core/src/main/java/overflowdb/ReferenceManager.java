package overflowdb;

import overflowdb.storage.NodesWriter;
import overflowdb.storage.OdbStorage;
import overflowdb.util.NamedThreadFactory;

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
  private final ExecutorService executorService;
  private final boolean shutdownExecutorOnClose;
  private int clearingProcessCount = 0;
  private final Object backPressureSyncObject = new Object();
  private final OdbStorage storage;
  private final NodesWriter nodesWriter;

  private final Queue<NodeRef> clearableRefs = new ConcurrentLinkedQueue<>();

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

  /* Register NodeRef, so it can be cleared on low memory */
  public void registerRef(NodeRef ref) {
    clearableRefs.add(ref);
  }

  public void applyBackpressureMaybe() {
    synchronized (backPressureSyncObject) {
      while (clearingProcessCount > 0) {
        try {
          backPressureSyncObject.wait();
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
      }
    }
  }

  /**
   * run clearing of references asynchronously to not block the gc notification thread
   */
  private void syncClearReferences(final int releaseCount) {
    final List<NodeRef> refsToClear = collectRefsToClear(releaseCount);
    if (!refsToClear.isEmpty()) {
      safelyClearReferences(refsToClear);
    }
  }

  private List<NodeRef> collectRefsToClear(int releaseCount) {
    final List<NodeRef> refsToClear = new ArrayList<>(releaseCount);

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

  private void safelyClearReferences(final List<NodeRef> refsToClear) {
    try {
      synchronized (backPressureSyncObject) {
        clearingProcessCount += 1;
      }
      nodesWriter.writeAndClearBatched(refsToClear.spliterator(), refsToClear.size());
      storage.flush();
    } catch (Exception e) {
    } finally {
      synchronized (backPressureSyncObject) {
        clearingProcessCount -= 1;
        if (clearingProcessCount == 0) {
          backPressureSyncObject.notifyAll();
        }
      }
    }
  }

  public void clearAllReferences() {
    List<NodeRef> allRefs = new ArrayList<>(clearableRefs.size());
    NodeRef ref;
    while ((ref = clearableRefs.poll()) != null) {
      allRefs.add(ref);
    }
    nodesWriter.writeAndClearBatched(allRefs.spliterator(), allRefs.size());
  }

  @Override
  public void close() {
    if (shutdownExecutorOnClose) {
      executorService.shutdown();
    }
  }
}