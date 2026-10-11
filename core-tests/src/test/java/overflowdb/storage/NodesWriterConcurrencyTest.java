package overflowdb.storage;

import org.junit.Test;
import overflowdb.Config;
import overflowdb.Graph;
import overflowdb.Node;
import overflowdb.NodeRef;
import overflowdb.testdomains.gratefuldead.FollowedBy;
import overflowdb.testdomains.gratefuldead.GratefulDead;
import overflowdb.testdomains.gratefuldead.Song;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Nodes stay readable, with their latest state, while ReferenceManager writes them to disk and clears their references
 * on another thread.
 */
public class NodesWriterConcurrencyTest {

  private static final int COUNT = NodesWriter.BATCH_SIZE * 4 + 17;

  @Test
  public void newNodesStayReadableWhileWritten() throws Exception {
    try (Graph graph = openGraph()) {
      final List<NodeRef<?>> refs = addSongs(graph, "new");
      assertNull(readAllWhileWriting(graph, refs, "new"));
      assertNames(refs, "new");
    }
  }

  @Test
  public void changedNodesKeepTheirLatestStateWhileWritten() throws Exception {
    try (Graph graph = openGraph()) {
      final List<NodeRef<?>> refs = addSongs(graph, "old");
      // put every node on disk once, then change them all in memory
      new NodesWriter(graph.nodeSerializer, graph.getStorage()).writeAndClearBatched(refs.spliterator(), refs.size());
      for (NodeRef<?> ref : refs) ref.setProperty(Song.NAME, "new");

      assertNull(readAllWhileWriting(graph, refs, "new"));
      assertNames(refs, "new");
    }
  }

  @Test
  public void changesMadeWhileNodesAreClearedAreKept() throws Exception {
    final int rounds = 5;
    try (Graph graph = openGraph()) {
      final List<NodeRef<?>> refs = addSongs(graph, "0");
      final NodesWriter writer = new NodesWriter(graph.nodeSerializer, graph.getStorage());
      final AtomicBoolean changing = new AtomicBoolean(true);
      final AtomicReference<Throwable> failure = new AtomicReference<>();
      final Thread clearer = new Thread(() -> {
        try {
          while (changing.get()) writer.writeAndClearBatched(refs.spliterator(), refs.size());
        } catch (Throwable t) {
          failure.compareAndSet(null, t);
        }
      });
      clearer.start();
      try {
        for (int round = 1; round <= rounds; round++) {
          for (NodeRef<?> ref : refs) ref.setProperty(Song.NAME, String.valueOf(round));
        }
      } finally {
        changing.set(false);
        clearer.join();
      }
      assertNull(failure.get());
      assertNames(refs, String.valueOf(rounds));

      // and the last change is what storage holds
      writer.writeAndClearBatched(refs.spliterator(), refs.size());
      for (NodeRef<?> ref : refs) NodeRef.clear(ref);
      assertNames(refs, String.valueOf(rounds));
    }
  }

  @Test
  public void edgesAddedWhileNodesAreClearedAreKept() throws Exception {
    final int rounds = 6;
    try (Graph graph = openGraph()) {
      final List<NodeRef<?>> refs = addSongs(graph, "song");
      final NodesWriter writer = new NodesWriter(graph.nodeSerializer, graph.getStorage());
      final AtomicBoolean changing = new AtomicBoolean(true);
      final AtomicReference<Throwable> failure = new AtomicReference<>();
      final Thread clearer = new Thread(() -> {
        try {
          while (changing.get()) writer.writeAndClearBatched(refs.spliterator(), refs.size());
        } catch (Throwable t) {
          failure.compareAndSet(null, t);
        }
      });
      clearer.start();
      try {
        for (int round = 1; round <= rounds; round++) {
          for (int i = 0; i < refs.size(); i++) {
            refs.get(i).addEdge(FollowedBy.LABEL, refs.get((i + round) % refs.size()), FollowedBy.WEIGHT, round);
          }
        }
      } finally {
        changing.set(false);
        clearer.join();
      }
      assertNull(failure.get());
      assertEdgeCounts(refs, rounds);

      writer.writeAndClearBatched(refs.spliterator(), refs.size());
      for (NodeRef<?> ref : refs) NodeRef.clear(ref);
      assertEdgeCounts(refs, rounds);
    }
  }

  private static void assertEdgeCounts(List<NodeRef<?>> refs, int expected) {
    for (NodeRef<?> ref : refs) {
      int out = 0, in = 0;
      for (java.util.Iterator<Node> it = ref.out(FollowedBy.LABEL); it.hasNext(); it.next()) out++;
      for (java.util.Iterator<Node> it = ref.in(FollowedBy.LABEL); it.hasNext(); it.next()) in++;
      if (out != expected || in != expected)
        throw new AssertionError("node " + ref.id() + " has " + out + " out and " + in + " in edges");
    }
  }

  /** Reads every node over and over until the writer is done; returns the first failure, if any. */
  private static Throwable readAllWhileWriting(Graph graph, List<NodeRef<?>> refs, String expectedName)
      throws InterruptedException {
    final AtomicBoolean writing = new AtomicBoolean(true);
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    final Thread reader = new Thread(() -> {
      try {
        while (writing.get() && failure.get() == null) {
          for (NodeRef<?> ref : refs) {
            final Object name = ref.property(Song.NAME);
            if (!expectedName.equals(name)) throw new AssertionError("node " + ref.id() + " read as " + name);
          }
        }
      } catch (Throwable t) {
        failure.compareAndSet(null, t);
      }
    });
    reader.start();
    try {
      new NodesWriter(graph.nodeSerializer, graph.getStorage()).writeAndClearBatched(refs.spliterator(), refs.size());
    } finally {
      writing.set(false);
      reader.join();
    }
    return failure.get();
  }

  private static List<NodeRef<?>> addSongs(Graph graph, String name) {
    final List<NodeRef<?>> refs = new ArrayList<>(COUNT);
    for (int i = 0; i < COUNT; i++) {
      final Node node = graph.addNode(Song.label, Song.NAME, name);
      refs.add((NodeRef<?>) node);
    }
    return refs;
  }

  private static void assertNames(List<NodeRef<?>> refs, String expectedName) {
    for (NodeRef<?> ref : refs) assertEquals(expectedName, ref.property(Song.NAME));
  }

  private static Graph openGraph() throws IOException {
    final File storageFile = Files.createTempFile("overflowdb", "bin").toFile();
    storageFile.deleteOnExit();
    return GratefulDead.newGraph(Config.withoutOverflow().withStorageLocation(storageFile.getAbsolutePath()));
  }
}
