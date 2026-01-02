package overflowdb.formats.dot

import overflowdb.formats.{ExportResult, Exporter, resolveOutputFileSingle}
import overflowdb.{Edge, Graph, Node}

import java.io.BufferedWriter
import java.nio.file.{Files, Path}
import scala.util.Using
import scala.jdk.CollectionConverters.IterableHasAsScala

object DotExporter extends Exporter:
    override def defaultFileExtension = "dot"

    override def runExport(graph: Graph, outputFile: Path): ExportResult =
        val outFile = resolveOutputFileSingle(outputFile, s"export.$defaultFileExtension")
        var nodeCount, edgeCount = 0

        Using.resource(Files.newBufferedWriter(outFile)) { writer =>
            writer.write("digraph {"); writer.newLine()

            val nodeIter = graph.nodes()
            while nodeIter.hasNext do
                val node = nodeIter.next()
                nodeCount += 1
                writeNode(writer, node)

            val edgeIter = graph.edges()
            while edgeIter.hasNext do
                val edge = edgeIter.next()
                edgeCount += 1
                writeEdge(writer, edge)

            writer.write("}")
            writer.newLine()
        }

        ExportResult(nodeCount, edgeCount, Seq(outFile), None)
    end runExport

    private def writeNode(writer: BufferedWriter, node: Node): Unit =
        writer.write(s"  ${node.id} [label=${node.label}")
        writeProperties(writer, node.propertiesMap)
        writer.write("]")
        writer.newLine()

    private def writeEdge(writer: BufferedWriter, edge: Edge): Unit =
        writer.write(s"  ${edge.outNode.id} -> ${edge.inNode.id} [label=${edge.label}")
        writeProperties(writer, edge.propertiesMap)
        writer.write("]")
        writer.newLine()

    private def writeProperties(
      writer: BufferedWriter,
      properties: java.util.Map[String, Object]
    ): Unit =
        if !properties.isEmpty then
            properties.forEach { (key, value) =>
                writer.write(" ")
                writer.write(key)
                writer.write("=")
                writer.write(encodePropertyValue(value))
            }

    private def encodePropertyValue(value: Object): String =
        value match
            case s: String =>
                escapeString(s)
            case l: java.lang.Iterable[?] =>
                val sb = new StringBuilder()
                sb.append('"')
                val it    = l.iterator()
                var first = true
                while it.hasNext do
                    if !first then sb.append(';')
                    sb.append(it.next().toString)
                    first = false
                sb.append('"')
                sb.toString
            case arr: Array[?] =>
                val sb = new StringBuilder()
                sb.append('"')
                var first = true
                for item <- arr do
                    if !first then sb.append(';')
                    sb.append(item.toString)
                    first = false
                sb.append('"')
                sb.toString
            case _ => value.toString

    private def escapeString(s: String): String =
        val sb = new StringBuilder(s.length + 2)
        sb.append('"')
        var i = 0
        while i < s.length do
            val c = s.charAt(i)
            if c == '"' then sb.append("\\\"")
            else if c == '\\' then sb.append("\\\\")
            else sb.append(c)
            i += 1
        sb.append('"')
        sb.toString

end DotExporter
