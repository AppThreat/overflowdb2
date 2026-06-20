# Lesson 1: Memory-Efficient Graph Architecture and Disk Overflow

### Learning Objective

Master the dual-representation memory architecture of OverflowDB, understanding the separation between `NodeRef` and `NodeDb`, layout optimizations in adjacency arrays, and the execution queue of the `ReferenceManager`.

### Pre-requisites

To follow this lesson, ensure the following software is installed on your system:

- **JDK 23+**: Standard OpenJDK or GraalVM.
- **SBT 1.10+**: Standard build utility for compiling the project.
- **Local clone of OverflowDB**: Clone the [overflowdb2 repository](https://github.com/AppThreat/overflowdb2) and run `sbt compile` in the root folder.

### Conceptual Background

In graph databases designed for static code analysis, graphs can exceed the available physical RAM. Traditional object-oriented graphs introduce significant garbage collection (GC) and pointer overhead. OverflowDB resolves this by employing a dual-class representation for nodes:

1. **[NodeRef](https://github.com/AppThreat/overflowdb2/blob/main/core/src/main/java/overflowdb/NodeRef.java)**: A lightweight reference object containing only the unique node ID and a pointer to the graph instance. NodeRefs are kept in memory and are cheap for the JVM to store and track.
2. **[NodeDb](https://github.com/AppThreat/overflowdb2/blob/main/core/src/main/java/overflowdb/NodeDb.java)**: The actual node containing properties, labels, and adjacency lists. When memory runs low, the contents of NodeDb are serialized to disk, leaving the NodeRef pointing to a cold state.

Memory reclamation and serialization are managed by the **[ReferenceManager](https://github.com/AppThreat/overflowdb2/blob/main/core/src/main/java/overflowdb/ReferenceManager.java)**. It monitors the heap memory usage. If memory usage exceeds the configured heap threshold (e.g. 90%), it runs eviction passes, identifying clean NodeDb instances, serializing dirty instances to disk, and replacing their in-memory pointers with empty references within their respective NodeRefs.

Additionally, to optimize CPU cache performance, adjacent nodes and edges are stored in flat, primitive arrays within **[AdjacentNodes](https://github.com/AppThreat/overflowdb2/blob/main/core/src/main/java/overflowdb/AdjacentNodes.java)** instead of standard Java collections (like ArrayList or HashMap). This minimizes memory overhead and prevents pointer chasing during traversals.

### Real Commands

Configure a graph instance with a disk cache storage location and a 90% heap threshold to activate auto-overflow:

```scala
import overflowdb.{Config, Graph}

val config = Config.withDefaults()
  .withStorageLocation("/tmp/overflow_cache.db")
  .withHeapPercentageThreshold(90)

val graph = Graph.open(config, domainNodeFactories, domainEdgeFactories)
```

### Code Example

The relationship between NodeRef and NodeDb is shown in the following lookup logic of `NodeRef.java`:

```java
public abstract class NodeRef<N extends NodeDb> implements Node {
  protected final long id;
  protected final Graph graph;
  private N nodeDb; // Cached reference to the heavy NodeDb instance

  public N get() {
    N ref = nodeDb;
    if (ref == null) {
      // If the node has been evicted, load it back from storage
      ref = (N) graph.storage.loadNode(id);
      nodeDb = ref;
      graph.referenceManager.register(this);
    }
    return ref;
  }
}
```
