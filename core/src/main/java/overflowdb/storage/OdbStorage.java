package overflowdb.storage;

import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;
import overflowdb.Config;
import overflowdb.util.StringInterner;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class OdbStorage implements AutoCloseable {
    /** increase this number when persistence format changes (usually driven by changes in the NodeSerializer)
     * this protects us from attempting to open outdated formats */
    public static final int STORAGE_FORMAT_VERSION = 3;

    public static final String METADATA_KEY_STORAGE_FORMAT_VERSION = "STORAGE_FORMAT_VERSION";
    public static final String METADATA_KEY_STRING_TO_INT_MAX_ID = "STRING_TO_INT_MAX_ID";
    public static final String METADATA_KEY_LIBRARY_VERSIONS_MAX_ID = "LIBRARY_VERSIONS_MAX_ID";
    public static final String METADATA_PREFIX_LIBRARY_VERSIONS = "LIBRARY_VERSIONS_ENTRY_";
    private static final String INDEX_PREFIX = "index_";
    public static final int DEFAULT_COMPACT_FILL_RATE = 50; // In percent
    public static final int DEFAULT_COMMIT_BUFFER_SIZE = 1024 * 64; // 64 MB


    private final File mvstoreFile;
    private final StringInterner stringInterner;
    private final Config config;
    protected MVStore mvstore;
    private MVMap<Long, byte[]> nodesMVMap;
    private MVMap<String, String> metadataMVMap;
    private MVMap<String, Integer> stringToIntMappings;
    private MVMap<Integer, String> intToStringMappings;
    private boolean closed;
    private final AtomicInteger stringToIntMappingsMaxId = new AtomicInteger(0);
    private int libraryVersionsIdCurrentRun;
    private final boolean isTemporary;

    /* In-heap caches in front of the MVStore-backed string<->int glossary maps. The glossary is
     * consulted once per property key, edge label and node label on every (de)serialization, so a
     * direct MVStore lookup on each access dominates serialization time on large graphs. Schema-derived
     * keys form a tiny, bounded set, so caching them on-heap is cheap and removes those lookups.
     * The string->int cache additionally makes mapping creation atomic, preventing duplicate ids being
     * assigned to the same string under concurrent serialization. */
    private final java.util.concurrent.ConcurrentHashMap<String, Integer> stringToIntCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<Integer, String> intToStringCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static OdbStorage createWithTempFile(StringInterner stringInterner) {
        Config defaultConfig = Config.withDefaults();
        return new OdbStorage(Optional.empty(), stringInterner, defaultConfig);
    }

    /**
     * create with specific mvstore file - which may or may not yet exist.
     * mvstoreFile won't be deleted at the end (unlike temp file constructors above)
     */
    public static OdbStorage createWithSpecificLocation(final File mvstoreFile, StringInterner stringInterner) {
        Config defaultConfig = Config.withDefaults();
        return new OdbStorage(Optional.ofNullable(mvstoreFile), stringInterner, defaultConfig);
    }

    /**
     * Create with temp file and specific config
     */
    public static OdbStorage createWithTempFile(StringInterner stringInterner, Config config) {
        return new OdbStorage(Optional.empty(), stringInterner, config);
    }

    /**
     * Create with specific mvstore file and config
     */
    public static OdbStorage createWithSpecificLocation(final File mvstoreFile, StringInterner stringInterner, Config config) {
        return new OdbStorage(Optional.ofNullable(mvstoreFile), stringInterner, config);
    }

    private OdbStorage(final Optional<File> mvstoreFileMaybe, StringInterner stringInterner, Config config) {
        this.config = config;
        this.stringInterner = stringInterner;
        this.isTemporary = !mvstoreFileMaybe.isPresent();
        if (mvstoreFileMaybe.isPresent()) {
            mvstoreFile = mvstoreFileMaybe.get();
            if (mvstoreFile.exists() && mvstoreFile.length() > 0) {
                verifyStorageVersion();
                initializeStringToIntMaxId();
            }
        } else {
            try {
                mvstoreFile = File.createTempFile("mvstore", ".bin");
                if (!System.getProperty("os.name").toLowerCase().contains("win")) {
                    mvstoreFile.delete();
                } else {
                    mvstoreFile.deleteOnExit();
                }
            } catch (IOException e) {
                throw new RuntimeException("cannot create tmp file for mvstore", e);
            }
        }
    }

    private void initializeStringToIntMaxId() {
        MVMap<String, String> metadata = getMetaDataMVMap();
        if (metadata.containsKey(METADATA_KEY_STRING_TO_INT_MAX_ID)) {
            int maxIndexFromStorage = Integer.parseInt(metadata.get(METADATA_KEY_STRING_TO_INT_MAX_ID));
            stringToIntMappingsMaxId.set(maxIndexFromStorage);
        }
    }

    /** storage version must be exactly the same */
    private void verifyStorageVersion() {
        ensureMVStoreAvailable();
        MVMap<String, String> metaData = getMetaDataMVMap();
        if (!metaData.containsKey(METADATA_KEY_STORAGE_FORMAT_VERSION)) {
            throw new BackwardsCompatibilityError("storage metadata does not contain version number, this must be an old format.");
        }

        String storageFormatVersionString = metaData.get(METADATA_KEY_STORAGE_FORMAT_VERSION);
        int storageFormatVersion = Integer.parseInt(storageFormatVersionString);
        if (storageFormatVersion != STORAGE_FORMAT_VERSION) {
            throw new BackwardsCompatibilityError(String.format(
                    "attempting to open storage with different version: %s; this version of overflowdb requires the version to be exactly %s",
                    storageFormatVersion, STORAGE_FORMAT_VERSION));
        }
    }

    public void persist(long id, byte[] node) {
        if (!closed) {
            getNodesMVMap().put(id, node);
        }
    }

    /** flush any remaining changes in underlying storage to disk */
    public void flush() {
        if (mvstore != null) {
            getMetaDataMVMap().put(METADATA_KEY_STORAGE_FORMAT_VERSION, String.format("%s", STORAGE_FORMAT_VERSION));
            getMetaDataMVMap().put(METADATA_KEY_STRING_TO_INT_MAX_ID, String.format("%s", stringToIntMappingsMaxId.get()));
            mvstore.commit();
        }
    }

    @Override
    public void close() {
        closed = true;
        flush();
        if (mvstore != null) mvstore.close();
        if (isTemporary && mvstoreFile != null && mvstoreFile.exists()) {
            mvstoreFile.delete();
        }
    }

    public File getStorageFile() {
        return mvstoreFile;
    }

    public void removeNode(final Long id) {
        getNodesMVMap().remove(id);
    }

    public Set<Map.Entry<Long, byte[]>> allNodes() {
        return getNodesMVMap().entrySet();
    }

    public MVMap<Long, byte[]> getNodesMVMap() {
        ensureMVStoreAvailable();
        if (nodesMVMap == null)
            nodesMVMap = mvstore.openMap("nodes");
        return nodesMVMap;
    }

    public MVMap<String, String> getMetaDataMVMap() {
        ensureMVStoreAvailable();
        if (metadataMVMap == null)
            metadataMVMap = mvstore.openMap("metadata");
        return metadataMVMap;
    }

    public MVMap<String, Integer> getStringToIntMappings() {
        ensureMVStoreAvailable();
        if (stringToIntMappings == null) {
            stringToIntMappings = mvstore.openMap("stringToIntMappings");
        }
        return stringToIntMappings;
    }

    private MVMap<Integer, String> getIntToStringMappings() {
        ensureMVStoreAvailable();
        if (intToStringMappings == null)
            intToStringMappings = mvstore.openMap("intToStringMappings");
        return intToStringMappings;
    }

    public int lookupOrCreateStringToIntMapping(String s) {
        String interned = stringInterner.intern(s);
        /* computeIfAbsent runs the mapping function at most once per key, which both serves repeat
         * lookups from the heap cache and guarantees a single id is created per string even when
         * multiple threads race on a previously unseen string. */
        return stringToIntCache.computeIfAbsent(interned, key -> {
            final MVMap<String, Integer> mappings = getStringToIntMappings();
            Integer existing = mappings.get(key);
            return existing != null ? existing : createStringToIntMapping(key);
        });
    }

    public void preInitializeGlossary(Set<String> strings) {
        for (String s : strings) {
            if (s != null) {
                lookupOrCreateStringToIntMapping(s);
            }
        }
    }

    public int lookupStringToInt(String s) {
        Integer id = stringToIntCache.get(s);
        if (id != null) {
            return id;
        }
        return lookupOrCreateStringToIntMapping(s);
    }

    private int createStringToIntMapping(String s) {
        final int index = stringToIntMappingsMaxId.incrementAndGet();
        getStringToIntMappings().put(s, index);
        getIntToStringMappings().put(index, s);
        intToStringCache.put(index, s);
        return index;
    }

    /**
     * initialize list with correct size - we want to use it as an reverse index,
     * and ArrayList.ensureCapacity doesn't actually grow the list... */
    private void ensureCapacity(ArrayList<String> array, int requiredMinSize) {
        while (array.size() < requiredMinSize) {
            array.add(null);
        }
    }

    public String reverseLookupStringToIntMapping(int stringId) {
        String cached = intToStringCache.get(stringId);
        if (cached != null) return cached;
        String s = getIntToStringMappings().get(stringId);
        if (s == null) return null;
        String interned = stringInterner.intern(s);
        intToStringCache.put(stringId, interned);
        return interned;
    }

    private void ensureMVStoreAvailable() {
        if (mvstore == null) {
            mvstore = initializeMVStore();
            persistOdbLibraryVersion();
            this.libraryVersionsIdCurrentRun = initializeLibraryVersionsIdCurrentRun();
        }
    }

    private MVStore initializeMVStore() {
        MVStore.Builder builder = new MVStore.Builder()
                .autoCommitDisabled()
                .autoCompactFillRate(DEFAULT_COMPACT_FILL_RATE);

        switch (config.getStorageCompressionMode()) {
            case LZF:
                builder.compress();
                break;
            case DEFLATE:
                builder.compressHigh();
                break;
            case NONE:
            default:
                break;
        }

        if (config.getCacheSize().isPresent()) {
            builder.cacheSize(config.getCacheSize().get());
        }
        if (config.getPageSplitSize().isPresent()) {
            builder.pageSplitSize(config.getPageSplitSize().get());
        }
        builder.fileName(mvstoreFile.getAbsolutePath());
        return builder.open();
    }

    private int initializeLibraryVersionsIdCurrentRun() {
        MVMap<String, String> metaData = getMetaDataMVMap();
        final int res;
        if (metaData.containsKey(METADATA_KEY_LIBRARY_VERSIONS_MAX_ID)) {
            res = Integer.parseInt(metaData.get(METADATA_KEY_LIBRARY_VERSIONS_MAX_ID)) + 1;
        } else {
            res = 0;
        }

        metaData.put(METADATA_KEY_LIBRARY_VERSIONS_MAX_ID, "" + res);
        return res;
    }

    private Map<String, String> getIndexNameMap(MVStore store) {
        return store
                .getMapNames()
                .stream()
                .filter(s -> s.startsWith(INDEX_PREFIX))
                .collect(Collectors.toConcurrentMap(this::removeIndexPrefix, s -> s));
    }

    public Set<String> getIndexNames() {
        return getIndexNameMap(mvstore).keySet();
    }

    private String removeIndexPrefix(String s) {
        assert s.startsWith(INDEX_PREFIX);
        return s.substring(INDEX_PREFIX.length());
    }

    public MVMap<Object, long[]> openIndex(String indexName) {
        final String mapName = getIndexMapName(indexName);
        return mvstore.openMap(mapName);
    }

    private String getIndexMapName(String indexName) {
        return INDEX_PREFIX + indexName;
    }

    public void clearIndices() {
        getIndexNames().forEach(this::clearIndex);
    }

    public void clearIndex(String indexName) {
        openIndex(indexName).clear();
    }

    public byte[] getSerializedNode(long nodeId) {
        return getNodesMVMap().get(nodeId);
    }

    private void persistOdbLibraryVersion() {
        Class<?> clazz = getClass();
        String version = clazz.getPackage().getImplementationVersion();
        if (version != null) persistLibraryVersion(clazz.getCanonicalName(), version);
    }

    public void persistLibraryVersion(String name, String version) {
        String key = String.format("%s%d_%s", METADATA_PREFIX_LIBRARY_VERSIONS, libraryVersionsIdCurrentRun, name);
        getMetaDataMVMap().put(key, version);
    }

    public ArrayList<Map<String, String>> getAllLibraryVersions() {
        Map<Integer, Map<String, String>> libraryVersionsByRunId = new HashMap<>();
        getMetaDataMVMap().forEach((key, version) -> {
            if (key.startsWith(METADATA_PREFIX_LIBRARY_VERSIONS)) {
                String withoutPrefix = key.substring(METADATA_PREFIX_LIBRARY_VERSIONS.length());
                int firstDividerIndex = withoutPrefix.indexOf('_');
                int runId = Integer.parseInt(withoutPrefix.substring(0, firstDividerIndex));
                String library = withoutPrefix.substring(firstDividerIndex + 1);
                Map<String, String> versionInfos = libraryVersionsByRunId.computeIfAbsent(runId, i -> new HashMap<>());
                versionInfos.put(library, version);
            }
        });

        return new ArrayList<>(libraryVersionsByRunId.values());
    }
}