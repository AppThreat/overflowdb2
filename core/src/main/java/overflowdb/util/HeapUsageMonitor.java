package overflowdb.util;

import com.sun.management.GarbageCollectionNotificationInfo;
import overflowdb.ReferenceManager;

import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.List;
import java.util.Map;

public class HeapUsageMonitor {
  private static final String GC_NAME_PATTERN = ".*(ConcurrentMarkSweep|G1|CMS|Garbage|Parallel).*";

  public static void install(ReferenceManager referenceManager, int heapPercentageThreshold) {
    List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
    for (GarbageCollectorMXBean gcBean : gcBeans) {
      if (gcBean.getName().matches(GC_NAME_PATTERN)) {
        try {
          NotificationEmitter emitter = (NotificationEmitter) gcBean;
          NotificationListener listener = (notification, handback) -> {
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
        } catch (ClassCastException e) {
          // ignore if the MXBean is not a NotificationEmitter
        }
      }
    }
  }
}
