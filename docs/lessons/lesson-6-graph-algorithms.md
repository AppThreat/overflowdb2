# Lesson 6: Graph Algorithmic Analysis

### Learning Objective

Master and apply the graph-theoretic and utility algorithms built into OverflowDB to solve complex compilation, static analysis, security auditing, and performance optimization problems.

### Pre-requisites

To follow this lesson, ensure the following software is installed on your system:

- **JDK 23+**: Standard OpenJDK or GraalVM.
- **SBT 1.10+**: Standard build utility.
- **Local clone of OverflowDB**: Clone the [overflowdb2 repository](https://github.com/AppThreat/overflowdb2) and run `sbt compile`.

---

## Overview of Algorithms

The `traversal` module of OverflowDB provides a suite of algorithms under the [overflowdb.algorithm](https://github.com/AppThreat/overflowdb2/tree/main/traversal/src/main/scala/overflowdb/algorithm) package. These algorithms operate directly on the graph structure using generic interfaces, allowing analysis of arbitrary subgraphs without duplicating data.

The following sections provide a detailed analysis, practical compiler/security context, and realistic Scala/Java examples for each of the ten algorithms.

---

## 1. PageRank Centrality

### Technical Description

PageRank measures node importance by recursively calculating the probability of arriving at a node when following random edges. It distributes weights across outgoing edges. The class also provides an `inDegree` utility to count incoming edges.

### Compiler & Security Applications

- **Prioritizing Security Reviews**: Rank methods based on PageRank in the call graph. Highly ranked methods represent critical execution hubs (such as dispatcher methods or shared security utilities) that must be audited first.
- **Compiler Hotspots**: Identify performance-critical bottlenecks by detecting heavily called helper methods.

### Code Example

```scala
import overflowdb.Node
import overflowdb.algorithm.PageRank
import scala.jdk.CollectionConverters.*

// Select all methods as the universe
val nodes: java.util.Collection[Node] = graph.nodes("Method").asScala.toSeq.asJavaCollection
val getSuccessors = new java.util.function.Function[Node, java.util.Iterator[Node]] {
  override def apply(node: Node): java.util.Iterator[Node] = {
    // Follow outgoing CALL edges to trace invocations
    node.out("CALL").asScala.asJava
  }
}

// Compute PageRank with default damping (0.85), max iterations (100), and tolerance (1.0e-6)
val ranks = PageRank.compute(nodes, getSuccessors)
ranks.asScala.toSeq.sortBy(-_._2).take(10).foreach { case (nodeId, score) =>
  val method = graph.node(nodeId)
  println(s"Method: ${method.property("FULL_NAME")} | Score: $score")
}
```

---

## 2. Topological Sort

### Technical Description

TopologicalSort uses Kahn's algorithm to establish a linear ordering of vertices in a Directed Acyclic Graph (DAG) such that for every directed edge $u \to v$, node $u$ comes before node $v$. If the graph contains cycles, it throws a `CycleDetectedException`.

### Compiler & Security Applications

- **Pass Scheduling**: Sequence compiler optimization passes that have dependency requirements.
- **Basic Block Sequencing**: Arrange CFG basic blocks to optimize instruction layout and cache efficiency.
- **Data Flow Slicing**: Process statements in topological order to compute data dependencies in a single forward pass.

### Code Example

```scala
import overflowdb.Node
import overflowdb.algorithm.TopologicalSort
import scala.jdk.CollectionConverters.*

val declarationNodes: java.util.Collection[Node] = method.astChildren.collect { case n: Node => n }.toSeq.asJavaCollection
val getDependencies = new java.util.function.Function[Node, java.util.Iterator[Node]] {
  override def apply(node: Node): java.util.Iterator[Node] = {
    // Follow AST dependency edges (child relies on parent definition)
    node.out("DEPENDS_ON").asScala.asJava
  }
}

try {
  val sorted = TopologicalSort.sort(declarationNodes, getDependencies)
  sorted.asScala.foreach(node => println(s"Process Definition: ${node.property("NAME")}"))
} catch {
  case _: TopologicalSort.CycleDetectedException =>
    println("Circular dependencies detected. Compilation failed.")
}
```

---

## 3. Dominator & Post-Dominator Trees

### Technical Description

The DominatorTree class computes dominator and post-dominator trees using the Lengauer-Tarjan algorithm. Node $A$ dominates node $B$ ($A$ dom $B$) if every path from the entry node to $B$ must pass through $A$. Node $A$ immediately dominates $B$ ($A$ idom $B$) if it is the closest dominator of $B$.

### Compiler & Security Applications

- **Loop Identification**: Detect loop headers by finding back-edges (CFG edges from a node to one of its dominators).
- **Control Dependence Analysis**: Compute the Control Dependence Graph (CDG) to map branch influences.
- **Decompilation**: Reconstruct high-level control structures (if-else statements, while loops) from flat bytecode or assembly control flows.

### Code Example

```scala
import overflowdb.Node
import overflowdb.algorithm.DominatorTree
import scala.jdk.CollectionConverters.*

val entryBlock: Node = method.entryBlock
val getCFGSuccessors = new java.util.function.Function[Node, java.util.Iterator[Node]] {
  override def apply(node: Node): java.util.Iterator[Node] = {
    node.out("CFG").asScala.asJava
  }
}

// Compute immediate dominators (idom) mapping node ID to immediate dominator node ID
val dominators = DominatorTree.computeDominators(entryBlock, getCFGSuccessors)
dominators.asScala.foreach { case (nodeId, domId) =>
  println(s"Node ID $nodeId is immediately dominated by Node ID $domId")
}
```

---

## 4. Strongly Connected Components (SCC)

### Technical Description

StronglyConnectedComponents groups nodes into components where every vertex is reachable from any other vertex in the same component. It is commonly used to find cycles and mutually recursive structures.

### Compiler & Security Applications

- **Handling Recursion**: Mutually recursive methods complicate static interprocedural analysis. Grouping these methods into strongly connected components allows you to collapse recursive cycles into single nodes, turning the call graph into a DAG for topological sequencing.
- **Dead Code Detection**: Locate code clusters that are unreachable from the entry component.

### Code Example

```scala
import overflowdb.Node
import overflowdb.algorithm.StronglyConnectedComponents
import scala.jdk.CollectionConverters.*

val callGraphNodes: java.util.Collection[Node] = graph.nodes("Method").asScala.toSeq.asJavaCollection
val getCallSuccessors = new java.util.function.Function[Node, java.util.Iterator[Node]] {
  override def apply(node: Node): java.util.Iterator[Node] = {
    node.out("CALL").asScala.asJava
  }
}

val components = StronglyConnectedComponents.compute(callGraphNodes, getCallSuccessors)
components.asScala.filter(_.size() > 1).foreach { recursiveSet =>
  val names = recursiveSet.asScala.map(_.property("NAME")).mkString(", ")
  println(s"Mutually recursive method group: [$names]")
}
```

---

## 5. PathFinder (Shortest Paths)

### Technical Description

PathFinder implements Breadth-First Search (BFS) and Dijkstra algorithms to compute the shortest path or all possible paths between a source node and a destination node.

### Compiler & Security Applications

- **Reachability Verification**: Verify if untrusted inputs can reach sensitive methods.
- **Data Flow Trace Extraction**: Extract the sequence of statement assignments linking a source parameter to a database query sink.

### Code Example

```scala
import overflowdb.Node
import overflowdb.algorithm.PathFinder
import scala.jdk.CollectionConverters.*

val source: Node = paramNode
val target: Node = dbSinkNode
val getDDGSuccessors = (node: Node) => node.out("DDG").asScala

// Find the shortest path using DDG edges
val shortestPathOption = PathFinder.shortestPath(source, target, getDDGSuccessors)
shortestPathOption match {
  case Some(path) =>
    println(s"Data flow path found! Hops: ${path.size}")
    path.foreach(node => println(s"  -> ${node.property("CODE")}"))
  case None =>
    println("No data flow path exists between source and target.")
}
```

---

## 6. Context-Sensitive Path Finder

### Technical Description

ContextSensitivePathFinder performs path routing while keeping track of the call-stack context. It ensures that when a path enters a method via call-site $C$, it only returns to the caller through call-site $C$ rather than branching into other callers.

### Compiler & Security Applications

- **Precise Taint Tracking**: Context-insensitive data-flow tracking causes false positives by mapping invalid flows that enter a shared helper method from one caller and return to a different caller. Context sensitivity enforces correct call-return alignment.

### Code Example

```scala
import overflowdb.Node
import overflowdb.algorithm.ContextSensitivePathFinder
import scala.jdk.CollectionConverters.*

val source: Node = entryPointNode
val target: Node = sinkNode

// Map outgoing interprocedural edges while matching calling context frames
val path = ContextSensitivePathFinder.findPath(
  source,
  target,
  (node: Node) => node.out("CALL_CFG").asScala.iterator
)
path.asScala.foreach(node => println(s"Context flow node: ${node.id} (${node.label})"))
```

---

## 7. Lowest Common Ancestors (LCA)

### Technical Description

LCA finds the lowest (deepest) node in a tree structure that has both node $A$ and node $B$ as descendants.

### Compiler & Security Applications

- **Type Hierarchy Resolution**: In object-oriented languages, find the lowest common parent class of two types to determine the type of a conditional ternary expression.
- **Variable Scoping**: Find the common AST block node containing two variable references to determine their nearest enclosing scope.

### Code Example

```scala
import overflowdb.Node
import overflowdb.algorithm.LowestCommonAncestors
import scala.jdk.CollectionConverters.*

val nodeA: Node = identifierA
val nodeB: Node = identifierB

// Find the lowest common parent in the AST hierarchy
val lcaNodeOption = LowestCommonAncestors.find(
  graph,
  nodeA,
  nodeB,
  (node: Node) => node.in("AST").asScala.headOption.iterator // Fetch AST parent
)
lcaNodeOption match {
  case Some(lca) =>
    println(s"Lowest common AST ancestor: ${lca.label} | Code: ${lca.property("CODE")}")
  case None =>
    println("No common AST ancestor found.")
}
```

---

## 8. Union Find (Disjoint Set)

### Technical Description

UnionFind implements a disjoint-set data structure. It supports two operations: union (merging two subsets) and find (determining which subset a particular element belongs to), optimized with path compression.

### Compiler & Security Applications

- **Alias Analysis**: Track variable aliases. When variable $X$ is assigned to $Y$, perform a `union` on their sets. Querying if two variables are aliases becomes a fast `find` operation.
- **Equivalence Class Computation**: Group program variables by typing structures or layout alignments.

### Code Example

```scala
import overflowdb.Node
import overflowdb.algorithm.UnionFind

val uf = new UnionFind[Node]()

// Initialize elements
uf.add(varA)
uf.add(varB)
uf.add(varC)

// Perform union: alias varA and varB
uf.union(varA, varB)

// Query if varA and varB belong to the same alias set
if (uf.find(varA) == uf.find(varB)) {
  println("Variables varA and varB are aliases.")
}
```

---

## 9. Dependency Sequencer

### Technical Description

DependencySequencer evaluates task items and their dependency graphs, returning a sequence of groups that can be executed in order. Items in the same group have no mutual dependencies and can be run in parallel.

### Compiler & Security Applications

- **Parallel Build Systems**: Order file compilation tasks such that files with no dependencies compile first, maximizing parallel CPU core usage.
- **Pass Optimization**: Group and run independent AST passes concurrently.

### Code Example

```scala
import overflowdb.algorithm.DependencySequencer

case class Task(name: String, dependencies: Seq[Task])

val taskA = Task("Compile Lexer", Seq())
val taskB = Task("Compile Parser", Seq(taskA))
val taskC = Task("Generate AST", Seq(taskB))
val taskD = Task("Run Linter", Seq(taskA))

val tasks = Seq(taskC, taskD)
val sequencer = new DependencySequencer[Task](tasks, _.dependencies)

// Get execution phases
val executionPhases: Seq[Seq[Task]] = sequencer.sequence
executionPhases.zipWithIndex.foreach { case (phaseTasks, index) =>
  println(s"Phase $index (Execute in Parallel): ${phaseTasks.map(_.name).mkString(", ")}")
}
```

---

## 10. Asynchronous Prefetcher

### Technical Description

AsynchronousPrefetcher pre-loads adjacent nodes of active traversals in a background worker thread. When nodes are evicted by the `ReferenceManager` to manage memory, the prefetcher reads them back from disk before the main thread requests them, avoiding I/O blocking.

### Compiler & Security Applications

- **Large Graph Traversal Speedups**: Accelerate interprocedural analysis on massive codebases by overlapping graph traversal computations with disk I/O.

### Code Example

```scala
import overflowdb.algorithm.AsynchronousPrefetcher
import overflowdb.Node
import scala.jdk.CollectionConverters.*

val activeNodes: java.util.Iterator[Node] = graph.nodes().iterator()

// Set up the prefetcher to fetch adjacent "AST" children on background threads
val prefetcher = new AsynchronousPrefetcher(
  activeNodes,
  (node: Node) => node.out("AST").iterator()
)

while (prefetcher.hasNext) {
  val node = prefetcher.next()
  // Process node with minimal disk read latency
  println(s"Processing Node: ${node.id}")
}
```
