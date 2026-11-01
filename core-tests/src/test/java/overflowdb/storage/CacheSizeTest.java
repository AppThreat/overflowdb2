package overflowdb.storage;

import org.junit.Test;

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
}
