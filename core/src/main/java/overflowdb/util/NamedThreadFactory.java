package overflowdb.util;

import java.util.concurrent.ThreadFactory;

public class NamedThreadFactory implements ThreadFactory {
  private final String threadName;
  private final boolean daemon;

  public NamedThreadFactory(String threadName) {
    this(threadName, false);
  }

  public NamedThreadFactory(String threadName, boolean daemon) {
    this.threadName = threadName;
    this.daemon = daemon;
  }

  public Thread newThread(Runnable r) {
    Thread thread = new Thread(r, threadName);
    thread.setDaemon(daemon);
    return thread;
  }
}
