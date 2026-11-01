package overflowdb.util;

import org.junit.Test;
import overflowdb.NodeRef;
import overflowdb.Config;
import overflowdb.Graph;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import overflowdb.Node;
import java.util.Vector;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class NodesListTest {

  @Test
  public void addElements() {
    NodesList nl = new NodesList();

    NodeRef ref1 = createDummyRef(1L, "A");
    NodeRef ref2 = createDummyRef(2L, "A");
    NodeRef ref3 = createDummyRef(3L, "B");
    nl.add(ref1);
    nl.add(ref2);
    nl.add(ref3);

    assertEquals(3, nl.size());
    assertEquals(2, nl.nodesByLabel("A").size());
    assertEquals(1, nl.nodesByLabel("B").size());
    assertEquals(ref1, nl.nodeById(1L));
    assertEquals(ref2, nl.nodeById(2L));
    assertEquals(ref3, nl.nodeById(3L));
    assertTrue(nl.nodesByLabel("A").contains(ref1));
    assertTrue(nl.nodesByLabel("A").contains(ref2));
    assertTrue(nl.nodesByLabel("B").contains(ref3));
  }

  @Test
  public void growsAboveInitialCapacity() {
    NodesList nl = new NodesList(2);

    NodeRef ref1 = createDummyRef(1L, "A");
    NodeRef ref2 = createDummyRef(2L, "A");
    NodeRef ref3 = createDummyRef(3L, "B");
    nl.add(ref1);
    nl.add(ref2);
    nl.add(ref3);

    assertEquals(3, nl.size());
    assertEquals(2, nl.nodesByLabel("A").size());
    assertEquals(1, nl.nodesByLabel("B").size());
    assertEquals(ref1, nl.nodeById(1L));
    assertEquals(ref2, nl.nodeById(2L));
    assertEquals(ref3, nl.nodeById(3L));
    assertTrue(nl.nodesByLabel("A").contains(ref1));
    assertTrue(nl.nodesByLabel("A").contains(ref2));
    assertTrue(nl.nodesByLabel("B").contains(ref3));
  }

  @Test
  public void growsAboveInitialCapacity2() {
    NodesList nl = new NodesList(2);

    for (int i = 0; i< 50000; i++) {
      nl.add(createDummyRef(i, "A" + i));
    }

    assertEquals(50000, nl.size());
  }

  @Test
  public void removeNode() {
    NodesList nl = new NodesList();

    NodeRef ref1 = createDummyRef(1L, "A");
    NodeRef ref2 = createDummyRef(2L, "A");
    nl.add(ref1);
    nl.add(ref2);

    nl.remove(ref1);
    assertEquals(1, nl.size());
    assertEquals(1, nl.nodesByLabel("A").size());
    assertTrue(nl.nodesByLabel("A").contains(ref2));
    assertEquals(ref2, nl.nodeById(2L));
    assertNull(nl.nodeById(1L));
  }

  @Test
  public void removeNodeThenAddMoreNodes() {
    NodesList nl = new NodesList();

    NodeRef ref1 = createDummyRef(1L, "A");
    NodeRef ref2 = createDummyRef(2L, "B");
    nl.add(ref1);
    nl.add(ref2);

    nl.remove(ref1);
    NodeRef ref3 = createDummyRef(3L, "A");
    nl.add(ref3);

    assertEquals(2, nl.size());
    assertEquals(1, nl.nodesByLabel("A").size());
    assertTrue(nl.nodesByLabel("A").contains(ref3));
    assertTrue(nl.nodesByLabel("B").contains(ref2));
    assertNull(nl.nodeById(1L));
    assertEquals(ref2, nl.nodeById(2L));
    assertEquals(ref3, nl.nodeById(3L));
  }

  @Test(expected = AssertionError.class)
  public void idsAreUnique() {
    NodesList nl = new NodesList();
    nl.add(createDummyRef(1L, "A"));
    nl.add(createDummyRef(1L, "B"));
  }

  @Test
  public void compact() {
    NodesList nl = new NodesList(10);

    // insert two nodes we'll check on later, and lot's of dummy nodes that we only insert to take up some space
    NodeRef ref1 = createDummyRef(500000L, "A");
    nl.add(ref1);
    Vector<NodeRef> bulkRefs = new Vector<>(20000);
    for (int i = 0; i < 10000; i++) {
      NodeRef dummyRef = createDummyRef(i, "A");
      nl.add(dummyRef);
      bulkRefs.add(dummyRef);
    }
    NodeRef ref2 = createDummyRef(500001L, "B");
    nl.add(ref2);
    for (int i = 10000; i < 20000; i++) {
      NodeRef dummyRef = createDummyRef(i, "B");
      nl.add(dummyRef);
      bulkRefs.add(dummyRef);
    }
    assertTrue(
        "internal element array should be large enough to hold all nodes (>= 20k), but has size " + nl._elementDataSize(),
        nl._elementDataSize() >= 20000);

    // delete all the bulk dummy nodes, then compact/trim and verify that remaining elements are still ok
    bulkRefs.forEach(it -> nl.remove(it));
    nl.compact();
    assertTrue(
        "internal element array should have been compacted (< 100), but has size " + nl._elementDataSize(),
        nl._elementDataSize() < 100);

    assertEquals(2, nl.size());
    assertEquals(1, nl.nodesByLabel("A").size());
    assertEquals(1, nl.nodesByLabel("B").size());
    assertTrue(nl.nodesByLabel("A").contains(ref1));
    assertTrue(nl.nodesByLabel("B").contains(ref2));
    assertEquals(ref1, nl.nodeById(ref1.id()));
    assertEquals(ref2, nl.nodeById(ref2.id()));
  }

  @Test
  public void compactsAutomatically() {
    NodesList nl = new NodesList();

    final int nodeCount = 200_000;
    Vector<NodeRef> bulkRefs = new Vector<>(nodeCount);
    for (int i = 0; i < nodeCount; i++) {
      NodeRef dummyRef = createDummyRef(i, "A");
      nl.add(dummyRef);
      bulkRefs.add(dummyRef);
    }
    assertEquals(nodeCount, nl.size());
    assertTrue(
        "internal element array should be large enough to hold all nodes (>= 200k), but has size " + nl._elementDataSize(),
        nl._elementDataSize() >= nodeCount);

    // delete all the bulk dummy nodes - this should (intermittently) automatically call 'collect'
    bulkRefs.forEach(it -> nl.remove(it));
    assertTrue(
        "internal element array should have been compacted (< 50k), but has size " + nl._elementDataSize(),
        nl._elementDataSize() < 50000);

    System.out.println(nl._elementDataSize());
    assertEquals(0, nl.size());
  }



  /** A node that has been added is found by id while another thread keeps adding nodes, as when a pass writer adds
   * nodes while the pass's parts traverse the graph. */
  @Test
  public void lookupsDuringConcurrentAdds() throws Exception {
    final int count = 300_000;
    final NodesList nl = new NodesList(16);
    final NodeRef[] refs = new NodeRef[count];
    for (int i = 0; i < count; i++) refs[i] = createDummyRef(i, "A");
    final AtomicInteger added = new AtomicInteger();
    final AtomicReference<String> failure = new AtomicReference<>();
    final List<Thread> readers = new ArrayList<>();
    for (int r = 0; r < 3; r++) {
      readers.add(new Thread(() -> {
        final ThreadLocalRandom random = ThreadLocalRandom.current();
        while (added.get() < count && failure.get() == null) {
          final int known = added.get();
          if (known == 0) continue;
          final int id = random.nextInt(known);
          if (nl.nodeById(id) != refs[id] || !nl.contains(id)) {
            failure.compareAndSet(null, "node " + id + " not found after " + known + " adds");
          }
        }
      }));
    }
    readers.forEach(Thread::start);
    for (int i = 0; i < count && failure.get() == null; i++) {
      nl.add(refs[i]);
      added.set(i + 1);
    }
    added.set(count);
    for (Thread t : readers) t.join();
    assertNull(failure.get());
  }

  private static List<Long> ids(Iterable<Node> nodes) {
    List<Long> ids = new ArrayList<>();
    for (Node node : nodes) ids.add(node.id());
    return ids;
  }

  /** Removal keeps the order of the remaining nodes of a label, also across the compaction of the label's array. */
  @Test
  public void removalKeepsLabelOrderAcrossCompaction() {
    NodesList nl = new NodesList();
    final int count = 20_000;
    List<Long> expected = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      nl.add(createDummyRef(i, "A"));
      if (i % 3 == 0) expected.add((long) i);
    }
    for (int i = 0; i < count; i++) {
      if (i % 3 != 0) nl.remove(nl.nodeById(i));
    }
    assertEquals(expected, ids(nl.nodesByLabel("A")));
    assertEquals(expected.size(), nl.nodesByLabel("A").size());
    assertEquals(expected.size(), nl.cardinality("A"));
    assertTrue("label array should have been compacted, has " + nl._labelDataSize("A") + " slots",
        nl._labelDataSize("A") < count / 2);

    // positions are still right after compaction: remove the rest and add more
    for (long id : expected) nl.remove(nl.nodeById(id));
    assertEquals(0, nl.nodesByLabel("A").size());
    assertFalse(nl.nodesByLabel("A").iterator().hasNext());
    assertFalse(nl.nodeLabels().contains("A"));
    nl.add(createDummyRef(count, "A"));
    assertEquals(Arrays.asList((long) count), ids(nl.nodesByLabel("A")));
  }

  /** Removal used to scan the label's list for the node and shift the rest, so removing half of a label took time
   * quadratic in its size: 4.1 s for half of 400k nodes on an M-series Mac, against 69 ms now. With 600k nodes the
   * old way takes over twice as long again. */
  @Test(timeout = 5_000)
  public void removingManyNodesOfOneLabelIsLinear() {
    NodesList nl = new NodesList();
    final int count = 600_000;
    List<NodeRef> refs = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      NodeRef ref = createDummyRef(i, "A");
      refs.add(ref);
      nl.add(ref);
    }
    // the front half: the worst case for a scan from the front plus a shift
    for (int i = 0; i < count / 2; i++) nl.remove(refs.get(i));
    assertEquals(count / 2, nl.nodesByLabel("A").size());
    assertEquals(refs.get(count / 2), nl.nodesByLabel("A").iterator().next());
  }

  @Test
  public void removingANodeTwiceOrAnUnknownNodeDoesNothing() {
    NodesList nl = new NodesList();
    NodeRef a = createDummyRef(1, "A");
    NodeRef b = createDummyRef(2, "A");
    nl.add(a);
    nl.add(b);
    nl.remove(a);
    nl.remove(a);
    nl.remove(createDummyRef(3, "A"));
    assertEquals(1, nl.size());
    assertEquals(Arrays.asList(2L), ids(nl.nodesByLabel("A")));
  }

  /** A snapshot does not change when nodes are added after it was taken. A node removed later may still be seen,
   * e.g. once adding nodes has moved the label to a larger array. */
  @Test
  public void snapshotIsUnaffectedByLaterAdds() {
    NodesList nl = new NodesList();
    for (int i = 0; i < 10; i++) nl.add(createDummyRef(i, "A"));
    NodesList.LabelNodes snapshot = nl.nodesByLabel("A");
    Iterator<Node> it = snapshot.iterator();
    it.next();
    for (int i = 10; i < 5_000; i++) nl.add(createDummyRef(i, "A"));
    nl.remove(nl.nodeById(5));
    int seen = 1;
    while (it.hasNext()) {
      assertTrue(it.next().id() < 10);
      seen++;
    }
    assertTrue("seen " + seen, seen == 9 || seen == 10);
    assertEquals(10, snapshot.size());
    assertEquals(4_999, nl.nodesByLabel("A").size());
  }

  /** Without a move to a larger array, a removal is visible to an iteration that has not reached the node yet. */
  @Test
  public void snapshotSkipsANodeRemovedBeforeItIsReached() {
    NodesList nl = new NodesList();
    for (int i = 0; i < 10; i++) nl.add(createDummyRef(i, "A"));
    Iterator<Node> it = nl.nodesByLabel("A").iterator();
    it.next();
    nl.remove(nl.nodeById(5));
    List<Long> rest = new ArrayList<>();
    it.forEachRemaining(node -> rest.add(node.id()));
    assertEquals(Arrays.asList(1L, 2L, 3L, 4L, 6L, 7L, 8L, 9L), rest);
  }

  /** Readers iterate a label while writers add and remove nodes of it: no exception, no null, no duplicate, and every
   * node that was present for the whole iteration is seen. */
  @Test
  public void iterationDuringConcurrentAddsAndRemovals() throws Exception {
    final NodesList nl = new NodesList(16);
    final int stable = 2_000;
    for (int i = 0; i < stable; i++) nl.add(createDummyRef(i, "A"));
    final AtomicReference<String> failure = new AtomicReference<>();
    final java.util.concurrent.atomic.AtomicBoolean writing = new java.util.concurrent.atomic.AtomicBoolean(true);
    final List<Thread> readers = new ArrayList<>();
    for (int r = 0; r < 3; r++) {
      readers.add(new Thread(() -> {
        while (writing.get() && failure.get() == null) {
          try {
            java.util.Set<Long> seen = new java.util.HashSet<>();
            for (Node node : nl.nodesByLabel("A")) {
              if (node == null) throw new AssertionError("null node");
              if (!seen.add(node.id())) throw new AssertionError("node " + node.id() + " seen twice");
            }
            for (long id = 0; id < stable; id++) {
              if (!seen.contains(id)) throw new AssertionError("stable node " + id + " not seen");
            }
          } catch (Throwable t) {
            failure.compareAndSet(null, t.toString());
          }
        }
      }));
    }
    readers.forEach(Thread::start);
    for (int round = 0; round < 40 && failure.get() == null; round++) {
      List<NodeRef> added = new ArrayList<>();
      for (int i = 0; i < 5_000; i++) {
        NodeRef ref = createDummyRef(stable + round * 5_000L + i, "A");
        nl.add(ref);
        added.add(ref);
      }
      added.forEach(nl::remove);
    }
    writing.set(false);
    for (Thread t : readers) t.join();
    assertNull(failure.get());
    assertEquals(stable, nl.nodesByLabel("A").size());
  }

  private NodeRef createDummyRef(long id, String label) {
    return new NodeRef(dummyGraph, id) {
      public String label() {
        return label;
      }
    };
  }

  private Graph dummyGraph = Graph.open(Config.withoutOverflow(), new ArrayList<>(), new ArrayList<>());
}
