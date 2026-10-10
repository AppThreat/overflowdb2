package overflowdb;

import org.junit.Test;
import overflowdb.storage.NodesWriter;
import overflowdb.testdomains.gratefuldead.FollowedBy;
import overflowdb.testdomains.gratefuldead.GratefulDead;
import overflowdb.testdomains.gratefuldead.Song;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertNull;

/**
 * A node read back from disk by one thread is never visible to another without all of its properties and edges, as
 * happens when references are cleared under heap pressure while passes traverse the graph in parallel.
 */
public class NodeRefConcurrencyTest {

  private static final int COUNT = 20_000;
  private static final int READERS = 3;

  @Test
  public void nodesLoadedFromDiskAreSeenComplete() throws Exception {
    final File storageFile = Files.createTempFile("overflowdb", "bin").toFile();
    storageFile.deleteOnExit();
    try (Graph graph = GratefulDead.newGraph(Config.withoutOverflow().withStorageLocation(storageFile.getAbsolutePath()))) {
      final List<NodeRef<?>> refs = new ArrayList<>(COUNT);
      Node previous = null;
      for (int i = 0; i < COUNT; i++) {
        final Node song = graph.addNode(Song.label, Song.NAME, "Song " + i);
        if (previous != null) previous.addEdge(FollowedBy.LABEL, song, FollowedBy.WEIGHT, i);
        refs.add((NodeRef<?>) song);
        previous = song;
      }
      new NodesWriter(graph.nodeSerializer, graph.getStorage()).writeAndClearBatched(refs.spliterator(), refs.size());

      final AtomicBoolean running = new AtomicBoolean(true);
      final AtomicReference<Throwable> failure = new AtomicReference<>();
      final List<Thread> threads = new ArrayList<>();
      // every node is on disk and unchanged, so clearing its reference is what ReferenceManager would do
      threads.add(new Thread(() -> {
        while (running.get()) {
          for (NodeRef<?> ref : refs) {
            ref.getOption().filter(node -> !node.isDirty()).ifPresent(node -> NodeRef.clear(ref));
          }
        }
      }));
      for (int r = 0; r < READERS; r++) {
        threads.add(new Thread(() -> {
          try {
            while (running.get() && failure.get() == null) {
              for (int i = 0; i < COUNT; i++) assertComplete(refs.get(i), i);
            }
          } catch (Throwable t) {
            failure.compareAndSet(null, t);
          }
        }));
      }
      threads.forEach(Thread::start);
      final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      while (System.nanoTime() < deadline && failure.get() == null) Thread.sleep(20);
      running.set(false);
      for (Thread thread : threads) thread.join();
      assertNull(failure.get());
    }
  }

  private static void assertComplete(Node song, int i) {
    final Object name = song.property(Song.NAME);
    if (!("Song " + i).equals(name)) throw new AssertionError("node " + song.id() + " has name " + name);
    final int out = count(song.out(FollowedBy.LABEL));
    final int in = count(song.in(FollowedBy.LABEL));
    if (out != (i < COUNT - 1 ? 1 : 0) || in != (i > 0 ? 1 : 0))
      throw new AssertionError("node " + song.id() + " has " + out + " out and " + in + " in edges");
  }

  private static int count(Iterator<?> iterator) {
    int count = 0;
    for (; iterator.hasNext(); iterator.next()) count++;
    return count;
  }
}
