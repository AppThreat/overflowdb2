package overflowdb;

import org.junit.Test;
import overflowdb.storage.NodesWriter;
import overflowdb.testdomains.simple.TestEdge;
import overflowdb.testdomains.simple.TestNode;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import static org.junit.Assert.*;

/**
 * A node that cannot be written to disk stays in memory and evictable, and does not stop the others from being
 * written. Before, a failed eviction batch was dropped silently: its nodes left the eviction queue for good.
 */
public class EvictionFailureTest {

  private static final String POISON = "cannot be serialized";

  /** Fails to persist any property value equal to POISON, as an unsupported property type would. */
  private static final Function<Object, Object> CONVERT = value -> {
    if (POISON.equals(value)) throw new UnsupportedOperationException("value not supported: " + value);
    return value;
  };

  private static Graph newGraph(Config config) {
    return Graph.open(config, Arrays.asList(TestNode.factory), Arrays.asList(TestEdge.factory), CONVERT);
  }

  private static ReferenceManager referenceManager(Graph graph) throws ReflectiveOperationException {
    Field field = Graph.class.getDeclaredField("referenceManager");
    field.setAccessible(true);
    return (ReferenceManager) field.get(graph);
  }

  /** Evicts on the eviction thread, as the heap monitor would, and waits until that eviction is done. */
  private static void evict(ReferenceManager referenceManager) throws Exception {
    Field pending = ReferenceManager.class.getDeclaredField("evictionPending");
    pending.setAccessible(true);
    java.util.concurrent.atomic.AtomicBoolean flag = (java.util.concurrent.atomic.AtomicBoolean) pending.get(referenceManager);
    referenceManager.triggerAsynchronousEviction();
    for (int i = 0; i < 500 && flag.get(); i++) Thread.sleep(10);
    assertFalse("eviction did not finish", flag.get());
  }

  @Test
  public void writerReportsNodesItCouldNotWriteAndWritesTheRest() {
    try (Graph graph = newGraph(Config.withoutOverflow())) {
      List<Node> nodes = new ArrayList<>();
      for (int i = 0; i < 20_000; i++) {
        nodes.add(graph.addNode(TestNode.LABEL, TestNode.STRING_PROPERTY, i == 9_000 || i == 15_000 ? POISON : "n" + i));
      }
      NodesWriter.Failures failures = new NodesWriter(graph.nodeSerializer, graph.getStorage())
          .writeAndClearBatched(nodes.spliterator(), nodes.size());

      assertEquals(new java.util.HashSet<>(Arrays.asList(nodes.get(9_000), nodes.get(15_000))), new java.util.HashSet<>(failures.refs()));
      assertTrue(failures.first() instanceof UnsupportedOperationException);
      for (int i = 0; i < nodes.size(); i++) {
        NodeRef<?> ref = (NodeRef<?>) nodes.get(i);
        if (i == 9_000 || i == 15_000) {
          assertTrue("a node that failed must stay in memory", ref.isSet());
          assertTrue("and must still count as changed", ref.get().isDirty());
        } else {
          assertTrue("node " + i + " should have been written and released", ref.isCleared());
        }
      }
      // the written nodes read back from disk
      assertEquals("n1", nodes.get(1).property(TestNode.STRING_PROPERTY));
      try {
        failures.throwIfAny();
        fail("expected the failures to be thrown");
      } catch (RuntimeException e) {
        assertTrue(e.getMessage(), e.getMessage().startsWith("2 node(s) could not be serialized"));
      }
    }
  }

  @Test
  public void failedNodesStayQueuedAndAreEvictedOnceWritable() throws Exception {
    try (Graph graph = newGraph(Config.withDefaults().withHeapPercentageThreshold(99))) {
      ReferenceManager referenceManager = referenceManager(graph);
      Node good = graph.addNode(TestNode.LABEL, TestNode.STRING_PROPERTY, "good");
      Node bad = graph.addNode(TestNode.LABEL, TestNode.STRING_PROPERTY, POISON);
      good.addEdge(TestEdge.LABEL, bad);

      evict(referenceManager);
      assertTrue(((NodeRef<?>) good).isCleared());
      assertTrue(((NodeRef<?>) bad).isSet());
      assertEquals("the failed node is queued again", 1, referenceManager.queuedForEviction());

      // a second attempt fails again, and keeps it queued
      evict(referenceManager);
      assertTrue(((NodeRef<?>) bad).isSet());
      assertEquals(1, referenceManager.queuedForEviction());

      bad.setProperty(TestNode.STRING_PROPERTY, "fixed");
      evict(referenceManager);
      assertTrue(((NodeRef<?>) bad).isCleared());
      assertEquals("fixed", bad.property(TestNode.STRING_PROPERTY));
      assertEquals(bad, good.out().next());
    }
  }

  /** A node read back from disk and not changed since is released again without writing it; before, eviction
   * skipped it and dropped it from the queue, so it stayed in memory for the rest of the run. */
  @Test
  public void unchangedNodeReadBackFromDiskIsEvictedAgain() throws Exception {
    try (Graph graph = newGraph(Config.withDefaults().withHeapPercentageThreshold(99))) {
      ReferenceManager referenceManager = referenceManager(graph);
      NodeRef<?> node = (NodeRef<?>) graph.addNode(TestNode.LABEL, TestNode.STRING_PROPERTY, "x");
      evict(referenceManager);
      assertTrue(node.isCleared());

      assertEquals("x", node.property(TestNode.STRING_PROPERTY)); // reads it back
      assertTrue(node.isSet());
      assertFalse(node.get().isDirty());
      assertEquals(1, referenceManager.queuedForEviction());

      evict(referenceManager);
      assertTrue("an unchanged node should be released again", node.isCleared());
      assertEquals("x", node.property(TestNode.STRING_PROPERTY));
    }
  }

  /** A node changed after it was read back is written again, not released with its change lost. */
  @Test
  public void changedNodeReadBackFromDiskIsWrittenAgain() throws Exception {
    try (Graph graph = newGraph(Config.withDefaults().withHeapPercentageThreshold(99))) {
      ReferenceManager referenceManager = referenceManager(graph);
      NodeRef<?> node = (NodeRef<?>) graph.addNode(TestNode.LABEL, TestNode.STRING_PROPERTY, "x");
      evict(referenceManager);
      node.setProperty(TestNode.STRING_PROPERTY, "y");
      evict(referenceManager);
      assertTrue(node.isCleared());
      assertEquals("y", node.property(TestNode.STRING_PROPERTY));
    }
  }

  /** At close every node must reach storage: a node that cannot be written makes close fail loudly. */
  @Test
  public void closeFailsForANodeThatCannotBeWritten() throws Exception {
    Path file = Files.createTempDirectory("odb-eviction").resolve("graph.bin");
    Graph graph = newGraph(Config.withDefaults().withStorageLocation(file));
    graph.addNode(TestNode.LABEL, TestNode.STRING_PROPERTY, POISON);
    try {
      graph.close();
      fail("close should report the node it could not write");
    } catch (RuntimeException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("could not be serialized"));
    }
    assertTrue(graph.isClosed());
  }
}
