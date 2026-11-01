package overflowdb;

import org.junit.Test;
import overflowdb.storage.NodesWriter;
import overflowdb.testdomains.simple.SimpleDomain;
import overflowdb.testdomains.simple.TestEdge;
import overflowdb.testdomains.simple.TestNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.junit.Assert.*;

/** Removing edges and nodes, and iterating what is left. */
public class RemovalTest {

  private static int count(Iterator<?> it) {
    int c = 0;
    while (it.hasNext()) {
      assertNotNull(it.next());
      c++;
    }
    return c;
  }

  private static List<Edge> edges(Iterator<Edge> it) {
    List<Edge> result = new ArrayList<>();
    it.forEachRemaining(result::add);
    return result;
  }

  /** Runs `body` on a thread with a small stack, where recursion per removed edge overflows quickly. */
  private static void onSmallStack(Runnable body) throws InterruptedException {
    Throwable[] failure = new Throwable[1];
    Thread thread = new Thread(null, () -> {
      try {
        body.run();
      } catch (Throwable t) {
        failure[0] = t;
      }
    }, "small-stack", 128 * 1024);
    thread.start();
    thread.join();
    if (failure[0] instanceof Error error) throw error;
    if (failure[0] instanceof RuntimeException e) throw e;
  }

  /** Iteration skipped each hole left by a removed edge with one recursive call, and overflowed the stack. */
  @Test
  public void iteratesPastManyRemovedEdges() throws Exception {
    try (Graph graph = SimpleDomain.newGraph()) {
      Node hub = graph.addNode(TestNode.LABEL);
      int n = 20_000;
      for (int i = 0; i < n; i++) {
        Node other = graph.addNode(TestNode.LABEL);
        hub.addEdge(TestEdge.LABEL, other);
        other.addEdge(TestEdge.LABEL, hub);
      }
      List<Edge> out = edges(hub.outE(TestEdge.LABEL));
      List<Edge> in = edges(hub.inE(TestEdge.LABEL));
      // keep the last out-edge and the first and last in-edge, so holes come before, between and after them
      for (int i = 0; i < n - 1; i++) out.get(i).remove();
      for (int i = 1; i < n - 1; i++) in.get(i).remove();

      onSmallStack(() -> {
        assertEquals(1, count(hub.out(TestEdge.LABEL)));
        assertEquals(1, count(hub.out()));
        assertEquals(2, count(hub.in(TestEdge.LABEL)));
        assertEquals(2, count(hub.in()));
        assertEquals(3, count(hub.both()));
        assertEquals(1, count(hub.outE()));
        assertEquals(2, count(hub.inE()));
        assertTrue(hub.hasOut(TestEdge.LABEL));
      });
      assertEquals(3, graph.edgeCount());
    }
  }

  @Test
  public void iteratesANodeWhoseEdgesAreAllRemoved() throws Exception {
    try (Graph graph = SimpleDomain.newGraph()) {
      Node hub = graph.addNode(TestNode.LABEL);
      for (int i = 0; i < 20_000; i++) hub.addEdge(TestEdge.LABEL, graph.addNode(TestNode.LABEL));
      edges(hub.outE(TestEdge.LABEL)).forEach(Edge::remove);
      onSmallStack(() -> {
        Iterator<Node> it = hub.out(TestEdge.LABEL);
        assertFalse(it.hasNext());
        assertFalse(it.hasNext());
      });
      assertFalse(hub.hasOut(TestEdge.LABEL));
      assertEquals(0, graph.edgeCount());
    }
  }

  @Test
  public void removedEdgeKnowsItIsRemoved() {
    try (Graph graph = SimpleDomain.newGraph()) {
      Node a = graph.addNode(TestNode.LABEL);
      Node b = graph.addNode(TestNode.LABEL);
      Edge edge = a.addEdge(TestEdge.LABEL, b);
      assertFalse(edge.isRemoved());
      edge.remove();
      assertTrue(edge.isRemoved());
      assertFalse(a.out().hasNext());
      assertFalse(b.in().hasNext());
    }
  }

  /** bothE() returns a self-loop once as an out-edge and once as an in-edge; removing the node removed it twice and
   * failed with "unable to calculate occurrenceCount". */
  @Test
  public void removesANodeWithASelfLoop() {
    try (Graph graph = SimpleDomain.newGraph()) {
      Node a = graph.addNode(TestNode.LABEL);
      Node b = graph.addNode(TestNode.LABEL);
      a.addEdge(TestEdge.LABEL, a);
      a.addEdge(TestEdge.LABEL, b);
      b.addEdge(TestEdge.LABEL, a);

      a.remove();

      assertEquals(1, graph.nodeCount());
      assertEquals(0, graph.edgeCount());
      assertFalse(b.both().hasNext());
    }
  }

  @Test
  public void removesANodeWithSeveralSelfLoopsAndParallelEdges() {
    try (Graph graph = SimpleDomain.newGraph()) {
      Node a = graph.addNode(TestNode.LABEL);
      Node b = graph.addNode(TestNode.LABEL);
      Node c = graph.addNode(TestNode.LABEL);
      a.addEdge(TestEdge.LABEL, b);
      a.addEdge(TestEdge.LABEL, a, TestEdge.LONG_PROPERTY, 1L);
      a.addEdge(TestEdge.LABEL, b);
      a.addEdge(TestEdge.LABEL, a, TestEdge.LONG_PROPERTY, 2L);
      b.addEdge(TestEdge.LABEL, a);
      a.addEdge(TestEdge.LABEL, a);
      b.addEdge(TestEdge.LABEL, c);

      a.remove();

      assertEquals(2, graph.nodeCount());
      assertEquals(1, graph.edgeCount());
      assertEquals(c, b.out().next());
      assertFalse(b.in().hasNext());
    }
  }

  /** Removing one of two self-loops keeps the other intact, with its property, on both of its sides. */
  @Test
  public void removesOneOfTwoSelfLoops() {
    try (Graph graph = SimpleDomain.newGraph()) {
      Node a = graph.addNode(TestNode.LABEL);
      a.addEdge(TestEdge.LABEL, a, TestEdge.LONG_PROPERTY, 1L);
      a.addEdge(TestEdge.LABEL, a, TestEdge.LONG_PROPERTY, 2L);
      edges(a.inE(TestEdge.LABEL)).get(0).remove();

      List<Edge> out = edges(a.outE(TestEdge.LABEL));
      List<Edge> in = edges(a.inE(TestEdge.LABEL));
      assertEquals(1, out.size());
      assertEquals(1, in.size());
      assertEquals(2L, out.get(0).property(TestEdge.LONG_PROPERTY));
      assertEquals(2L, in.get(0).property(TestEdge.LONG_PROPERTY));
      a.remove();
      assertEquals(0, graph.nodeCount());
    }
  }

  /** A node read back from storage holds the NodeRefs of its neighbours as found by id, which may be other instances
   * than the ones it was created with. */
  @Test
  public void removesASelfLoopNodeReadBackFromStorage() {
    try (Graph graph = SimpleDomain.newGraph(Config.withDefaults())) {
      Node a = graph.addNode(TestNode.LABEL);
      Node b = graph.addNode(TestNode.LABEL);
      a.addEdge(TestEdge.LABEL, a);
      a.addEdge(TestEdge.LABEL, b);
      b.addEdge(TestEdge.LABEL, a);
      new NodesWriter(graph.nodeSerializer, graph.getStorage())
          .writeAndClearBatched(List.of(a, b).spliterator(), 2);
      assertTrue(((NodeRef<?>) a).isCleared());

      a.remove();

      assertEquals(1, graph.nodeCount());
      assertEquals(0, graph.edgeCount());
    }
  }
}
