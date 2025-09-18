package overflowdb.storage;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public abstract class BookKeeper {
  public final boolean statsEnabled;
  private final AtomicInteger totalCount = new AtomicInteger(0);
  private final AtomicLong totalTimeSpentNanos = new AtomicLong(0);

  protected BookKeeper(boolean statsEnabled) {
    this.statsEnabled = statsEnabled;
  }

  protected final long getStartTimeNanos() {
    // System.nanoTime is relatively expensive - only  go there if we're actually recording stats
    return statsEnabled ? System.nanoTime() : 0;
  }

  protected void recordStatistics(long startTimeNanos) {
    totalCount.incrementAndGet();
    totalTimeSpentNanos.addAndGet(System.nanoTime() - startTimeNanos);
  }

  public final int getSerializedCount() {
    if (statsEnabled) return totalCount.intValue();
    else throw new RuntimeException("serialization statistics not enabled");
  }
}
