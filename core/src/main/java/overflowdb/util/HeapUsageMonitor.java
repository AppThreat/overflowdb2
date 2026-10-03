package overflowdb.util;

import com.sun.management.GarbageCollectionNotificationInfo;
import overflowdb.ReferenceManager;

import javax.management.ListenerNotFoundException;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Listens for GC notifications and triggers eviction on the given {@link ReferenceManager} when the heap is above the
 * threshold after a major GC.
 *
 * The listeners are registered on the platform GC MXBeans, which live for the lifetime of the JVM. Each listener
 * holds the ReferenceManager (and therefore the graph's storage), so the monitor must be closed when the graph is
 * closed, otherwise every graph ever created stays reachable.
 */
public class HeapUsageMonitor implements AutoCloseable {
  private static final String GC_NAME_PATTERN = ".*(ConcurrentMarkSweep|G1|CMS|Garbage|Parallel).*";

  private final List<Registration> registrations = new ArrayList<>();
  private volatile boolean closed = false;

  private HeapUsageMonitor() {}

  public static HeapUsageMonitor install(ReferenceManager referenceManager, int heapPercentageThreshold) {
    HeapUsageMonitor monitor = new HeapUsageMonitor();
    List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
    for (GarbageCollectorMXBean gcBean : gcBeans) {
      if (gcBean.getName().matches(GC_NAME_PATTERN)) {
        try {
          NotificationEmitter emitter = (NotificationEmitter) gcBean;
          NotificationListener listener = (notification, handback) -> {
            // a notification may already be in flight while we're being closed
            if (monitor.closed) return;
            if (notification.getType().equals(GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION)) {
              CompositeData cd = (CompositeData) notification.getUserData();
              GarbageCollectionNotificationInfo info = GarbageCollectionNotificationInfo.from(cd);

              if (info.getGcAction().toLowerCase().contains("end of major gc") ||
                  info.getGcAction().toLowerCase().contains("end of garbage collection") ||
                  info.getGcAction().toLowerCase().contains("end of parallel gc")) {

                Map<String, MemoryUsage> usageMap = info.getGcInfo().getMemoryUsageAfterGc();
                for (Map.Entry<String, MemoryUsage> entry : usageMap.entrySet()) {
                  if (entry.getKey().toLowerCase().contains("heap") ||
                      entry.getKey().toLowerCase().contains("tenured") ||
                      entry.getKey().toLowerCase().contains("old gen")) {

                    MemoryUsage usage = entry.getValue();
                    long used = usage.getUsed();
                    long max = usage.getMax();
                    if (max > 0) {
                      double usagePct = ((double) used / max) * 100.0;
                      if (usagePct > heapPercentageThreshold) {
                        referenceManager.triggerAsynchronousEviction();
                      }
                    }
                  }
                }
              }
            }
          };
          emitter.addNotificationListener(listener, null, null);
          monitor.registrations.add(new Registration(emitter, listener));
        } catch (ClassCastException e) {
          // ignore if the MXBean is not a NotificationEmitter
        }
      }
    }
    return monitor;
  }

  /** Removes all GC notification listeners registered by this monitor. Safe to call more than once. */
  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
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
