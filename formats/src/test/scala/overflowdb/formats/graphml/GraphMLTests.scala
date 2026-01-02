package overflowdb.formats.graphml

import better.files.File
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import overflowdb.testdomains.gratefuldead.GratefulDead
import overflowdb.testdomains.simple.{FunkyList, SimpleDomain, TestEdge, TestNode}
import overflowdb.util.DiffTool

import java.nio.file.Paths
import scala.jdk.CollectionConverters.{CollectionHasAsScala, IterableHasAsJava}

class GraphMLTests extends AnyWordSpec {

    "import minified gratefuldead graph" in {
        val graph = GratefulDead.newGraph()
        graph.nodeCount() shouldBe 0

        GraphMLImporter.runImport(graph, Paths.get(getClass.getResource("/graphml-small.xml").toURI))
        graph.nodeCount() shouldBe 3
        graph.edgeCount() shouldBe 2

        val node1 = graph.node(1)
        node1.label() shouldBe "song"
        val node340 = node1.out("sungBy").next()
        val node527 = node1.out("writtenBy").next()

        node340.label shouldBe "artist"
        node340.property("name") shouldBe "Garcia"
        node340.out().hasNext shouldBe false
        node340.in().hasNext shouldBe true

        node527.label shouldBe "artist"
        node527.property("name") shouldBe "Bo_Diddley"
        node527.out().hasNext shouldBe false
        node527.in().hasNext shouldBe true

        graph.close()
    }

    "Exporter should export valid xml" when {
        "not using (unsupported) list properties" in {
            val graph = SimpleDomain.newGraph()

            val node2 = graph.addNode(2, TestNode.LABEL, TestNode.STRING_PROPERTY, "stringProp2")
            val node3 = graph.addNode(3, TestNode.LABEL, TestNode.INT_PROPERTY, 13)

            val node1 = graph.addNode(1, TestNode.LABEL, TestNode.INT_PROPERTY, 11, TestNode.STRING_PROPERTY, "<stringProp1>")

            node1.addEdge(TestEdge.LABEL, node2, TestEdge.LONG_PROPERTY, Long.MaxValue)
            node2.addEdge(TestEdge.LABEL, node3)

            File.usingTemporaryDirectory(getClass.getName) { exportRootDirectory =>
                val exportResult = GraphMLExporter.runExport(graph, exportRootDirectory.pathAsString)
                exportResult.nodeCount shouldBe 3
                exportResult.edgeCount shouldBe 2
                val Seq(graphMLFile) = exportResult.files

                // Round trip check
                val reimported = SimpleDomain.newGraph()
                GraphMLImporter.runImport(reimported, graphMLFile)

                val diff = DiffTool.compare(graph, reimported)
                withClue(s"Differences found: ${diff.asScala.mkString("\n")}") {
                    diff.size shouldBe 0
                }
            }
        }

        "using list properties" in {
            val graph = SimpleDomain.newGraph()

            // GraphML doesn't support lists standardly, so we expect them to be dropped
            val node1 = graph.addNode(
                1,
                TestNode.LABEL,
                TestNode.INT_PROPERTY,
                11,
                TestNode.STRING_PROPERTY,
                "<stringProp1>",
                TestNode.STRING_LIST_PROPERTY,
                List("stringListProp1a", "stringListProp1b").asJava,
                TestNode.INT_LIST_PROPERTY,
                List(21, 31, 41).asJava
            )

            File.usingTemporaryDirectory(getClass.getName) { exportRootDirectory =>
                val exportResult = GraphMLExporter.runExport(graph, exportRootDirectory.pathAsString)
                exportResult.nodeCount shouldBe 1
                exportResult.edgeCount shouldBe 0

                // Ensure warning was logged in result
                val info = exportResult.additionalInfo.getOrElse("")
                info should include("discarded")
                info should include("list properties")

                val Seq(graphMLFile) = exportResult.files

                // Round trip check - expect differences
                val reimported = SimpleDomain.newGraph()
                GraphMLImporter.runImport(reimported, graphMLFile)

                val diff = DiffTool.compare(graph, reimported)
                val diffString = diff.asScala.mkString("\n")

                // We expect differences regarding the missing list properties
                diff.size should be > 0
                diffString should include("property 'StringListProperty'")
                diffString should include("property 'IntListProperty'")
            }
        }
    }

}