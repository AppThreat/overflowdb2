package overflowdb;

import org.junit.Test;
import overflowdb.testdomains.simple.SimpleDomain;
import overflowdb.testdomains.simple.TestNode;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class IndexManagerTest {

  @Test
  public void handleOldAndNewNodes() {
    try (Graph graph = SimpleDomain.newGraph()) {
      IndexManager indexManager = graph.indexManager;
      final String testValue = "test value zero";
      Node nodeCreatedBeforeIndexExisted0 = graph.addNode(TestNode.LABEL, TestNode.STRING_PROPERTY, testValue);
      Node nodeCreatedBeforeIndexExisted1 = graph.addNode(TestNode.LABEL); // will set property after creating index
      Node nodeCreatedBeforeIndexExisted2 = graph.addNode(TestNode.LABEL); // will set property to something else

      indexManager.createNodePropertyIndex(TestNode.STRING_PROPERTY);
      nodeCreatedBeforeIndexExisted1.setProperty(TestNode.STRING_PROPERTY, testValue);
      nodeCreatedBeforeIndexExisted2.setProperty(TestNode.STRING_PROPERTY, "something else");
      Node nodeInsertedAfterIndexCreation = graph.addNode(TestNode.LABEL, TestNode.STRING_PROPERTY, testValue);

      assertTrue(indexManager.isIndexed(TestNode.STRING_PROPERTY));
      assertEqualContents(Arrays.asList(
          nodeCreatedBeforeIndexExisted0,
          nodeCreatedBeforeIndexExisted1,
          nodeInsertedAfterIndexCreation
      ), indexManager.lookup(TestNode.STRING_PROPERTY, testValue));
    }
  }

  @Test
  public void handleNodePropertyRemoval() {
    try (Graph graph = SimpleDomain.newGraph()) {
      IndexManager indexManager = graph.indexManager;
      final String testValue = "test value one";
      Node node = graph.addNode(TestNode.LABEL, TestNode.STRING_PROPERTY, testValue);
      indexManager.createNodePropertyIndex(TestNode.STRING_PROPERTY);
      assertEqualContents(Arrays.asList(node), indexManager.lookup(TestNode.STRING_PROPERTY, testValue));

      node.removeProperty(TestNode.STRING_PROPERTY);
      assertTrue(indexManager.lookup(TestNode.STRING_PROPERTY, testValue).isEmpty());
    }
  }

  @Test
  public void handleNodeRemoval() {
    try (Graph graph = SimpleDomain.newGraph()) {
      IndexManager indexManager = graph.indexManager;
      final String testValue = "test value one";
      Node node = graph.addNode(TestNode.LABEL, TestNode.STRING_PROPERTY, testValue);
      indexManager.createNodePropertyIndex(TestNode.STRING_PROPERTY);
      assertEqualContents(Arrays.asList(node), indexManager.lookup(TestNode.STRING_PROPERTY, testValue));

      node.remove();
      assertTrue(indexManager.lookup(TestNode.STRING_PROPERTY, testValue).isEmpty());
    }
  }

  /** The index only came into being once a node with the property existed, so an index created on an empty graph
   * ignored every node added later. */
  @Test
  public void indexCreatedOnAnEmptyGraph() {
    try (Graph graph = SimpleDomain.newGraph()) {
      IndexManager indexManager = graph.indexManager;
      indexManager.createNodePropertyIndex(TestNode.STRING_PROPERTY);
      assertTrue(indexManager.isIndexed(TestNode.STRING_PROPERTY));
      assertEquals(new HashSet<>(Arrays.asList(TestNode.STRING_PROPERTY)), indexManager.getIndexedNodeProperties());

      Node a = graph.addNode(TestNode.LABEL, TestNode.STRING_PROPERTY, "a");
      Node b = graph.addNode(TestNode.LABEL);
      b.setProperty(TestNode.STRING_PROPERTY, "a");
      assertEqualContents(Arrays.asList(a, b), indexManager.lookup(TestNode.STRING_PROPERTY, "a"));
      assertEquals(2, indexManager.getIndexedNodeCount(TestNode.STRING_PROPERTY));
    }
  }

  /** An index created before any node has the property is stored and found again when the graph is reopened. */
  @Test
  public void emptyIndexIsStoredAndRestored() throws Exception {
    java.nio.file.Path file = java.nio.file.Files.createTempDirectory("odb-index").resolve("graph.bin");
    try (Graph graph = SimpleDomain.newGraph(Config.withoutOverflow().withStorageLocation(file))) {
      graph.indexManager.createNodePropertyIndex(TestNode.STRING_PROPERTY);
      graph.addNode(TestNode.LABEL, TestNode.STRING_PROPERTY, "a");
    }
    try (Graph graph = SimpleDomain.newGraph(Config.withoutOverflow().withStorageLocation(file))) {
      assertTrue(graph.indexManager.isIndexed(TestNode.STRING_PROPERTY));
      assertEquals(1, graph.indexManager.lookup(TestNode.STRING_PROPERTY, "a").size());
      Node b = graph.addNode(TestNode.LABEL, TestNode.STRING_PROPERTY, "b");
      assertEqualContents(Arrays.asList(b), graph.indexManager.lookup(TestNode.STRING_PROPERTY, "b"));
    }
  }

  /** A node whose indexed property changes is found by its new value only. */
  @Test
  public void changedValueMovesTheNode() {
    try (Graph graph = SimpleDomain.newGraph()) {
      IndexManager indexManager = graph.indexManager;
      indexManager.createNodePropertyIndex(TestNode.STRING_PROPERTY);
      Node node = graph.addNode(TestNode.LABEL, TestNode.STRING_PROPERTY, "old");
      Node other = graph.addNode(TestNode.LABEL, TestNode.STRING_PROPERTY, "old");
      node.setProperty(TestNode.STRING_PROPERTY, "new");
      assertEqualContents(Arrays.asList(other), indexManager.lookup(TestNode.STRING_PROPERTY, "old"));
      assertEqualContents(Arrays.asList(node), indexManager.lookup(TestNode.STRING_PROPERTY, "new"));
      node.setProperty(TestNode.STRING_PROPERTY, "new");
      assertEqualContents(Arrays.asList(node), indexManager.lookup(TestNode.STRING_PROPERTY, "new"));
      assertEquals(2, indexManager.getIndexedNodeCount(TestNode.STRING_PROPERTY));
    }
  }

  /** Setting an indexed property to null used to throw from the index's ConcurrentHashMap. */
  @Test
  public void nullValueIsNotIndexed() {
    try (Graph graph = SimpleDomain.newGraph()) {
      IndexManager indexManager = graph.indexManager;
      indexManager.createNodePropertyIndex(TestNode.STRING_PROPERTY);
      Node node = graph.addNode(TestNode.LABEL, TestNode.STRING_PROPERTY, "a");
      node.setProperty(TestNode.STRING_PROPERTY, null);
      assertTrue(indexManager.lookup(TestNode.STRING_PROPERTY, "a").isEmpty());
      assertEquals(0, indexManager.getIndexedNodeCount(TestNode.STRING_PROPERTY));
    }
  }

  /** Nodes added to and removed from one value from many threads at once: none that remains is lost. */
  @Test
  public void concurrentAddsAndRemovalsOfOneValue() throws Exception {
    try (Graph graph = SimpleDomain.newGraph()) {
      IndexManager indexManager = graph.indexManager;
      indexManager.createNodePropertyIndex(TestNode.STRING_PROPERTY);
      final int threads = 8;
      final int perThread = 5_000;
      final List<List<Node>> kept = new java.util.ArrayList<>();
      for (int t = 0; t < threads; t++) kept.add(java.util.Collections.synchronizedList(new java.util.ArrayList<>()));
      final java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
      final List<Thread> workers = new java.util.ArrayList<>();
      for (int t = 0; t < threads; t++) {
        final List<Node> mine = kept.get(t);
        workers.add(new Thread(() -> {
          try {
            start.await();
          } catch (InterruptedException e) {
            throw new RuntimeException(e);
          }
          for (int i = 0; i < perThread; i++) {
            Node node = graph.addNode(TestNode.LABEL, TestNode.STRING_PROPERTY, "shared");
            // every other node leaves again at once, often emptying the value's set
            if (i % 2 == 0) node.setProperty(TestNode.STRING_PROPERTY, "elsewhere");
            else mine.add(node);
          }
        }));
      }
      workers.forEach(Thread::start);
      start.countDown();
      for (Thread worker : workers) worker.join();
      Set<NodeRef<?>> found = new HashSet<>(indexManager.lookup(TestNode.STRING_PROPERTY, "shared"));
      int expected = 0;
      for (List<Node> mine : kept) {
        expected += mine.size();
        for (Node node : mine) assertTrue("lost from the index: " + node, found.contains(node));
      }
      assertEquals(expected, found.size());
    }
  }

  /** Threads setting one node's indexed property at once leave it under its final value only. */
  @Test
  public void concurrentChangesOfOneNodeLeaveItUnderOneValue() throws Exception {
    try (Graph graph = SimpleDomain.newGraph()) {
      IndexManager indexManager = graph.indexManager;
      indexManager.createNodePropertyIndex(TestNode.STRING_PROPERTY);
      final List<Node> nodes = new java.util.ArrayList<>();
      for (int i = 0; i < 200; i++) nodes.add(graph.addNode(TestNode.LABEL, TestNode.STRING_PROPERTY, "v0"));
      final int threads = 8;
      final java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
      final List<Thread> workers = new java.util.ArrayList<>();
      for (int t = 0; t < threads; t++) {
        final String value = "v" + (t + 1);
        workers.add(new Thread(() -> {
          try {
            start.await();
          } catch (InterruptedException e) {
            throw new RuntimeException(e);
          }
          for (int round = 0; round < 200; round++) {
            for (Node node : nodes) node.setProperty(TestNode.STRING_PROPERTY, value);
          }
        }));
      }
      workers.forEach(Thread::start);
      start.countDown();
      for (Thread worker : workers) worker.join();
      assertEquals(nodes.size(), indexManager.getIndexedNodeCount(TestNode.STRING_PROPERTY));
      for (Node node : nodes) {
        Object value = node.property(TestNode.STRING_PROPERTY);
        for (int v = 0; v <= threads; v++) {
          boolean indexed = indexManager.lookup(TestNode.STRING_PROPERTY, "v" + v).contains(node);
          assertEquals(node + " under v" + v + ", value " + value, ("v" + v).equals(value), indexed);
        }
      }
    }
  }

  private void assertEqualContents(List expected, List actual) {
    assertEquals(expected.size(), actual.size());

    Set actualSet = new HashSet<>();
    actualSet.addAll(actual);
    assertTrue(actualSet.containsAll(expected));
  }

}
