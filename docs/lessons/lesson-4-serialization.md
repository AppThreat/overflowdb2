# Lesson 4: Graph Importers and Exporters

### Learning Objective

Leverage the serialization module of OverflowDB to import and export graphs using GraphML, DOT, GraphSON, Neo4j CSV, or GNN input files.

### Pre-requisites

To follow this lesson, ensure the following software is installed on your system:

- **JDK 23+**: Standard OpenJDK or GraalVM.
- **SBT 1.10+**: Standard build utility.
- **Graph Tools**: Graphviz (`dot`), Gephi, or Cytoscape to view the exported graphs.

### Conceptual Background

Interoperability with external graph engines and databases requires serialization formats. The `formats` module of OverflowDB provides encoders and decoders for streaming graphs without loading all elements into RAM.

Supported formats include:

- **GraphML**: Standard XML format for graph data exchange.
- **DOT**: Line-based format for graph visualization and layout rendering.
- **GraphSON**: JSON format compatible with the TinkerPop Gremlin stack.
- **Neo4j CSV**: Relational tables separated by labels, optimized for bulk loading.
- **GNN (Graph Neural Network)**: Structured tensor representations of nodes and edges, suitable for machine learning training.

Additionally, to ensure atomic updates when writing or loading files, OverflowDB uses a batched transaction mechanism through **[BatchedUpdate](https://github.com/AppThreat/overflowdb2/blob/main/core/src/main/java/overflowdb/BatchedUpdate.java)**, preventing half-written states.

### Real Commands

Export a graph structure to a GraphML file:

```scala
import java.nio.file.Paths
import overflowdb.formats.graphml.GraphMLExporter

val outPath = Paths.get("/tmp/cpg_graph.graphml")
GraphMLExporter.runExport(graph, outPath)
```

Export specific nodes and edges to a DOT file:

```scala
import java.nio.file.Paths
import overflowdb.formats.dot.DotExporter

val nodesList = graph.nodes().asScala.toSeq
val edgesList = nodesList.flatMap(_.outE().asScala)
val outPath = Paths.get("/tmp/method_ast.dot")

DotExporter.runExport(nodesList, edgesList, outPath)
```

### Code Example

The exporter processes nodes and edges sequentially. Below is a conceptual illustration of how the format exporters iterate over graph entities:

```scala
package overflowdb.formats

import java.nio.file.Path
import overflowdb.{Edge, Graph, Node}

trait Exporter {
  def defaultFileExtension: String

  def runExport(graph: Graph, output: Path): ExportResult = {
    val nodes = graph.nodes().asScala.toSeq
    val edges = nodes.flatMap(_.outE().asScala)
    runExport(nodes, edges, output)
  }

  def runExport(nodes: Seq[Node], edges: Seq[Edge], output: Path): ExportResult
}
```
