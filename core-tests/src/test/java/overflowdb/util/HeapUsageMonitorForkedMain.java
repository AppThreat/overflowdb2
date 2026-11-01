package overflowdb.util;

import overflowdb.Config;
import overflowdb.Graph;
import overflowdb.Node;
import overflowdb.NodeRef;
import overflowdb.testdomains.simple.SimpleDomain;
import overflowdb.testdomains.simple.TestEdge;
import overflowdb.testdomains.simple.TestNode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Run in a forked JVM by {@link HeapUsageMonitorCollectorsTest}, with the collector under test. Prints one result line
 * starting with "RESULT ".
 *
 * <ul>
 *   <li>{@code monitor <threshold> <retainPercent>}: installs the monitor, keeps `retainPercent` of the heap
 *   reachable, then allocates garbage so that collections run. Prints whether the monitor reported the heap above
 *   `threshold`.</li>
 *   <li>{@code poll <threshold> <retainPercent>}: the same, with GC notifications ignored, as in a native image.</li>
 *   <li>{@code overflow <nodes>}: builds a graph with overflow enabled that does not fit the heap, and prints how many
 *   of its nodes were evicted to disk.</li>
 * </ul>
 */
public class HeapUsageMonitorForkedMain {
  /** Keeps retained data reachable. */
  static volatile Object retained;
  static volatile long sink;

  public static void main(String[] args) throws Exception {
    switch (args[0]) {
      case "monitor" -> monitor(Integer.parseInt(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3]), false);
      case "poll" -> monitor(Integer.parseInt(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3]), true);
      case "overflow" -> overflow(Integer.parseInt(args[1]), Integer.parseInt(args[2]));
      default -> throw new IllegalArgumentException(args[0]);
    }
    System.exit(0);
  }

  private static void monitor(int threshold, int retainPercent, int seconds, boolean pollOnly) throws Exception {
    final CountDownLatch triggered = new CountDownLatch(1);
    final AtomicInteger reported = new AtomicInteger(-1);
    HeapUsageMonitor monitor = HeapUsageMonitor.install(threshold, usedPercent -> {
      reported.compareAndSet(-1, usedPercent);
      triggered.countDown();
    }, pollOnly);

    long max = Runtime.getRuntime().maxMemory();
    List<byte[]> keep = new ArrayList<>();
    long target = max * retainPercent / 100;
    // small chunks: larger ones are humongous objects in G1 and Shenandoah, which take more than their size
    for (long kept = 0; kept < target; kept += 32 * 1024) keep.add(new byte[32 * 1024]);
    retained = keep;

    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    while (triggered.getCount() > 0 && System.nanoTime() < deadline) {
      // short-lived garbage: young collections, and full ones once the retained data is promoted
      for (int i = 0; i < 256; i++) sink += new byte[16 * 1024].length;
      if (Thread.interrupted()) break;
      triggered.await(1, TimeUnit.MILLISECONDS);
    }
    System.out.println(triggered.getCount() == 0 ? "RESULT triggered " + reported.get() : "RESULT not-triggered");
    monitor.close();
  }

  private static void overflow(int nodeCount, int intsPerNode) throws Exception {
    long evicted = 0;
    // MVStore keeps its page cache on the heap, 256 MB by default: more than this whole test heap
    try (Graph graph = SimpleDomain.newGraph(Config.withDefaults().withHeapPercentageThreshold(60).withCacheSize(4))) {
      List<Node> nodes = new ArrayList<>(nodeCount);
      Node previous = null;
      for (int i = 0; i < nodeCount; i++) {
        Node node = graph.addNode(TestNode.LABEL, TestNode.INT_LIST_PROPERTY, new int[intsPerNode], TestNode.STRING_PROPERTY, "node " + i);
        if (previous != null) previous.addEdge(TestEdge.LABEL, node);
        previous = node;
        nodes.add(node);
        if (System.getenv("ODB_PROBE_PROGRESS") != null && i % 20_000 == 0) {
          Runtime rt = Runtime.getRuntime();
          System.err.printf("progress %d nodes, heap used %d MB, stored %d%n", i, (rt.totalMemory() - rt.freeMemory()) >> 20,
              graph.getStorage().getNodesMVMap().size());
        }
      }
      for (Node node : nodes) if (((NodeRef<?>) node).isCleared()) evicted++;
      // every node is still readable, from memory or from disk
      long found = 0;
      for (Node node : nodes) if (("node " + node.id()).equals(node.property(TestNode.STRING_PROPERTY))) found++;
      System.out.println("RESULT evicted " + evicted + " readable " + found);
    }
  }
}
