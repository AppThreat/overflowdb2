# Lesson 5: CPG2 Integration and Schema Layout

### Learning Objective

Understand how the Code Property Graph (`cpg2` and `atom`) represents program semantics (AST, CFG, PDG) using the OverflowDB graph structure and indexing capabilities.

### Pre-requisites

To follow this lesson, ensure the following software is installed on your system:

- **JDK 23+**: Standard OpenJDK or GraalVM.
- **SBT 1.10+**: Standard build utility.
- **Clones of cpg2 and atom**: Have clones of [cpg2](https://github.com/AppThreat/cpg2) and [atom](https://github.com/AppThreat/atom) repositories compiled locally.

### Conceptual Background

The Code Property Graph (CPG) models multiple program structures within a single unified graph:

- **AST (Abstract Syntax Tree)**: Tracks block, statement, and expression containment using `AST` edges.
- **CFG (Control Flow Graph)**: Tracks execution paths using `CFG` edges.
- **PDG (Program Dependence Graph)**: Tracks data flows using `REACHING_DEF` or `DDG` edges.

These structures are built by compiler passes that modify the graph instance.

To resolve identifiers, look up method signatures, and query method definitions, the CPG needs indexing. OverflowDB uses **[IndexManager](https://github.com/AppThreat/overflowdb2/blob/main/core/src/main/java/overflowdb/IndexManager.java)** to build indices on node properties (such as method full names, variable identifiers, and file paths). When a node is updated or added to the graph, it is indexed, allowing $O(1)$ lookups during data-flow traversals.

### Real Commands

Index a custom property `FULL_NAME` on `Method` nodes:

```scala
graph.indexManager.createNodePropertyIndex(classOf[Method], "FULL_NAME")
```

Look up a node by its indexed full name:

```scala
val methods = graph.indexManager.lookupNodesWithPropertyValue(
  classOf[Method],
  "FULL_NAME",
  "io.appthreat.atom.Atom.main"
)
```

### Code Example

The compilation passes in `atom` write CFG connections between statement nodes. Below is a conceptual representation of linking control flow nodes:

```scala
package io.appthreat.atom.passes

import io.shiftleft.codepropertygraph.generated.nodes.{CfgNode, Method}
import overflowdb.Graph

class CfgBuilder(graph: Graph) {
  def linkControlFlow(src: CfgNode, dst: CfgNode): Unit = {
    // Write CFG edge from source statement to destination statement
    src.addEdge("CFG", dst)
  }

  def buildMethodCFG(method: Method): Unit = {
    val entries = method.astChildren.collect { case c: CfgNode => c }.toList
    for (i <- 0 until entries.size - 1) {
      linkControlFlow(entries(i), entries(i + 1))
    }
  }
}
```
