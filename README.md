# overflowdb2

**overflowdb2** is a high-performance, low-footprint graph database designed specifically for storing and traversing **Code Property Graphs (CPGs)**, Abstract Syntax Trees (ASTs), and Control Flow Graphs (CFGs).

It operates as a hybrid in-memory/on-disk graph store. It attempts to keep the graph in Heap for maximum traversal speed but automatically "overflows" (swaps) unused nodes to disk (H2 MVStore) when Heap pressure rises, allowing the processing of codebases significantly larger than available RAM.

## Key Features

- **Hybrid Storage Model:** Seamlessly transitions between `NodeRef` (lightweight pointer, <32 bytes) and `NodeDb` (full property container) based on memory pressure.
- **Schema-Aware Compression:** Optimized specifically for graph domains where edge labels and property keys are repetitive (common in ASTs).
- **Zero-Allocation Serialization:** Custom MsgPack-based serialization pipeline designed to eliminate GC pressure during disk I/O.
- **Dynamic String Glossary Interning:** Dynamically maps all schema-defined labels, properties, and edge types during graph startup to minimize MVStore lookup/write overhead.
- **Edge Property Fast-Paths:** Skips empty map metadata for property-less edges to achieve higher performance and lower storage footprints.

## Architecture and Optimizations

This library has undergone rigorous optimization to support massive graphs on standard hardware.

### 1. Storage and Memory Management

ASTs contain millions of repetitive strings. To address this, overflowdb2 uses a bi-directional index backed by H2 MVStore. String to integer mapping happens in memory, but integer to string mappings are offloaded to disk. String interning is used for string properties, allowing the garbage collector to reclaim memory when nodes are unloaded. An automatic GC-based memory monitor runs in the background and triggers asynchronous eviction of nodes to disk when heap usage after a collection cycle exceeds the configured threshold.

At startup, the schema's labels, properties, and edge keys are dynamically scanned and registered with the storage glossary ("Glossary Pre-initialization"). This avoids runtime write contention and lookup overheads on backing MVStore glossary maps.

### 2. Zero-Allocation Serialization and Deserialization

To eliminate garbage collector overhead, the serialization pipeline uses a two-pass write strategy that writes data directly to the message buffer. The deserialization pipeline is also optimized to extract values directly from the unpacker stream, which bypasses the creation of intermediate MsgPack wrapper objects. All packaging logic utilizes primitive streams to eliminate object boxing, and node property packing avoids intermediate map allocations.

### 3. Data Structures and Algorithms

Batch clearing of unloadable references uses a ConcurrentLinkedQueue instead of ArrayList to prevent index shifting costs during batch evictions. The central node registry utilizes stride-based indexing and avoids full index reconstruction on node removal. Internal maps and diffing structures utilize Trove primitive collections to reduce the memory overhead of standard Java collection frameworks.

## Installation

**Requirements:** JDK 23+

```scala
// build.sbt
resolvers += Resolver.githubPackages("appthreat/overflowdb2")

libraryDependencies ++= Seq(
  "io.appthreat" %% "overflowdb2-core" % "3.0.1"
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
    .withCacheSize(128)                  // 128MB Off-heap cache for MVStore
    .withStorageCompressionMode(Config.StorageCompressionMode.LZF);

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

## Traversal Steps

The Scala traversal DSL provides standard graph steps and optimized custom steps grouped by their purpose:

### 1. Navigation Steps

- `.out` or `.out(labels)`: Follow outgoing edges to adjacent nodes.
- `.in` or `.in(labels)`: Follow incoming edges to adjacent nodes.
- `.both` or `.both(labels)`: Follow both incoming and outgoing edges to adjacent nodes.
- `.outE` or `.outE(labels)`: Follow outgoing edges.
- `.inE` or `.inE(labels)`: Follow incoming edges.
- `.bothE` or `.bothE(labels)`: Follow both incoming and outgoing edges.

### 2. Filtering Steps

- `.hasOut(label)`: A zero-allocation filter step that keeps nodes containing at least one outgoing edge with the specified label, resolved directly in the storage engine without iterator allocations.
- `.hasIn(label)`: A zero-allocation filter step that keeps nodes containing at least one incoming edge with the specified label.
- `.hasId(values)` or `.id(values)`: Keep nodes with the specified IDs.
- `.hasLabel(labels)` or `.label(labels)`: Keep nodes with the specified labels.
- `.labelNot(labels)`: Discard nodes matching the specified labels.
- `.has(key)` or `.hasNot(key)`: Filter elements by existence or non-existence of a property.
- `.has(key, value)` or `.hasNot(key, value)`: Filter elements by property values.
- `.has(propertyPredicate)`: Filter elements using standard predicates like `P.eq`, `P.neq`, `P.within`.
- `.is(value)`: Keep elements that are equal to the specified value.
- `.within(set)` or `.without(set)`: Keep or discard elements present in the specified set.

### 3. Conditional and Routing Steps

- `.choose(on)(options)`: A routing step that enables conditional paths inside a single fluent traversal expression.
- `.where(subWalk)` or `.whereNot(subWalk)`: Look-ahead filter steps that preserve or discard the active elements depending on whether the sub-walk returns results.
- `.coalesce(options)`: Evaluates traversals in order and returns the first one that emits elements.

### 4. Transformation and Aggregation Steps

- `.map(fun)`: Transform each element.
- `.flatMap(fun)`: Transform and flatten elements.
- `.collectAll[B]`: Filter and collect elements matching the specified class.
- `.cast[B]`: Cast all elements to the specified type.
- `.dedup` or `.dedupBy(fun)`: Remove duplicate elements.
- `.sorted` or `.sortBy(fun)`: Sort elements.
- `.groupCount` or `.groupCount(fun)`: Group elements and count occurrences.
- `.groupBy(fun)` or `.groupMap(key)(fun)`: Group elements by a key or transform values.
- `.union(travs)`: Aggregate multiple traversal branches into one.

### 5. Path and Graph Walk Steps

- `.repeat(walk)(config)`: Recursively repeat the walk. Supported modulators include `.maxDepth`, `.until`, `.emit`, `.whilst`, `.breadthFirstSearch`.
- `.path`: Resolves the visited path tracking for each element in the traversal.
- `.neighborhood(maxDepth, direction)`: Returns all nodes reachable within the given maximum depth in the specified direction using cycle-safe breadth-first search.

### 6. Side Effect and Diagnostic Steps

- `.sideEffect(fun)` or `.sideEffectPF(pf)`: Execute a side effect on each element without altering the traversal.
- `.profile(name)`: Monitors execution time and count metrics for elements passing through the step, logging console statistics upon completion.

### 7. Materialization Steps

- `.l` or `.toList`: Execute the traversal and return a List.
- `.iterate()`: Execute the traversal strictly for side effects without returning anything.
- `.countTrav` or `.size`: Resolve the total number of elements.
- `.head` or `.headOption`: Return the first element.
- `.last` or `.lastOption`: Return the last element.

## Performance Tuning

| Config Option               | Default    | Description                                                                                                                                                               |
| :-------------------------- | :--------- | :------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `heapPercentageThreshold`   | `80`       | When JVM Heap usage exceeds this percentage, ReferenceManager begins clearing strong references to nodes, turning them into NodeRef pointers and persisting data to disk. |
| `cacheSize`                 | `256` (MB) | The amount of RAM H2 MVStore is allowed to use for off-heap caching of the string dictionary and raw node bytes.                                                          |
| `serializationStatsEnabled` | `false`    | Set to true to debug serialization throughput performance.                                                                                                                |
| `storageCompressionMode`    | `DEFLATE`  | The compression algorithm used for the persistent store. Options are NONE, LZF, and DEFLATE.                                                                              |

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

The library includes optimized implementations of graph algorithms designed for static analysis.

- `overflowdb.algorithm.TopologicalSort`: Performs topological sorting on subgraphs using Kahn's algorithm. It populates parent mappings and tracks in-degrees strictly for the input node universe, reducing memory overhead and preventing map footprint growth.
- `overflowdb.algorithm.UnionFind`: Implements a memory-efficient Disjoint Set Union structure using primitive long mappings. It uses Union by Rank to limit tree depth to logarithmic bounds and executes path compression iteratively to prevent stack overflow errors.
- `overflowdb.algorithm.HeapWalker`: Provides an iterative depth-first-search walker that replaces recursive stack frames with heap storage to safely navigate deep abstract syntax trees. It utilizes a reused internal child deque buffer to eliminate allocation and garbage collection pressure.
- `overflowdb.algorithm.DependencySequencer`: Groups dependent tasks into stages that can be run in parallel. It caches parent sets to ensure parent traversals are executed exactly once per node, avoiding repeated database queries.
- `overflowdb.algorithm.LowestCommonAncestors`: Computes lowest common ancestors in a directed acyclic graph. It optimizes execution time to linear scale by short-circuiting the ancestor intersection checks and computing sets of parents in a non-overlapping manner.
- `overflowdb.algorithm.PathFinder`: Searches for paths between nodes with configurable depth bounds, supporting reachability checks and data-flow tracking.
- `overflowdb.algorithm.DominatorTree`: Computes dominator and post-dominator trees using the Lengauer-Tarjan algorithm. It runs in near-linear time and is optimized with primitive Trove maps to avoid boxing, featuring an iterative DFS walk to prevent stack overflow.
- `overflowdb.algorithm.StronglyConnectedComponents`: Extracts strongly connected components using Tarjan's algorithm. It operates iteratively using custom state frames to remain stack-safe on large cycles.
- `overflowdb.algorithm.ContextSensitivePathFinder`: Performs context-sensitive path queries using open/close brackets logic to match calls and returns, eliminating invalid paths across call sites.
- `overflowdb.algorithm.AsynchronousPrefetcher`: Pre-loads evicted nodes from backing disk storage in background thread workers to eliminate blocking I/O during heavy traversals.
- `overflowdb.algorithm.GnnExporter`: Extracts subgraphs into parallel flat primitive arrays containing node IDs, edge source/destination pairs, and labels, designed for direct consumption by Graph Neural Network frameworks.

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

// 7. Dominator Trees (Lengauer-Tarjan)
Map<Long, Long> idoms = DominatorTree.computeDominators(entryNode, n -> n.out("CFG"));

// 8. Strongly Connected Components (Tarjan)
List<Set<Node>> sccs = StronglyConnectedComponents.compute(allNodes, n -> n.out("CFG"));

// 9. Context-Sensitive Path Finding
Optional<Path> csPath = ContextSensitivePathFinder.findPath(source, target, n -> getContextEdges(n), 10);

// 10. Asynchronous Pre-fetching
AsynchronousPrefetcher prefetcher = new AsynchronousPrefetcher(4);
prefetcher.prefetch(unloadedNodes);

// 11. GNN Subgraph Export
GnnExport gnnData = GnnExporter.exportGraph(subgraphNodes);
```

## License

Apache 2.0
