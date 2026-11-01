package overflowdb.storage;

import org.junit.Test;
import overflowdb.Config;
import overflowdb.Graph;
import overflowdb.testdomains.simple.SimpleDomain;

import static org.junit.Assert.assertEquals;

/** The MVStore page cache is on the heap: it is capped at a tenth of it. */
public class CacheSizeTest {
  private static final long MB = 1L << 20;

  @Test
  public void cacheIsCappedAtATenthOfTheHeap() {
    assertEquals(25, OdbStorage.effectiveCacheSizeMB(256, 256 * MB));
    assertEquals(256, OdbStorage.effectiveCacheSizeMB(256, 4096 * MB));
    assertEquals(64, OdbStorage.effectiveCacheSizeMB(64, 4096 * MB));
    assertEquals(1, OdbStorage.effectiveCacheSizeMB(256, 4 * MB));
    assertEquals(256, OdbStorage.effectiveCacheSizeMB(256, Long.MAX_VALUE));
  }

  /** The store uses an explicit size as given, and the default capped by this JVM's heap. Before, an explicit size
   * was capped too: Config.withDefaults() set the default the same way. */
  @Test
  public void explicitSizeIsUsedAsGivenAndTheDefaultIsCapped() {
    final int heapMB = (int) (Runtime.getRuntime().maxMemory() >> 20);
    final int large = heapMB; // more than a tenth of the heap
    try (Graph graph = SimpleDomain.newGraph(Config.withDefaults().withCacheSize(large))) {
      assertEquals(large, graph.getStorage().getNodesMVMap().getStore().getCacheSize());
    }
    try (Graph graph = SimpleDomain.newGraph(Config.withDefaults())) {
      assertEquals(OdbStorage.effectiveCacheSizeMB(256, Runtime.getRuntime().maxMemory()),
          graph.getStorage().getNodesMVMap().getStore().getCacheSize());
    }
  }
}
