package overflowdb;

import org.junit.Test;
import overflowdb.testdomains.simple.SimpleDomain;
import overflowdb.testdomains.simple.TestNode;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

/**
 * An eviction finishes while every common-pool thread creates nodes. Those threads wait for the eviction in progress
 * (backpressure), and the eviction serialized its nodes on the common pool: it waited for them, they for it. Seen in
 * atom on a 320 MB heap, where chen's passes run on the common pool.
 */
public class EvictionDeadlockTest {

  @Test(timeout = 60_000)
  public void evictionFinishesWhileTheCommonPoolCreatesNodes() throws Exception {
    try (Graph graph = SimpleDomain.newGraph(Config.withDefaults().withHeapPercentageThreshold(99))) {
      final List<NodeRef<?>> nodes = new ArrayList<>();
      for (int i = 0; i < 50_000; i++) nodes.add((NodeRef<?>) graph.addNode(TestNode.LABEL, TestNode.STRING_PROPERTY, "n" + i));
      ReferenceManager referenceManager = referenceManager(graph);
      AtomicBoolean pending = evictionPending(referenceManager);

      final int workers = ForkJoinPool.getCommonPoolParallelism();
      final CountDownLatch started = new CountDownLatch(workers);
      final AtomicBoolean stop = new AtomicBoolean();
      final List<java.util.concurrent.Future<?>> tasks = new ArrayList<>();
      for (int w = 0; w < workers; w++) {
        tasks.add(ForkJoinPool.commonPool().submit(() -> {
          started.countDown();
          while (!stop.get()) graph.addNode(TestNode.LABEL, TestNode.STRING_PROPERTY, "x");
        }));
      }
      assertTrue(started.await(10, TimeUnit.SECONDS));

      referenceManager.triggerAsynchronousEviction();
      final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
      while (pending.get() && System.nanoTime() < deadline) Thread.sleep(10);
      final boolean finished = !pending.get();
      stop.set(true);
      for (java.util.concurrent.Future<?> task : tasks) task.get(30, TimeUnit.SECONDS);
      assertTrue("eviction did not finish while the common pool was busy creating nodes", finished);
      assertTrue("nothing was evicted", nodes.stream().anyMatch(ref -> !ref.isSet()));
    }
  }

  private static ReferenceManager referenceManager(Graph graph) throws ReflectiveOperationException {
    Field field = Graph.class.getDeclaredField("referenceManager");
    field.setAccessible(true);
    return (ReferenceManager) field.get(graph);
  }

  private static AtomicBoolean evictionPending(ReferenceManager referenceManager) throws ReflectiveOperationException {
    Field field = ReferenceManager.class.getDeclaredField("evictionPending");
    field.setAccessible(true);
    return (AtomicBoolean) field.get(referenceManager);
  }
}
