package overflowdb.util;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadFactory;

public class NamedThreadFactory implements ThreadFactory {
  private final String threadName;
  public NamedThreadFactory(String threadName) {

    this.threadName = threadName;
  }

  public Thread newThread(Runnable r) {
    return new Thread(() -> {r.run();}, threadName);
  }
}
