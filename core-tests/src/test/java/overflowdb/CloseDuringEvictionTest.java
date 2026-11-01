package overflowdb;

import org.junit.Test;
import overflowdb.testdomains.simple.TestEdge;
import overflowdb.testdomains.simple.TestNode;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.Assert.*;

/**
 * Closing a graph while an eviction is in progress saves every node. Before, close() wrote only the nodes still
 * queued for eviction: the ones the eviction had taken off the queue but not written yet were in neither, and the
 * eviction's writes went to a store that was closed meanwhile. They were missing when the graph was opened again.
 */
public class CloseDuringEvictionTest {
  private static final int NODES = 20_000;
  private static final String BLOCK = "block";

  @Test(timeout = 60_000)
  public void closeWaitsForTheEvictionInProgress() throws Exception {
    final File file = Files.createTempFile("odb-close-during-eviction", ".bin").toFile();
    file.delete();
    file.deleteOnExit();
    final CountDownLatch serializing = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    // holds up serialization at one node, so that the eviction is in progress when close() is called
    final Function<Object, Object> holdUp = value -> {
      if (BLOCK.equals(value)) {
        serializing.countDown();
        try {
          release.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      return value;
    };

    final Graph graph = Graph.open(config(file), Arrays.asList(TestNode.factory), Arrays.asList(TestEdge.factory), holdUp);
    for (int i = 0; i < NODES; i++) {
      graph.addNode(i, TestNode.LABEL, TestNode.STRING_PROPERTY, i == 100 ? BLOCK : "n" + i);
    }
    referenceManager(graph).triggerAsynchronousEviction();
    assertTrue("eviction did not start", serializing.await(30, TimeUnit.SECONDS));

    final Thread closer = new Thread(graph::close);
    closer.start();
    closer.join(300); // close() now waits for the eviction, before only for itself
    release.countDown();
    closer.join(30_000);
    assertFalse("close did not finish", closer.isAlive());

    try (Graph reopened = Graph.open(config(file), Arrays.asList(TestNode.factory), Arrays.asList(TestEdge.factory))) {
      assertEquals(NODES, reopened.nodeCount());
      for (int i = 0; i < NODES; i++) {
        assertEquals("node " + i, i == 100 ? BLOCK : "n" + i, reopened.node(i).property(TestNode.STRING_PROPERTY));
      }
    }
  }

  private static Config config(File file) {
    return Config.withDefaults().withHeapPercentageThreshold(99).withStorageLocation(file.getAbsolutePath());
  }

  private static ReferenceManager referenceManager(Graph graph) throws ReflectiveOperationException {
    Field field = Graph.class.getDeclaredField("referenceManager");
    field.setAccessible(true);
    return (ReferenceManager) field.get(graph);
  }
}
