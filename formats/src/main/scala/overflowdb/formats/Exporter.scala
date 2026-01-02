package overflowdb.formats

import java.nio.file.{Path, Paths}
import overflowdb.{Edge, Graph, Node}
import scala.jdk.CollectionConverters.IteratorHasAsScala

trait Exporter:

    def defaultFileExtension: String

    /** Main entry point for Exporters. Optimized exporters (Dot, GraphML, GraphSON) override this
      * to stream directly from the Graph. Legacy exporters (Neo4j) inherit this default
      * implementation which delegates to the Iterator version.
      */
    def runExport(graph: Graph, outputFile: Path): ExportResult =
        runExport(graph.nodes().asScala, graph.edges().asScala, outputFile)

    /** Legacy entry point. Legacy exporters override this. Optimized exporters inherit this default
      * implementation (which throws), as they are never called via this signature.
      */
    def runExport(
      nodes: IterableOnce[Node],
      edges: IterableOnce[Edge],
      outputFile: Path
    ): ExportResult =
        throw new UnsupportedOperationException(
          s"${this.getClass.getSimpleName} requires a Graph instance, not just iterators."
        )

    def runExport(graph: Graph, outputFile: String): ExportResult =
        runExport(graph, Paths.get(outputFile))
end Exporter

case class ExportResult(
  nodeCount: Int,
  edgeCount: Int,
  files: Seq[Path],
  additionalInfo: Option[String]
)
