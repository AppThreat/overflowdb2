package overflowdb.util;

import org.junit.Test;
import overflowdb.NodeRef;
import overflowdb.Config;
import overflowdb.Graph;

import java.util.ArrayList;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
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

  private NodeRef createDummyRef(long id, String label) {
    return new NodeRef(dummyGraph, id) {
      public String label() {
        return label;
      }
    };
  }

  private Graph dummyGraph = Graph.open(Config.withoutOverflow(), new ArrayList<>(), new ArrayList<>());
}
