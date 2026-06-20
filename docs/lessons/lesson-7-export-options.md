# Lesson 7: Graph Exportation Formats and Options

### Learning Objective

Export code property graphs or method subgraphs into standard serialization formats, understanding GraphML, DOT, GraphSON, GEXF, GNN, and Neo4j CSV exporters.

### Pre-requisites

To follow this lesson, ensure the following software is installed on your system:

- **JDK 23+**: Standard OpenJDK or GraalVM.
- **SBT 1.10+**: Standard build utility.
- **Graph Visualization Software**: Visualizers like Gephi (GraphML/GEXF), Graphviz (DOT), or Cytoscape to view the exported graphs.
- **Neo4j instance (Optional)**: If testing bulk data ingestion using Neo4j CSV outputs.

### Conceptual Background

Exporting and importing graph representation states is handled by the `formats` module of OverflowDB. Rather than relying on generic JSON object serialization, which incurs high JVM object allocation costs, the exporters read property indices and write streams directly to file descriptors.

Exporters inherit their structure from [Exporter](https://github.com/AppThreat/overflowdb2/blob/main/formats/src/main/scala/overflowdb/formats/Exporter.scala) and support the following options:

1. **[DotExporter](https://github.com/AppThreat/overflowdb2/blob/main/formats/src/main/scala/overflowdb/formats/dot/DotExporter.scala)**: Formats elements into a DOT representation of a directed graph (`digraph`). It serializes nodes with attributes and encodes edge relationships with labels. Useful for CFG and AST visualization.
2. **[GraphMLExporter](https://github.com/AppThreat/overflowdb2/blob/main/formats/src/main/scala/overflowdb/formats/graphml/GraphMLExporter.scala)**: Exists as a standard XML format representing nodes, edges, keys, and values. Supports schema mapping where property types are declared in headers.
3. **[GexfExporter](https://github.com/AppThreat/overflowdb2/blob/main/formats/src/main/scala/overflowdb/formats/gexf/GexfExporter.scala)**: Generates XML conforming to the Gephi Graph Exchange XML Format (GEXF) 1.2 specification. Serializes node/edge labels and property attributes, automatically filtering out list types due to GEXF specification limits.
4. **[GraphSONExporter](https://github.com/AppThreat/overflowdb2/blob/main/formats/src/main/scala/overflowdb/formats/graphson/GraphSONExporter.scala)**: Formats elements to a TinkerPop-compliant JSON serialization. Useful for migrating graphs into Gremlin-compliant servers.
5. **[Neo4jCsvExporter](https://github.com/AppThreat/overflowdb2/blob/main/formats/src/main/scala/overflowdb/formats/neo4jcsv/Neo4jCsvExporter.scala)**: Emits CSV tables separated by labels. It splits the graph structure into node files (e.g. `nodes_Method.csv`) and relationship files (e.g. `edges_AST.csv`), complete with Neo4j import headers.
6. **[GnnExporter](https://github.com/AppThreat/overflowdb2/blob/main/traversal/src/main/scala/overflowdb/algorithm/GnnExporter.java)**: Exports nodes, edges, labels, and features into tensor-friendly matrices (adjacencies, label indices, property tensors) to train Graph Neural Networks (GNNs).

Export operations support two distinct scopes:

- **Whole Graph**: Writes the entire database contents to disk.
- **Subgraph Selection**: Iterates over a subset of nodes and edges (useful for exporting individual method structures).

### Real Commands and Code Examples

#### 1. Exporting the Whole Graph to GraphML

Save the entire in-memory graph to GraphML format:

```scala
import java.nio.file.Paths
import overflowdb.formats.graphml.GraphMLExporter

val outputPath = Paths.get("/tmp/entire_project.graphml")
val result = GraphMLExporter.runExport(graph, outputPath)

println(s"Exported ${result.nodeCount} nodes and ${result.edgeCount} edges.")
```

#### 2. Exporting an AST Subgraph to DOT

Export only the nodes and edges belonging to a single method's AST:

```scala
import java.nio.file.Paths
import overflowdb.Node
import overflowdb.formats.dot.DotExporter
import scala.jdk.CollectionConverters.*

// Collect nodes belonging to the method's AST
val astNodes: Seq[Node] = method.ast.l.map(_.asInstanceOf[Node])
val nodeIds = astNodes.map(_.id()).toSet

// Gather edges connecting only nodes within the AST selection
val astEdges = astNodes.flatMap(_.outE().asScala.filter(e => nodeIds.contains(e.inNode().id())))

val outputPath = Paths.get(s"/tmp/${method.name}_ast.dot")
DotExporter.runExport(astNodes, astEdges, outputPath)
```

#### 3. Exporting to Gephi GEXF format

Save the graph to GEXF format to load it directly in Gephi:

```scala
import java.nio.file.Paths
import overflowdb.formats.gexf.GexfExporter

val outputPath = Paths.get("/tmp/cpg_visualization.gexf")
val result = GexfExporter.runExport(graph, outputPath)

println(s"GEXF file written to ${outputPath.toAbsolutePath}")
result.additionalInfo.foreach(println) // Reports any discarded list properties
```

#### 4. Exporting to Neo4j CSV for Bulk Import

Write CSV files formatted for Neo4j's bulk import command:

```scala
import java.nio.file.Paths
import overflowdb.formats.neo4jcsv.Neo4jCsvExporter

val exportDir = Paths.get("/tmp/neo4j_import_csvs/")
val result = Neo4jCsvExporter.runExport(graph, exportDir)

result.files.foreach { csvFile =>
  println(s"Generated Neo4j CSV: ${csvFile.toAbsolutePath}")
}
```

#### 5. Exporting Graph Structure for GNN Training

Format the call graph topology as GNN inputs:

```scala
import java.nio.file.Paths
import overflowdb.algorithm.GnnExporter

val gnnOutPath = Paths.get("/tmp/gnn_features/")
GnnExporter.runExport(graph, gnnOutPath)
```
