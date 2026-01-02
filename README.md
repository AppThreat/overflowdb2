# overflowdb2

**overflowdb2** is a high-performance, low-footprint graph database designed specifically for storing and traversing **Code Property Graphs (CPGs)**, Abstract Syntax Trees (ASTs), and Control Flow Graphs (CFGs).

It operates as a hybrid in-memory/on-disk graph store. It attempts to keep the graph in Heap for maximum traversal speed but automatically "overflows" (swaps) unused nodes to disk (H2 MVStore) when Heap pressure rises, allowing the processing of codebases significantly larger than available RAM.

## Key Features

- **Hybrid Storage Model:** Seamlessly transitions between `NodeRef` (lightweight pointer, <32 bytes) and `NodeDb` (full property container) based on memory pressure.
- **Schema-Aware Compression:** Optimized specifically for graph domains where edge labels and property keys are repetitive (common in ASTs).
- **Zero-Allocation Serialization:** Custom MsgPack-based serialization pipeline designed to eliminate GC pressure during disk I/O.
- **Strictly Typed:** Java 21+ / Scala 3.6+ support.

## Architecture & Optimizations

This library has undergone rigorous optimization to support massive graphs (10M+ nodes) on standard hardware.

### 1. Storage & Memory Management

- **Off-Heap String Dictionary:**
  - _Problem:_ ASTs contain millions of repetitive strings (identifiers, operators, types). Storing a reverse-lookup map in Heap caused `OutOfMemoryErrors`.
  - _Solution:_ We implemented a bi-directional index backed by **H2 MVStore**. String-to-Int mapping happens in memory, but Int-to-String (reverse lookup) is offloaded to disk. This reduces Heap usage by ~40% for large CPGs.
- **Weak Interning:**
  - String properties are interned using **Guava's `WeakInterner`**. Unlike standard `ConcurrentHashMap` caching, this allows the Garbage Collector to reclaim string memory when nodes are unloaded, preventing long-term memory leaks during batch processing.
- **Cache Sizing:** H2 MVStore cache is capped (default 256MB) to prevent the backing store from competing with the graph heap space.

### 2. Zero-Allocation Serialization

- **Two-Pass Write Strategy:**
  - _Problem:_ Standard serialization creates temporary `ArrayList`s and `HashMap`s to organize properties before writing, generating gigabytes of "garbage" objects.
  - _Solution:_ The `NodeSerializer` performs two passes. Pass 1 counts the bytes/edges to write headers. Pass 2 writes the data directly to the buffer. This results in **zero object allocations** during the serialization of edges and properties.
- **Primitive Boxing Elimination:** All serialization logic uses primitive streams and direct byte-packing to avoid boxing `long` IDs or `int` offsets.

### 3. Data Structures & Algorithms

- **$O(1)$ Batch Clearing:** The `ReferenceManager` uses a `ConcurrentLinkedQueue` instead of `ArrayList` for managing unloadable references, preventing $O(N)$ array-shift costs during batch evictions.
- **Smart Indexing:**
  - `NodesList` (the central node registry) uses "Stride-based" indexing and avoids full index reconstruction on node removal.
  - `IndexManager` performs property-aware deletion rather than full-index scanning when removing nodes.
- **Primitive Collections:** The `DiffTool` and internal maps utilize **Trove4j** (`TLongArrayList`, `TLongIntHashMap`) to reduce the memory overhead of Java's standard Collection framework by ~5x.

## Installation

**Requirements:** JDK 21+

```scala
// build.sbt
resolvers += Resolver.githubPackages("appthreat/overflowdb2")

libraryDependencies ++= Seq(
  "io.appthreat" %% "overflowdb2-core" % "2.2.0"
)
```

## Usage

### 1. Define Your Domain (Schema)

overflowdb2 relies on factories to create nodes efficiently.

```java
public class MethodNodeFactory extends NodeFactory<MethodNode> {
    public static final String LABEL = "METHOD";
    @Override
    public String forLabel() { return LABEL; }

    @Override
    public MethodNode createNode(Graph graph, long id, NodeRef<MethodNode> ref) {
        return new MethodNode(graph, id, ref);
    }

    // ... createNodeRef implementation ...
}
```

### 2. Graph Initialization & Configuration

Configure the graph for your specific workload.

```java
import overflowdb.Config;
import overflowdb.Graph;

Config config = Config.withDefaults()
    .withStorageLocation("/tmp/cpg.bin") // Enable disk overflow
    .withHeapPercentageThreshold(80)      // Start swapping to disk when Heap > 80%
    .withCacheSize(128);                  // 128MB Off-heap cache for MVStore

// Register your factories
Graph graph = Graph.open(
    config,
    List.of(new MethodNodeFactory(), new LiteralNodeFactory()),
    List.of(new AstEdgeFactory())
);
```

### 3. Working with Data

```java
// Create Nodes (Thread-safe ID generation)
Node method = graph.addNode("METHOD", "FULL_NAME", "main", "SIGNATURE", "void main()");

// Create Edges
Node body = graph.addNode("BLOCK");
method.addEdge("AST", body);

// Traversals (Zero-allocation iterators)
method.out("AST").forEachRemaining(child -> {
    System.out.println("Child label: " + child.label());
});

// Save and Close
graph.close(); // Persists to disk if storage location is set
```

## Performance Tuning

| Config Option               | Default    | Description                                                                                                                                                            |
| :-------------------------- | :--------- | :--------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `heapPercentageThreshold`   | `80`       | When JVM Heap usage exceeds this %, `ReferenceManager` begins clearing strong references to nodes, turning them into `NodeRef` (pointers) and persisting data to disk. |
| `cacheSize`                 | `256` (MB) | The amount of RAM H2 MVStore is allowed to use for off-heap caching of the string dictionary and raw node bytes.                                                       |
| `serializationStatsEnabled` | `false`    | Set to `true` to debug serialization throughput performance.                                                                                                           |

## Testing & Diffing

The library includes a highly optimized `DiffTool` for comparing two graphs, useful for regression testing compiler frontends.

```java
import overflowdb.util.DiffTool;

List<String> differences = DiffTool.compare(graphA, graphB);
if (!differences.isEmpty()) {
    differences.forEach(System.out::println);
}
```

## Algorithms

1. `overflowdb.algorithm.TopologicalSort` - This implementation uses Kahn's Algorithm. It uses TLongIntHashMap to track in-degrees, avoiding the overhead of boxing thousands of integers. It is designed to sort arbitrary subgraphs, not just the whole graph.
2. `overflowdb.algorithm.UnionFind` - This is a primitive-optimized Disjoint Set Union implementation. It uses TLongLongHashMap to map Node IDs to Parent IDs, completely avoiding Long object wrappers. This is significantly faster and smaller than a standard Java HashMap<Long, Long>.
3. `overflowdb.algorithm.HeapWalker` (Iterative Tree Walker) - This utility helps prevent StackOverflowError when traversing deep ASTs. It works as an Iterator, allowing it to be used seamlessly with Java streams or for-loops.
4. `overflowdb.algorithm.DependencySequencer` - Given a set of nodes and their dependencies, this calculates the optimal execution order, grouping independent items together. Useful for parallel task scheduling or build system dependency resolution. Returns a sequence of sets (Seq<Set<Node>>), where all nodes in a set can be processed in parallel.
5. `overflowdb.algorithm.LowestCommonAncestors` - Finds the lowest common ancestor(s) for an arbitrary set of nodes in a Directed Acyclic Graph (DAG). Essential for determining variable scopes, common control flow dominators, or the tightest enclosing block for a set of statements.
6. `overflowdb.algorithm.PathFinder` - A traversal utility to find paths between a source and a sink node. Supports depth limits to bound the search. Useful for taint analysis, reachability checks, and control flow verification.

### Example usage

```java
// 1. Safe AST Traversal (No StackOverflow)
HeapWalker walker = HeapWalker.forNode(methodRoot, "AST");
while(walker.hasNext()) {
    Node astNode = walker.next();
    process(astNode);
}

// 2. Pointer Analysis (Union Find)
UnionFind pointsTo = new UnionFind();
graph.nodes("ASSIGNMENT").forEachRemaining(assignment -> {
long leftId = assignment.out("LVALUE").next().id();
long rightId = assignment.out("RVALUE").next().id();
    pointsTo.union(leftId, rightId);
});

// 3. Scheduling Analysis (Topo Sort)
List<Node> tasks = new ArrayList<>();
graph.nodes("TASK").forEachRemaining(tasks::add);
try {
    List<Node> executionOrder = TopologicalSort.sort(tasks, n -> n.out("DEPENDS_ON"));
        executionOrder.forEach(this::execute);
} catch (CycleDetectedException e) {
    System.err.println("Circular dependency detected!");
}

// 4. Parallel Build Scheduling (Dependency Sequencer)
// Returns stages: [[A], [B, C], [D]] where B and C can run in parallel
var stages = DependencySequencer.apply(tasks, t -> t.out("DEPENDS_ON"));
for (var stage : stages) {
    runInParallel(stage);
}

// 5. Variable Scoping (Lowest Common Ancestor)
// Find the block that commonly encloses all usages of variable 'x'
Set<Node> usages = getUsages("x");
Set<Node> scope = LowestCommonAncestors.apply(usages, n -> n.in("AST"));

// 6. Reachability (Path Finder)
// Check if user input flows to sensitive sink
List<Path> paths = PathFinder.apply(userInputNode, sqlQueryNode);
if (!paths.isEmpty()) {
    reportVulnerability("SQL Injection detected");
}
```

## License

Apache 2.0
