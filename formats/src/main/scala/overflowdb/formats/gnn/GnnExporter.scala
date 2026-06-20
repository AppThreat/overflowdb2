package overflowdb.formats.gnn

import overflowdb.algorithm.GnnExporter as GnnArrays
import overflowdb.formats.{ExportResult, Exporter, resolveOutputFileSingle}
import overflowdb.{Edge, Graph, Node}

import java.io.BufferedWriter
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.IteratorHasAsScala
import scala.util.Using

/** Writes the graph as a small set of parallel arrays in JSON, ready to be loaded by a Graph Neural
  * Network pipeline. The layout mirrors the in-memory representation produced by
  * [[overflowdb.algorithm.GnnExporter]]: node ids and labels, then the source id, destination id
  * and label of every edge that stays within the exported node set.
  *
  * Only edges between exported nodes are emitted, so passing a slice or any other induced subgraph
  * produces a self-contained file with no dangling endpoints.
  */
object GnnExporter extends Exporter:
    override def defaultFileExtension = "json"

    override def runExport(graph: Graph, outputFile: Path): ExportResult =
        runExport(graph.nodes().asScala, Iterator.empty, outputFile)

    override def runExport(
      nodes: IterableOnce[Node],
      edges: IterableOnce[Edge],
      outputFile: Path
    ): ExportResult =
        val nodeList = new java.util.ArrayList[Node]()
        nodes.iterator.foreach(nodeList.add)
        val arrays  = GnnArrays.exportGraph(nodeList)
        val outFile = resolveOutputFileSingle(outputFile, s"export.$defaultFileExtension")

        Using.resource(Files.newBufferedWriter(outFile)) { writer =>
            writer.write("{")
            writeLongArray(writer, "nodeIds", arrays.nodeIds)
            writer.write(",")
            writeStringArray(writer, "nodeLabels", arrays.nodeLabels)
            writer.write(",")
            writeLongArray(writer, "edgeSrcIds", arrays.edgeSrcIds)
            writer.write(",")
            writeLongArray(writer, "edgeDstIds", arrays.edgeDstIds)
            writer.write(",")
            writeStringArray(writer, "edgeLabels", arrays.edgeLabels)
            writer.write("}")
            writer.newLine()
        }

        ExportResult(
          nodeCount = arrays.nodeIds.length,
          edgeCount = arrays.edgeSrcIds.length,
          files = Seq(outFile),
          additionalInfo = None
        )
    end runExport

    private def writeLongArray(writer: BufferedWriter, name: String, values: Array[Long]): Unit =
        writer.write(s"\"$name\":[")
        var i = 0
        while i < values.length do
            if i > 0 then writer.write(",")
            writer.write(values(i).toString)
            i += 1
        writer.write("]")

    private def writeStringArray(
      writer: BufferedWriter,
      name: String,
      values: Array[String]
    ): Unit =
        writer.write(s"\"$name\":[")
        var i = 0
        while i < values.length do
            if i > 0 then writer.write(",")
            writer.write(escape(values(i)))
            i += 1
        writer.write("]")

    private def escape(value: String): String =
        val sb = new StringBuilder(value.length + 2)
        sb.append('"')
        var i = 0
        while i < value.length do
            value.charAt(i) match
                case '"'  => sb.append("\\\"")
                case '\\' => sb.append("\\\\")
                case '\n' => sb.append("\\n")
                case '\r' => sb.append("\\r")
                case '\t' => sb.append("\\t")
                case c    => sb.append(c)
            i += 1
        sb.append('"')
        sb.toString
end GnnExporter
