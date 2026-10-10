package overflowdb.storage;

import org.junit.Test;
import overflowdb.Config;
import overflowdb.Graph;
import overflowdb.Node;
import overflowdb.NodeRef;
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
