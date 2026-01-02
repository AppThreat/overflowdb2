package overflowdb.formats.graphml

import overflowdb.formats.{ExportResult, Exporter, isList, resolveOutputFileSingle, writeFile}
import overflowdb.{Edge, Element, Graph, Node}

import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable
import scala.jdk.CollectionConverters.MapHasAsScala
import scala.util.Using

object GraphMLExporter extends Exporter:
    override def defaultFileExtension = "xml"

    override def runExport(graph: Graph, outputFile: Path): ExportResult =
        val outFile = resolveOutputFileSingle(outputFile, s"export.$defaultFileExtension")
        val nodePropertyContextById    = mutable.Map.empty[String, PropertyContext]
        val edgePropertyContextById    = mutable.Map.empty[String, PropertyContext]
        val discardedListPropertyCount = new AtomicInteger(0)

        val nodeScan = graph.nodes()
        while nodeScan.hasNext do
            collectKeys(
              nodeScan.next(),
              "node",
              nodePropertyContextById,
              discardedListPropertyCount
            )
        val edgeScan = graph.edges()
        while edgeScan.hasNext do
            collectKeys(
              edgeScan.next(),
              "edge",
              edgePropertyContextById,
              discardedListPropertyCount
            )

        var nodeCount = 0
        var edgeCount = 0

        Using.resource(Files.newBufferedWriter(outFile)) { writer =>
            writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            writer.newLine()
            writer.write(
              "<graphml xmlns=\"http://graphml.graphdrawing.org/xmlns\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:schemaLocation=\"http://graphml.graphdrawing.org/xmlns http://graphml.graphdrawing.org/xmlns/1.0/graphml.xsd\">"
            )
            writer.newLine()

            writeKeyDefs(writer, "node", nodePropertyContextById)
            writeKeyDefs(writer, "edge", edgePropertyContextById)

            writer.write("    <graph id=\"G\" edgedefault=\"directed\">")
            writer.newLine()

            val nodes = graph.nodes()
            while nodes.hasNext do
                val node = nodes.next()
                nodeCount += 1
                writeNode(writer, node, nodePropertyContextById)

            val edges = graph.edges()
            while edges.hasNext do
                val edge = edges.next()
                edgeCount += 1
                writeEdge(writer, edge, edgePropertyContextById)

            writer.write("    </graph>")
            writer.newLine()
            writer.write("</graphml>")
        }

        val additionalInfo =
            Some(discardedListPropertyCount.get).filter(_ > 0).map { count =>
                s"warning: discarded $count list properties (because they are not supported by the graphml spec)"
            }

        ExportResult(nodeCount, edgeCount, Seq(outFile), additionalInfo)
    end runExport

    private def collectKeys(
      element: Element,
      prefix: String,
      context: mutable.Map[String, PropertyContext],
      discarded: AtomicInteger
    ): Unit =
        val it = element.propertiesMap.entrySet().iterator()
        while it.hasNext do
            val entry = it.next()
            if isList(entry.getValue.getClass) then
                discarded.incrementAndGet()
            else
                val encodedName = s"${prefix}__${element.label}__${entry.getKey}"
                if !context.contains(encodedName) then
                    context.put(
                      encodedName,
                      PropertyContext(entry.getKey, Type.fromRuntimeClass(entry.getValue.getClass))
                    )
    end collectKeys

    private def writeKeyDefs(
      writer: java.io.BufferedWriter,
      forAttr: String,
      context: mutable.Map[String, PropertyContext]
    ): Unit =
        writer.write(
          s"""    <key id="$KeyForNodeLabel" for="node" attr.name="$KeyForNodeLabel" attr.type="string"></key>"""
        )
        writer.newLine()
        writer.write(
          s"""    <key id="$KeyForEdgeLabel" for="edge" attr.name="$KeyForEdgeLabel" attr.type="string"></key>"""
        )
        writer.newLine()

        context.foreach { case (key, PropertyContext(name, tpe)) =>
            writer.write(
              s"""    <key id="$key" for="$forAttr" attr.name="$name" attr.type="$tpe"></key>"""
            )
            writer.newLine()
        }
    end writeKeyDefs

    private def writeNode(
      writer: java.io.BufferedWriter,
      node: Node,
      context: mutable.Map[String, PropertyContext]
    ): Unit =
        writer.write(s"""        <node id="${node.id}">""")
        writer.newLine()
        writer.write(s"""            <data key="$KeyForNodeLabel">${node.label}</data>""")
        writer.newLine()
        writeDataEntries(writer, "node", node, context)
        writer.write("        </node>")
        writer.newLine()

    private def writeEdge(
      writer: java.io.BufferedWriter,
      edge: Edge,
      context: mutable.Map[String, PropertyContext]
    ): Unit =
        writer.write(s"""        <edge source="${edge.outNode.id}" target="${edge.inNode.id}">""")
        writer.newLine()
        writer.write(s"""            <data key="$KeyForEdgeLabel">${edge.label}</data>""")
        writer.newLine()
        writeDataEntries(writer, "edge", edge, context)
        writer.write("        </edge>")
        writer.newLine()

    private def writeDataEntries(
      writer: java.io.BufferedWriter,
      prefix: String,
      element: Element,
      context: mutable.Map[String, PropertyContext]
    ): Unit =
        val it = element.propertiesMap.entrySet().iterator()
        while it.hasNext do
            val entry = it.next()
            if !isList(entry.getValue.getClass) then
                val encodedName = s"${prefix}__${element.label}__${entry.getKey}"
                val xmlEncoded  = escapeXml(entry.getValue.toString)
                writer.write(s"""            <data key="$encodedName">$xmlEncoded</data>""")
                writer.newLine()

    private def escapeXml(s: String): String =
        val sb = new StringBuilder(s.length)
        var i  = 0
        while i < s.length do
            s.charAt(i) match
                case '<'  => sb.append("&lt;")
                case '>'  => sb.append("&gt;")
                case '&'  => sb.append("&amp;")
                case '"'  => sb.append("&quot;")
                case '\'' => sb.append("&apos;")
                case c    => sb.append(c)
            i += 1
        sb.toString
end GraphMLExporter
