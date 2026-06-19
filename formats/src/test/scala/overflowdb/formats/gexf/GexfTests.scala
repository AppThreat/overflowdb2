package overflowdb.formats.gexf

import better.files.File
import org.scalatest.wordspec.AnyWordSpec
import overflowdb.testdomains.simple.{SimpleDomain, TestEdge, TestNode}

/**
 * Tests for the Gephi Graph Exchange XML Format (GEXF) Exporter.
 * Verifies XML schema declarations, element layout, and property serialization correctness.
 */
class GexfTests extends AnyWordSpec {

    "Exporter should export valid GEXF XML structure" when {
        "not using (unsupported) list properties" in {
            val graph = SimpleDomain.newGraph()

            val node2 = graph.addNode(2, TestNode.LABEL, TestNode.STRING_PROPERTY, "stringProp2")
            val node3 = graph.addNode(3, TestNode.LABEL, TestNode.INT_PROPERTY, 13)
            val node1 = graph.addNode(1, TestNode.LABEL, TestNode.INT_PROPERTY, 11, TestNode.STRING_PROPERTY, "<stringProp1>")

            node1.addEdge(TestEdge.LABEL, node2, TestEdge.LONG_PROPERTY, Long.MaxValue)
            node2.addEdge(TestEdge.LABEL, node3)

            File.usingTemporaryDirectory(getClass.getName) { exportRootDirectory =>
                val exportResult = GexfExporter.runExport(graph, exportRootDirectory.pathAsString)
                assert(exportResult.nodeCount == 3)
                assert(exportResult.edgeCount == 2)
                val Seq(gexfFile) = exportResult.files

                val xmlContent = File(gexfFile).contentAsString

                // Verify XML GEXF metadata/structure
                assert(xmlContent.contains("<gexf xmlns=\"http://www.gexf.net/1.2draft\" version=\"1.2\">"))
                assert(xmlContent.contains("<creator>OverflowDB</creator>"))
                assert(xmlContent.contains("<graph defaultedgetype=\"directed\">"))

                // Verify attributes definitions
                assert(xmlContent.contains("<attributes class=\"node\">"))
                assert(xmlContent.contains("<attribute id=\"node__testNode__StringProperty\" title=\"StringProperty\" type=\"string\"/>"))
                assert(xmlContent.contains("<attribute id=\"node__testNode__IntProperty\" title=\"IntProperty\" type=\"integer\"/>"))
                assert(xmlContent.contains("<attributes class=\"edge\">"))
                assert(xmlContent.contains("<attribute id=\"edge__testEdge__longProperty\" title=\"longProperty\" type=\"long\"/>"))

                // Verify nodes list
                assert(xmlContent.contains("<nodes>"))
                assert(xmlContent.contains("<node id=\"1\" label=\"testNode\">"))
                assert(xmlContent.contains("<node id=\"2\" label=\"testNode\">"))
                assert(xmlContent.contains("<node id=\"3\" label=\"testNode\">"))

                // Verify node property values
                assert(xmlContent.contains("<attvalue for=\"node__testNode__StringProperty\" value=\"&lt;stringProp1&gt;\"/>"))
                assert(xmlContent.contains("<attvalue for=\"node__testNode__IntProperty\" value=\"11\"/>"))

                // Verify edges list
                assert(xmlContent.contains("<edges>"))
                assert(xmlContent.contains("source=\"1\" target=\"2\" label=\"testEdge\""))
                assert(xmlContent.contains("source=\"2\" target=\"3\" label=\"testEdge\""))
                assert(xmlContent.contains("<attvalue for=\"edge__testEdge__longProperty\" value=\"9223372036854775807\"/>"))
            }
        }
    }
}
