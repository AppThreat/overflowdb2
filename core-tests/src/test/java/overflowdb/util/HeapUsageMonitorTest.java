package overflowdb.util;

import org.junit.Assume;
import org.junit.Test;
import overflowdb.Config;
import overflowdb.Graph;
import overflowdb.ReferenceManager;
import overflowdb.testdomains.simple.SimpleDomain;
import overflowdb.testdomains.simple.TestNode;

import javax.management.NotificationEmitter;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Regression tests: the GC notification listeners registered by {@link HeapUsageMonitor} live on the platform MXBeans
 * and used to never be removed, so every closed graph (incl. its storage) stayed reachable for the lifetime of the JVM.
 */
public class HeapUsageMonitorTest {
  private static final int GRAPH_COUNT = 20;

  @Test
  public void closedGraphsShouldNotLeaveGcListenersBehind() {
    Integer before = gcListenerCount();
    Assume.assumeTrue("GC MXBean listener lists not accessible, needs --add-opens java.management/sun.management=ALL-UNNAMED",
        before != null);

    for (int i = 0; i < GRAPH_COUNT; i++) {
      Graph graph = newGraphWithNodes();
      assertTrue("an open graph with overflow enabled should register a listener", gcListenerCount() > before);
      graph.close();
      assertEquals(before, gcListenerCount());
    }
  }

  @Test
  public void closedGraphsShouldBeCollectable() throws Exception {
    List<WeakReference<ReferenceManager>> refs = new ArrayList<>();
    for (int i = 0; i < GRAPH_COUNT; i++) {
      Graph graph = newGraphWithNodes();
      refs.add(new WeakReference<>(referenceManager(graph)));
      graph.close();
    }

    for (int attempt = 0; attempt < 50 && refs.stream().anyMatch(ref -> ref.get() != null); attempt++) {
      System.gc();
      Thread.sleep(20);
    }

    long stillReachable = refs.stream().filter(ref -> ref.get() != null).count();
    assertEquals("ReferenceManagers of closed graphs still reachable", 0, stillReachable);
  }

  @Test
  public void closingTwiceShouldBeSafe() throws Exception {
    Graph graph = newGraphWithNodes();
    ReferenceManager referenceManager = referenceManager(graph);
    graph.close();
    graph.close();
    referenceManager.close();

    HeapUsageMonitor monitor = HeapUsageMonitor.install(referenceManager, 80);
    monitor.close();
    monitor.close();
  }

  private static Graph newGraphWithNodes() {
    Graph graph = SimpleDomain.newGraph(Config.withDefaults().withHeapPercentageThreshold(80));
    for (int i = 0; i < 100; i++) {
      graph.addNode(TestNode.LABEL, TestNode.STRING_PROPERTY, "node" + i);
    }
    return graph;
  }

  private static ReferenceManager referenceManager(Graph graph) throws ReflectiveOperationException {
    Field field = Graph.class.getDeclaredField("referenceManager");
    field.setAccessible(true);
    ReferenceManager referenceManager = (ReferenceManager) field.get(graph);
    assertTrue("overflow should be enabled", referenceManager != null);
    return referenceManager;
  }

  /** @return total number of listeners on all GC MXBeans, or null if the JDK internals aren't accessible */
  private static Integer gcListenerCount() {
    int count = 0;
    for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
      if (!(gcBean instanceof NotificationEmitter)) continue;
      try {
        Field field = findField(gcBean.getClass(), "listenerList");
        if (field == null) return null;
        field.setAccessible(true);
        Object listeners = field.get(gcBean);
        if (listeners instanceof List) {
          count += ((List<?>) listeners).size();
        }
      } catch (RuntimeException | IllegalAccessException e) {
        // InaccessibleObjectException if the package isn't opened
        return null;
      }
    }
    return count;
  }

  private static Field findField(Class<?> clazz, String name) {
    for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
      try {
        return c.getDeclaredField(name);
      } catch (NoSuchFieldException e) {
        // try superclass
      }
    }
    return null;
  }
}
