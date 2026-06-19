package overflowdb.storage;

import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;
import org.junit.Test;
import overflowdb.Node;
import overflowdb.Config;
import overflowdb.Graph;
import overflowdb.testdomains.gratefuldead.GratefulDead;
import overflowdb.testdomains.gratefuldead.Song;
import overflowdb.util.StringInterner;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class OdbStorageTest {
  private StringInterner stringInterner = new StringInterner();

  @Test
  public void persistToFileIfStorageConfigured() throws IOException {
    final File storageFile = Files.createTempFile("overflowdb", "bin").toFile();
    storageFile.deleteOnExit();
    Config config = Config.withDefaults().withStorageLocation(storageFile.getAbsolutePath());

    // open empty graph, add one node, close graph
    final long song1Id;
    try (Graph graph = GratefulDead.newGraph(config)) {
      assertEquals(0, graph.nodeCount());
      final Node song1 = graph.addNode(Song.label, Song.NAME, "Song 1");
      song1Id = song1.id();
      assertEquals(1, graph.nodeCount());
    } // ARM auto-close will trigger saving to disk because we specified a location

    // reopen graph: node should be there
    try (Graph graph = GratefulDead.newGraph(config)) {
      assertEquals(1, graph.nodeCount());
      final Node song1 = graph.node(song1Id);
      assertEquals("node should have been persisted to disk and reloaded when reopened the graph",
          "Song 1", song1.property(Song.NAME));

      // delete the node, close the graph
      song1.remove();
      assertEquals(0, graph.nodeCount());
    }

    try (Graph graph = GratefulDead.newGraph(config)) {
      assertEquals(0, graph.nodeCount());
    }
  }

  @Test
  public void shouldDeleteTmpStorageIfNoStorageLocationConfigured() {
    final File tmpStorageFile;

    try (Graph graph = GratefulDead.newGraph()) {
      graph.addNode(Song.label, Song.NAME, "Song 1");
      tmpStorageFile = graph.getStorage().getStorageFile();
    } // ARM auto-close will trigger saving to disk because we specified a location

    assertFalse("temp storage file should be deleted on close", tmpStorageFile.exists());
  }

  @Test(expected = BackwardsCompatibilityError.class)
  public void shouldErrorWhenTryingToOpenWithoutStorageFormatVersion() throws IOException {
    File storageFile = Files.createTempFile("overflowdb", "bin").toFile();
    storageFile.deleteOnExit();
    OdbStorage storage = OdbStorage.createWithSpecificLocation(storageFile, stringInterner);
    storage.close();

    // modify storage: drop storage version
    MVStore store = new MVStore.Builder().fileName(storageFile.getAbsolutePath()).open();
    final MVMap<String, String> metadata = store.openMap("metadata");
    metadata.remove(OdbStorage.METADATA_KEY_STORAGE_FORMAT_VERSION);
    store.close();

    // should throw a BackwardsCompatibilityError
    OdbStorage.createWithSpecificLocation(storageFile, stringInterner);
  }

  @Test(expected = BackwardsCompatibilityError.class)
  public void shouldErrorWhenTryingToOpenDifferentStorageFormatVersion() throws IOException {
    File storageFile = Files.createTempFile("overflowdb", "bin").toFile();
    storageFile.deleteOnExit();
    OdbStorage storage = OdbStorage.createWithSpecificLocation(storageFile, stringInterner);
    storage.close();

    // modify storage: change storage version
    MVStore store = new MVStore.Builder().fileName(storageFile.getAbsolutePath()).open();
    final MVMap<String, String> metadata = store.openMap("metadata");
    metadata.put(OdbStorage.METADATA_KEY_STORAGE_FORMAT_VERSION, "-1");
    store.close();

    // should throw a BackwardsCompatibilityError
    OdbStorage.createWithSpecificLocation(storageFile, stringInterner);
  }

  @Test
  public void shouldProvideStringToIntGlossary() throws IOException {
    File storageFile = Files.createTempFile("overflowdb", "bin").toFile();
    storageFile.deleteOnExit();
    OdbStorage storage = OdbStorage.createWithSpecificLocation(storageFile, stringInterner);

    String a = "a";
    String b = "b";
    String c = "c";

    int stringIdA = storage.lookupOrCreateStringToIntMapping(a);
    int stringIdB = storage.lookupOrCreateStringToIntMapping(b);
    assertEquals(a, storage.reverseLookupStringToIntMapping(stringIdA));
    assertEquals(b, storage.reverseLookupStringToIntMapping(stringIdB));

    // should be idempotent - i.e. should not create additional entries
    assertEquals(stringIdA, storage.lookupOrCreateStringToIntMapping(a));
    assertEquals(stringIdB, storage.lookupOrCreateStringToIntMapping(b));

    // should survive restarts
    storage.close();
    storage = OdbStorage.createWithSpecificLocation(storageFile, stringInterner);
    assertEquals(stringIdA, storage.lookupOrCreateStringToIntMapping(a));
    assertEquals(stringIdB, storage.lookupOrCreateStringToIntMapping(b));

    int stringIdC = storage.lookupOrCreateStringToIntMapping(c);
    assertEquals(3, storage.getStringToIntMappings().size());

    assertEquals(a, storage.reverseLookupStringToIntMapping(stringIdA));
    assertEquals(b, storage.reverseLookupStringToIntMapping(stringIdB));
    assertEquals(c, storage.reverseLookupStringToIntMapping(stringIdC));
  }

  @Test
  public void stringToIntMappingShouldBeConsistentUnderConcurrency() throws Exception {
    OdbStorage storage = OdbStorage.createWithTempFile(stringInterner);

    final int threadCount = 16;
    final int keysPerThread = 500;
    final java.util.List<String> keys = new java.util.ArrayList<>();
    for (int i = 0; i < keysPerThread; i++) keys.add("key" + i);

    final java.util.concurrent.ConcurrentHashMap<String, java.util.Set<Integer>> idsPerKey =
        new java.util.concurrent.ConcurrentHashMap<>();
    final java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newFixedThreadPool(threadCount);
    final java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
    final java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();

    for (int t = 0; t < threadCount; t++) {
      futures.add(pool.submit(() -> {
        try {
          start.await();
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
        for (String key : keys) {
          int id = storage.lookupOrCreateStringToIntMapping(key);
          idsPerKey.computeIfAbsent(key, k -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add(id);
        }
      }));
    }
    start.countDown();
    for (java.util.concurrent.Future<?> f : futures) f.get();
    pool.shutdown();

    // each key must have been assigned exactly one id, even under concurrent first-time creation
    for (String key : keys) {
      assertEquals("key " + key + " must map to a single id", 1, idsPerKey.get(key).size());
      int id = idsPerKey.get(key).iterator().next();
      assertEquals(key, storage.reverseLookupStringToIntMapping(id));
    }
    assertEquals(keysPerThread, storage.getStringToIntMappings().size());
    storage.close();
  }

  @Test
  public void testGlossaryPreinitialization() throws IOException {
    final File storageFile = Files.createTempFile("overflowdb-preinit", "bin").toFile();
    storageFile.deleteOnExit();

    // 1. With pre-initialization enabled (default)
    Config configPre = Config.withDefaults().withStorageLocation(storageFile.getAbsolutePath()).withGlossaryPreinitEnabled(true);
    try (Graph graph = GratefulDead.newGraph(configPre)) {
      // The glossary should already contain the Song label and other schema strings
      OdbStorage storage = graph.getStorage();
      int songLabelId = storage.lookupStringToInt(Song.label);
      org.junit.Assert.assertTrue("Glossary should pre-initialize Song label", songLabelId > 0);
    }

    // Clean up
    storageFile.delete();

    // 2. With pre-initialization disabled
    Config configNoPre = Config.withDefaults().withStorageLocation(storageFile.getAbsolutePath()).withGlossaryPreinitEnabled(false);
    try (Graph graph = GratefulDead.newGraph(configNoPre)) {
      OdbStorage storage = graph.getStorage();
      // Since it's a new empty storage and preinit is disabled, the internal map should be empty or not contain schema strings yet
      int count = storage.getStringToIntMappings().size();
      assertEquals("Glossary should be empty when pre-initialization is disabled", 0, count);
    }
  }

}

