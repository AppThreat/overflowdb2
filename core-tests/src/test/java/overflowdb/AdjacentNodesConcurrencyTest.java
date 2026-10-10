package overflowdb;

import org.junit.Test;
import overflowdb.testdomains.gratefuldead.Artist;
import overflowdb.testdomains.gratefuldead.FollowedBy;
import overflowdb.testdomains.gratefuldead.GratefulDead;
import overflowdb.testdomains.gratefuldead.Song;
import overflowdb.testdomains.gratefuldead.SungBy;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Traversals do not lock a node, so they must only ever see edges of the label they ask for, while another thread adds
 * edges and the node's adjacency array grows.
 */
public class AdjacentNodesConcurrencyTest {

  private static final int EDGES = 2_000;
  private static final int READERS = 3;

  @Test
  public void readersSeeOnlyTheirLabelWhileEdgesAreAdded() throws Exception {
    try (Graph graph = GratefulDead.newGraph()) {
      final Node song = graph.addNode(Song.label, Song.NAME, "song");
      final List<Node> songs = new ArrayList<>(EDGES);
      final List<Node> artists = new ArrayList<>(EDGES);
      for (int i = 0; i < EDGES; i++) {
        songs.add(graph.addNode(Song.label, Song.NAME, "next " + i));
        artists.add(graph.addNode(Artist.label, Artist.NAME, "artist " + i));
      }

      final AtomicBoolean adding = new AtomicBoolean(true);
      final AtomicReference<Throwable> failure = new AtomicReference<>();
      final List<Thread> readers = new ArrayList<>();
      for (int r = 0; r < READERS; r++) {
        readers.add(new Thread(() -> {
          try {
            while (adding.get() && failure.get() == null) {
              expectOnly(song.out(FollowedBy.LABEL), Song.label);
              expectOnly(song.out(SungBy.LABEL), Artist.label);
            }
          } catch (Throwable t) {
            failure.compareAndSet(null, t);
          }
        }));
      }
      readers.forEach(Thread::start);
      try {
        // alternate the labels, so that growing one block moves the start of the next
        for (int i = 0; i < EDGES && failure.get() == null; i++) {
          song.addEdge(FollowedBy.LABEL, songs.get(i));
          song.addEdge(SungBy.LABEL, artists.get(i));
        }
      } finally {
        adding.set(false);
        for (Thread reader : readers) reader.join();
      }
      assertNull(failure.get());
      assertEquals(EDGES, count(song.out(FollowedBy.LABEL)));
      assertEquals(EDGES, count(song.out(SungBy.LABEL)));
    }
  }

  private static void expectOnly(Iterator<Node> nodes, String label) {
    while (nodes.hasNext()) {
      final Node node = nodes.next();
      if (!label.equals(node.label())) throw new AssertionError("expected only " + label + " nodes, got " + node.label());
    }
  }

  private static int count(Iterator<?> iterator) {
    int count = 0;
    for (; iterator.hasNext(); iterator.next()) count++;
    return count;
  }
}
