package overflowdb;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

public class Config {
    private boolean overflowEnabled = true;
    private int heapPercentageThreshold = 80;
    private Optional<Path> storageLocation = Optional.empty();
    private boolean serializationStatsEnabled = false;
    private Optional<ExecutorService> executorService = Optional.empty();
    private FileSystemType fileSystemType = FileSystemType.DEFAULT;
    private static Optional<Integer> compressionRatio = Optional.empty();
    private static final int defaultCompressionRatio = 20;
    private static final int defaultCacheSize = 128 * 1024; // 128 MB
    private static final int defaultPageSplitSize = 64 * 1024; // 64 KB
    private Optional<Integer> cacheSize = Optional.empty();
    private Optional<Integer> pageSplitSize = Optional.empty();

    public static Config withDefaults() {
        return new Config()
                .withCacheSize(defaultCacheSize)
                .withPageSplitSize(defaultPageSplitSize);
    }

    public static Config withoutOverflow() {
        return withDefaults().disableOverflow();
    }

    public Config disableOverflow() {
        this.overflowEnabled = false;
        return this;
    }

    /**
     * when heap - after full GC - is above this threshold, OdbGraph will start to clear some references,
     * i.e. write them to storage and set them to `null`.
     * defaults to 80, i.e. 80%
     */
    public Config withHeapPercentageThreshold(int threshold) {
        this.heapPercentageThreshold = threshold;
        return this;
    }

    /* If specified, OdbGraph will be saved there on `close`.
     * To load from that location, just instantiate a new OdbGraph with the same location. */
    public Config withStorageLocation(Path path) {
        this.storageLocation = Optional.ofNullable(path);
        return this;
    }

    public Config withStorageLocation(String path) {
        return withStorageLocation(Paths.get(path));
    }

    /* If specified, OdbGraph will measure and report serialization / deserialization timing averages. */
    public Config withSerializationStatsEnabled() {
        this.serializationStatsEnabled = true;
        return this;
    }

    /**
     * Set the file system type for H2 database storage.
     * Default is DEFAULT (regular file system).
     */
    public Config withFileSystemType(FileSystemType fileSystemType) {
        this.fileSystemType = fileSystemType;
        return this;
    }

    /**
     * Set the compression ratio for nioMemLZF file system (percentage of blocks to keep uncompressed).
     * Only applicable when fileSystemType is NIO_MEMLZF.
     * Default is 1% (meaning 1% of blocks are kept uncompressed for performance).
     */
    public Config withCompressionRatio(int ratio) {
        compressionRatio = Optional.of(ratio);
        return this;
    }

    /**
     * Set the cache size for MVStore in MB.
     * Default is 1024 MB.
     */
    public Config withCacheSize(int cacheSizeMB) {
        this.cacheSize = Optional.of(cacheSizeMB);
        return this;
    }

    /**
     * Set the page split size for MVStore in bytes.
     * Default is 16384 (16KB).
     */
    public Config withPageSplitSize(int pageSplitSizeBytes) {
        this.pageSplitSize = Optional.of(pageSplitSizeBytes);
        return this;
    }

    public boolean isOverflowEnabled() {
        return overflowEnabled;
    }

    public int getHeapPercentageThreshold() {
        return heapPercentageThreshold;
    }

    public Optional<Path> getStorageLocation() {
        return storageLocation;
    }

    public boolean isSerializationStatsEnabled() {
        return serializationStatsEnabled;
    }

    public Config withExecutorService(ExecutorService executorService) {
        this.executorService = Optional.ofNullable(executorService);
        return this;
    }

    public Optional<ExecutorService> getExecutorService() {
        return executorService;
    }

    public FileSystemType getFileSystemType() {
        return fileSystemType;
    }

    public Optional<Integer> getCompressionRatio() {
        return compressionRatio;
    }

    public Optional<Integer> getCacheSize() {
        return cacheSize;
    }

    public Optional<Integer> getPageSplitSize() {
        return pageSplitSize;
    }

    // Enum for file system types
    public enum FileSystemType {
        DEFAULT(""),
        FILE("file:"),           // Regular file system (default behavior)
        NIO_MAPPED("nioMapped:"), // Memory mapped files
        ASYNC("async:"),         // Asynchronous file channel
        SPLIT("split:"),         // Split large files into 1GB chunks
        MEM("mem:"),             // In-memory (for MVStore this would be different)
        MEM_LZF("memLZF:"),      // Compressed in-memory
        NIO_MEMFS("nioMemFS:"),  // Memory-mapped outside heap
        NIO_MEMLZF("nioMemLZF:"); // Compressed memory-mapped outside heap

        private final String prefix;

        FileSystemType(String prefix) {
            this.prefix = prefix;
        }

        public String getPrefix() {
            return prefix;
        }

        public String applyPrefix(String path) {
            if (this == DEFAULT) {
                return path;
            }

            String effectivePrefix = prefix;
            if (this == NIO_MEMLZF && compressionRatio.isPresent()) {
                effectivePrefix = "nioMemLZF:" + compressionRatio.get() + ":";
            }
            return effectivePrefix + path;
        }
    }
}