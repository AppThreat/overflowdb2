package overflowdb.util;

import org.junit.Assume;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link HeapUsageMonitor} under every collector the JVM offers, each in a forked JVM with a small heap. Before, the
 * monitor matched collector bean names and action strings that only fit a G1 full collection, so eviction never
 * started under Serial (GraalVM native images use it), Parallel, ZGC or Shenandoah.
 */
public class HeapUsageMonitorCollectorsTest {
  private static final String[] COLLECTORS = {"G1", "Serial", "Parallel", "Z", "Shenandoah"};
  private static final int THRESHOLD = 50;

  /** ZGC and Shenandoah collect concurrently, so "after a collection" includes what was allocated during it; in a
   * heap below about 128 MB they thrash and report it full whatever is retained. */
  private static boolean concurrent(String gc) {
    return gc.equals("Z") || gc.equals("Shenandoah");
  }

  private static String heapFor(String gc) {
    return concurrent(gc) ? "256m" : "96m";
  }

  /** Concurrent collectors need headroom to collect while the test allocates. */
  private static String retainedAboveThreshold(String gc) {
    return concurrent(gc) ? "60" : "70";
  }

  @Test
  public void triggersAboveTheThresholdUnderEveryCollector() throws Exception {
    List<String> tested = new ArrayList<>();
    for (String gc : COLLECTORS) {
      if (!supported(gc)) continue;
      tested.add(gc);
      String result = run(gc, heapFor(gc), "monitor", "" + THRESHOLD, retainedAboveThreshold(gc), "30");
      assertTrue(gc + ": " + result, result.startsWith("triggered "));
      int reported = Integer.parseInt(result.substring("triggered ".length()));
      /* may be below the threshold: while the old generation grows after young collections, eviction starts halfway
       * between what was live and the limit, since Serial and Parallel reclaim it only once it is full */
      assertTrue(gc + " reported " + reported + "%", reported > 0 && reported <= 100);
    }
    assertTrue("G1 and Serial are always available", tested.contains("G1") && tested.contains("Serial"));
  }

  @Test
  public void staysQuietBelowTheThresholdUnderEveryCollector() throws Exception {
    for (String gc : COLLECTORS) {
      if (!supported(gc)) continue;
      // only short-lived garbage beyond 10% retained: never above 70% after a collection
      assertEquals(gc, "not-triggered", run(gc, heapFor(gc), "monitor", "70", "10", "3"));
    }
  }

  /** Without GC notifications, as in a GraalVM native image, polling the collection counts still triggers. */
  @Test
  public void triggersByPollingWithoutNotifications() throws Exception {
    for (String gc : new String[]{"Serial", "G1"}) {
      String result = run(gc, "96m", "poll", "" + THRESHOLD, "70", "30");
      assertTrue(gc + ": " + result, result.startsWith("triggered "));
    }
  }

  /** A graph larger than the heap is built under every collector: nodes are evicted to disk and all of them stay
   * readable. Before, eviction never started (or, under G1, too late) and every run ran out of heap. Two heap sizes:
   * at 256 MB, Serial's first full collection came just under the limit, and the heap ran out before the next. */
  @Test
  public void graphLargerThanTheHeapOverflowsUnderEveryCollector() throws Exception {
    for (String gc : COLLECTORS) {
      if (!supported(gc)) continue;
      // about 240 MB of nodes in memory (serialized about 400 bytes each); main runs out of memory in both heaps
      for (String heap : new String[]{"192m", "256m"}) {
        String result;
        try {
          result = run(gc, heap, "overflow", "400000", "100");
        } catch (AssertionError first) {
          /* one retry: under x64 emulation on Windows ARM64 eviction is slower than allocation in this tight loop,
           * and a run out of 3 to 5 ran out of memory. main fails every run. */
          System.err.println(gc + " " + heap + " overflow failed once, retrying: " + first.getMessage().lines().findFirst().orElse(""));
          result = run(gc, heap, "overflow", "400000", "100");
        }
        String[] parts = result.split(" ");
        assertEquals(gc + " " + heap + ": " + result, "evicted", parts[0]);
        assertTrue(gc + " " + heap + ": " + result, Long.parseLong(parts[1]) > 100_000);
        assertEquals(gc + " " + heap + ": " + result, "400000", parts[3]);
      }
    }
  }

  private static boolean supported(String gc) throws Exception {
    Process process = new ProcessBuilder(javaExecutable(), "-XX:+Use" + gc + "GC", "-version").redirectErrorStream(true).start();
    process.getInputStream().readAllBytes();
    return process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0;
  }

  private static String javaExecutable() {
    return Paths.get(System.getProperty("java.home"), "bin", "java").toString();
  }

  /** The test JVM's classpath. sbt runs tests in a class loader of its own, so java.class.path is not it. */
  private static String classpath() {
    List<String> entries = new ArrayList<>();
    ClassLoader loader = HeapUsageMonitorCollectorsTest.class.getClassLoader();
    for (ClassLoader l = loader; l != null; l = l.getParent()) {
      if (l instanceof URLClassLoader urlLoader) {
        for (URL url : urlLoader.getURLs()) {
          try {
            entries.add(new File(url.toURI()).getPath());
          } catch (Exception e) {
            entries.add(url.getPath());
          }
        }
      }
    }
    entries.add(System.getProperty("java.class.path"));
    return String.join(File.pathSeparator, entries);
  }

  private static String run(String gc, String heap, String... args) throws IOException, InterruptedException {
    List<String> command = new ArrayList<>(List.of(javaExecutable(), "-XX:+Use" + gc + "GC", "-Xmx" + heap,
        "-cp", classpath(), HeapUsageMonitorForkedMain.class.getName()));
    command.addAll(List.of(args));
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    StringBuilder output = new StringBuilder();
    String result = null;
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        output.append(line).append('\n');
        if (line.startsWith("RESULT ")) result = line.substring("RESULT ".length());
      }
    }
    if (!process.waitFor(120, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      fail(gc + " " + String.join(" ", args) + ": timed out\n" + output);
    }
    if (process.exitValue() != 0 || result == null) {
      fail(gc + " " + String.join(" ", args) + ": exit " + process.exitValue() + "\n" + output);
    }
    return result;
  }
}
