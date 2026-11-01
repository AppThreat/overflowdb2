package overflowdb.util;

import com.sun.management.GarbageCollectionNotificationInfo;
import overflowdb.ReferenceManager;

import javax.management.ListenerNotFoundException;
import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

/**
 * Triggers eviction on the given {@link ReferenceManager} when the old generation fills up.
 *
 * <p>The graph lives in the old generation, so that is what is measured: the heap pools ({@link MemoryType#HEAP})
 * other than eden, survivor and young ones. The limit is the threshold's share of the heap, or 90% of the old
 * generation if that is less (under Serial and Parallel the old generation is about two thirds of the heap).
 *
 * <p>Usage after a collection that reclaims the old generation is what is live, and is checked against the limit:
 * G1 concurrent cycles, mixed and full collections, Serial and Parallel full collections, ZGC major cycles,
 * Shenandoah cycles and complete collections in a GraalVM native image. After a young collection the old generation
 * still holds garbage (under G1 it read 53% with 10% of the heap live), see {@link #onCollection}. Before, only a G1
 * full collection counted, which G1 runs when the heap is nearly exhausted, and the Serial, Parallel, ZGC and
 * Shenandoah collectors were not recognised at all.
 *
 * <p>The JVM reports collections through notifications of the GC MXBeans. A GraalVM native image sends none, so the
 * monitor also polls the collection counts and the old pools' usage after collection until the first notification
 * arrives.
 *
 * <p>The listeners are registered on the platform GC MXBeans, which live for the lifetime of the JVM. Each listener
 * holds the ReferenceManager (and therefore the graph's storage), so the monitor must be closed when the graph is
 * closed, otherwise every graph ever created stays reachable.
 */
public class HeapUsageMonitor implements AutoCloseable {
  static final long POLL_INTERVAL_MILLIS = 100;

  private final List<Registration> registrations = new ArrayList<>();
  private final Set<String> oldPoolNames = new HashSet<>();
  private final List<MemoryPoolMXBean> oldPools = new ArrayList<>();
  /** The old generation's maximum, at most the heap's. */
  private final long oldMax;
  private final long heapMax = Runtime.getRuntime().maxMemory();
  private final int heapPercentageThreshold;
  private final IntConsumer onAboveThreshold;
  private volatile boolean closed = false;
  private volatile boolean notificationsSeen = false;
  private ScheduledExecutorService poller;
  private long lastOldCollectionCount = -1;
  /** Old generation usage after the last collection that reclaimed it, or -1 before the first. */
  private long lastLiveEstimate = -1;
  /** Set after an eviction until the next collection that reclaims the old generation. */
  private boolean awaitingReclaim = false;

  private HeapUsageMonitor(int heapPercentageThreshold, IntConsumer onAboveThreshold) {
    this.heapPercentageThreshold = heapPercentageThreshold;
    this.onAboveThreshold = onAboveThreshold;
    long max = 0;
    boolean allMaxKnown = true;
    for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
      if (pool.getType() == MemoryType.HEAP && !isYoungPool(pool.getName())) {
        oldPoolNames.add(pool.getName());
        oldPools.add(pool);
        long poolMax = pool.getUsage().getMax();
        if (poolMax > 0) max += poolMax;
        else allMaxKnown = false;
      }
    }
    // a native image reports no pool maximum; a pool that shares the heap (G1, ZGC) reports all of it
    long heapMax = Runtime.getRuntime().maxMemory();
    this.oldMax = allMaxKnown && max > 0 ? Math.min(max, heapMax) : heapMax;
  }

  /** Eden, survivor and young generation pools hold no graph for long; they are left out. */
  static boolean isYoungPool(String name) {
    String n = name.toLowerCase(java.util.Locale.ROOT);
    return n.contains("eden") || n.contains("survivor") || n.contains("young") || n.contains("nursery");
  }

  /** Whether a collector bean only collects the young generation (or reports pauses within a cycle), so that the old
   * generation still holds its garbage afterwards. Unknown collectors count as reclaiming the old generation: the
   * worst that does is evict early. */
  static boolean isYoungOnly(String beanName, String gcAction) {
    String n = beanName.toLowerCase(java.util.Locale.ROOT);
    String a = gcAction == null ? "" : gcAction.toLowerCase(java.util.Locale.ROOT);
    return n.contains("young") || n.contains("minor") || n.contains("pause") || n.equals("copy")
        || n.equals("ps scavenge") || n.equals("parnew") || a.contains("minor");
  }

  public static HeapUsageMonitor install(ReferenceManager referenceManager, int heapPercentageThreshold) {
    return install(heapPercentageThreshold, usedPercent -> referenceManager.triggerAsynchronousEviction());
  }

  /** @param onAboveThreshold called with the heap use in percent after each collection that left it above the
   *                          threshold, as a share of the heap */
  public static HeapUsageMonitor install(int heapPercentageThreshold, IntConsumer onAboveThreshold) {
    return install(heapPercentageThreshold, onAboveThreshold, false);
  }

  /** @param pollOnly ignore GC notifications, as if they were not sent (as in a native image). For tests. */
  static HeapUsageMonitor install(int heapPercentageThreshold, IntConsumer onAboveThreshold, boolean pollOnly) {
    HeapUsageMonitor monitor = new HeapUsageMonitor(heapPercentageThreshold, onAboveThreshold);
    if (!pollOnly) registerListeners(monitor);
    monitor.startPolling();
    return monitor;
  }

  private static void registerListeners(HeapUsageMonitor monitor) {
    for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
      if (gcBean instanceof NotificationEmitter emitter) {
        NotificationListener listener = (notification, handback) -> monitor.onNotification(gcBean.getName(), notification);
        emitter.addNotificationListener(listener, null, null);
        monitor.registrations.add(new Registration(emitter, listener));
      }
    }
  }

  private void onNotification(String beanName, Notification notification) {
    // a notification may already be in flight while we're being closed
    if (closed || !GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(notification.getType())) return;
    notificationsSeen = true;
    GarbageCollectionNotificationInfo info = GarbageCollectionNotificationInfo.from((CompositeData) notification.getUserData());
    long before = 0;
    long after = 0;
    boolean sawOldPool = false;
    for (Map.Entry<String, MemoryUsage> entry : info.getGcInfo().getMemoryUsageAfterGc().entrySet()) {
      if (oldPoolNames.contains(entry.getKey())) {
        after += entry.getValue().getUsed();
        MemoryUsage usageBefore = info.getGcInfo().getMemoryUsageBeforeGc().get(entry.getKey());
        if (usageBefore != null) before += usageBefore.getUsed();
        sawOldPool = true;
      }
    }
    if (!sawOldPool) after = oldPoolsUsedAfterCollection();
    /* A G1 mixed collection reclaims old regions but is reported as a minor one: old usage going down tells it apart */
    final boolean reclaimedOld = !isYoungOnly(beanName, info.getGcAction()) || (sawOldPool && after < before);
    onCollection(after, reclaimedOld);
  }

  /**
   * After a collection that reclaimed the old generation, its usage is what is live there, and is checked against the
   * limit. After a young one it also holds garbage no collection has looked at yet. Checked against the limit, that
   * evicted nodes for garbage after each young collection (under G1 they were read back again and again); ignored,
   * eviction came too late under Serial and Parallel, which reclaim the old generation only once it is full. So a
   * young collection triggers eviction once the old generation has grown halfway from what was live to the limit,
   * and, right after an eviction, only once it is nearly full.
   */
  private synchronized void onCollection(long oldUsedAfter, boolean reclaimedOld) {
    if (closed || oldMax <= 0 || oldMax == Long.MAX_VALUE || heapMax <= 0 || heapMax == Long.MAX_VALUE) return;
    if (reclaimedOld) {
      final boolean growing = lastLiveEstimate < 0 || oldUsedAfter > lastLiveEstimate;
      lastLiveEstimate = oldUsedAfter;
      awaitingReclaim = false;
      /* Still growing and above the threshold's share of the old generation: under Serial the young generation is
       * a third of the heap, and what it promotes before the next collection can fill the old generation, which
       * then ran out with the first collection just under the limit. */
      if (growing && oldUsedAfter > oldMax / 100 * heapPercentageThreshold) {
        onAboveThreshold.accept((int) (oldUsedAfter * 100 / heapMax));
      } else {
        check(oldUsedAfter);
      }
    } else if (awaitingReclaim) {
      /* the nodes just evicted are garbage in the old generation until a collection reclaims it: only a nearly full
       * old generation means more must go */
      if (oldUsedAfter * 100 / oldMax > CONTINUE_EVICTION_PERCENT) check(oldUsedAfter);
    } else if (lastLiveEstimate < 0) {
      check(oldUsedAfter);
    } else {
      /* Collectors that reclaim the old generation only when it is full (Serial, Parallel) leave no time to evict
       * after that collection: start once the old generation has grown halfway from what was live to the limit */
      final long limit = limit();
      if (oldUsedAfter > limit || oldUsedAfter - lastLiveEstimate > (limit - lastLiveEstimate) / 2) {
        onAboveThreshold.accept((int) (oldUsedAfter * 100 / heapMax));
      }
    }
  }

  private long oldPoolsUsedAfterCollection() {
    long used = 0;
    for (MemoryPoolMXBean pool : oldPools) {
      MemoryUsage afterGc = pool.getCollectionUsage();
      used += (afterGc != null ? afterGc : pool.getUsage()).getUsed();
    }
    return used;
  }

  private void startPolling() {
    poller = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("overflowdb-heap-usage-monitor", true));
    poller.scheduleWithFixedDelay(this::poll, POLL_INTERVAL_MILLIS, POLL_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
  }

  /** Runs until the first notification: after it, notifications report every collection. */
  private void poll() {
    if (closed) return;
    if (notificationsSeen) {
      poller.shutdown();
      return;
    }
    long count = 0;
    for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
      if (!isYoungOnly(gcBean.getName(), null)) count += Math.max(0, gcBean.getCollectionCount());
    }
    final boolean collected = lastOldCollectionCount >= 0 && count != lastOldCollectionCount;
    lastOldCollectionCount = count;
    // a notification may have come in meanwhile and already handled this collection
    if (collected && !notificationsSeen) onCollection(oldPoolsUsedAfterCollection(), true);
  }

  /** Called once an eviction is done: until a collection reclaims the old generation, its usage includes the nodes
   * just evicted, and young collections only trigger another eviction when it is nearly full. */
  public synchronized void evicted() {
    awaitingReclaim = true;
  }

  /** Share of the old generation in use right now at which eviction goes on with another batch without waiting for
   * the next collection. */
  static final int CONTINUE_EVICTION_PERCENT = 90;

  /** Whether the old generation is nearly full right now. It still holds garbage that no collection has reclaimed,
   * so this is only used to go on evicting after a collection found the heap above the threshold, and only close to
   * running out: otherwise eviction would go on removing nodes for what is only garbage. */
  public boolean isNearlyFullNow() {
    if (closed || oldMax <= 0 || oldMax == Long.MAX_VALUE) return false;
    long used = 0;
    for (MemoryPoolMXBean pool : oldPools) used += pool.getUsage().getUsed();
    return used * 100 / oldMax > Math.max(heapPercentageThreshold, CONTINUE_EVICTION_PERCENT);
  }

  /** Old generation usage above which nodes are evicted: the threshold's share of the heap (the threshold is
   * documented as one), or 90% of the old generation if that is less. Under Serial and Parallel the old generation is
   * about two thirds of the heap, and fills up, so that the JVM runs out of memory, before it holds 80% of the heap.
   * Measured against the old generation alone, eviction started at about half of the heap there, and nodes were
   * evicted and read back again and again. */
  private long limit() {
    return Math.min(heapMax / 100 * heapPercentageThreshold, oldMax / 100 * CONTINUE_EVICTION_PERCENT);
  }

  private void check(long usedAfterGc) {
    if (closed || oldMax <= 0 || oldMax == Long.MAX_VALUE || heapMax <= 0 || heapMax == Long.MAX_VALUE) return;
    if (usedAfterGc > limit()) onAboveThreshold.accept((int) (usedAfterGc * 100 / heapMax));
  }

  /** Whether polling still runs, i.e. no notification has arrived yet. For tests. */
  boolean isPolling() {
    return poller != null && !poller.isShutdown();
  }

  /** Removes all GC notification listeners registered by this monitor and stops polling. Safe to call more than once. */
  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    if (poller != null) poller.shutdownNow();
    for (Registration registration : registrations) {
      try {
        registration.emitter.removeNotificationListener(registration.listener);
      } catch (ListenerNotFoundException e) {
        // already removed
      }
    }
    registrations.clear();
  }

  private static final class Registration {
    final NotificationEmitter emitter;
    final NotificationListener listener;

    Registration(NotificationEmitter emitter, NotificationListener listener) {
      this.emitter = emitter;
      this.listener = listener;
    }
  }
}
