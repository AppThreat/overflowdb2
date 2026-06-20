package overflowdb.formats.gexf

import overflowdb.formats.{ExportResult, Exporter, isList, resolveOutputFileSingle}
import overflowdb.{Edge, Element, Graph, Node}

import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable
import scala.jdk.CollectionConverters.IteratorHasAsScala
import scala.util.Using

/** Exporter for Gephi Graph Exchange XML Format (GEXF). Generates standards-compliant GEXF 1.2
  * files suitable for import into visualizers like Gephi.
  */
object GexfExporter extends Exporter:
    override def defaultFileExtension = "gexf"

    override def runExport(graph: Graph, outputFile: Path): ExportResult =
        write(graph.nodes().asScala.toSeq, graph.edges().asScala.toSeq, outputFile)

    /** Subgraph entry point: export only the given nodes and edges. The caller is responsible for
      * supplying a self-contained selection (edges whose endpoints are part of the node set).
      */
    override def runExport(
      nodes: IterableOnce[Node],
      edges: IterableOnce[Edge],
      outputFile: Path
    ): ExportResult =
        write(nodes.iterator.toSeq, edges.iterator.toSeq, outputFile)

    private def write(
      nodes: Seq[Node],
      edges: Seq[Edge],
      outputFile: Path
    ): ExportResult =
        val outFile = resolveOutputFileSingle(outputFile, s"export.$defaultFileExtension")
        val nodePropertyContextById    = mutable.Map.empty[String, PropertyContext]
        val edgePropertyContextById    = mutable.Map.empty[String, PropertyContext]
        val discardedListPropertyCount = new AtomicInteger(0)

        nodes.foreach(collectKeys(_, "node", nodePropertyContextById, discardedListPropertyCount))
        edges.foreach(collectKeys(_, "edge", edgePropertyContextById, discardedListPropertyCount))

        var nodeCount = 0
        var edgeCount = 0

        Using.resource(Files.newBufferedWriter(outFile)) { writer =>
            writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            writer.newLine()
            writer.write(
              "<gexf xmlns=\"http://www.gexf.net/1.2draft\" version=\"1.2\">"
            )
            writer.newLine()
            writer.write("    <meta>")
            writer.newLine()
            writer.write("        <creator>OverflowDB</creator>")
            writer.newLine()
            writer.write("    </meta>")
            writer.newLine()
            writer.write("    <graph defaultedgetype=\"directed\">")
            writer.newLine()

            writeAttributes(writer, "node", nodePropertyContextById)
            writeAttributes(writer, "edge", edgePropertyContextById)

            writer.write("        <nodes>")
            writer.newLine()
            nodes.foreach { node =>
                nodeCount += 1
                writeNode(writer, node, nodePropertyContextById)
            }
            writer.write("        </nodes>")
            writer.newLine()

            writer.write("        <edges>")
            writer.newLine()
            edges.foreach { edge =>
                val edgeId = edgeCount
                edgeCount += 1
                writeEdge(writer, edgeId, edge, edgePropertyContextById)
            }
            writer.write("        </edges>")
            writer.newLine()

            writer.write("    </graph>")
            writer.newLine()
            writer.write("</gexf>")
            writer.newLine()
        }

        val additionalInfo =
            Some(discardedListPropertyCount.get).filter(_ > 0).map { count =>
                s"warning: discarded $count list properties (because they are not supported by the gexf spec)"
            }

        ExportResult(nodeCount, edgeCount, Seq(outFile), additionalInfo)
    end write

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

    private def writeAttributes(
      writer: java.io.BufferedWriter,
      className: String,
      context: mutable.Map[String, PropertyContext]
    ): Unit =
        if context.nonEmpty then
            writer.write(s"""        <attributes class="$className">""")
            writer.newLine()
            context.foreach { case (key, PropertyContext(name, tpe)) =>
                writer.write(
                  s"""            <attribute id="$key" title="$name" type="$tpe"/>"""
                )
                writer.newLine()
            }
            writer.write("        </attributes>")
            writer.newLine()
    end writeAttributes

    private def writeNode(
      writer: java.io.BufferedWriter,
      node: Node,
      context: mutable.Map[String, PropertyContext]
    ): Unit =
        writer.write(s"""            <node id="${node.id}" label="${escapeXml(node.label)}">""")
        writer.newLine()
        writeDataEntries(writer, "node", node, context)
        writer.write("            </node>")
        writer.newLine()

    private def writeEdge(
      writer: java.io.BufferedWriter,
      edgeId: Int,
      edge: Edge,
      context: mutable.Map[String, PropertyContext]
    ): Unit =
        writer.write(
          s"""            <edge id="$edgeId" source="${edge.outNode.id}" target="${edge.inNode.id}" label="${escapeXml(
                edge.label
              )}">"""
        )
        writer.newLine()
        writeDataEntries(writer, "edge", edge, context)
        writer.write("            </edge>")
        writer.newLine()

    private def writeDataEntries(
      writer: java.io.BufferedWriter,
      prefix: String,
      element: Element,
      context: mutable.Map[String, PropertyContext]
    ): Unit =
        val it = element.propertiesMap.entrySet().iterator()
        if it.hasNext then
            writer.write("                <attvalues>")
            writer.newLine()
            while it.hasNext do
                val entry = it.next()
                if !isList(entry.getValue.getClass) then
                    val encodedName = s"${prefix}__${element.label}__${entry.getKey}"
                    val xmlEncoded  = escapeXml(entry.getValue.toString)
                    writer.write(
                      s"""                    <attvalue for="$encodedName" value="$xmlEncoded"/>"""
                    )
                    writer.newLine()
            writer.write("                </attvalues>")
            writer.newLine()
    end writeDataEntries

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
end GexfExporter
