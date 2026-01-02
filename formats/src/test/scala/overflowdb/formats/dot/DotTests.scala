package overflowdb.formats.dot

import better.files._
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import overflowdb.testdomains.simple.{FunkyList, SimpleDomain, TestEdge, TestNode}

import scala.jdk.CollectionConverters.{CollectionHasAsScala, IterableHasAsJava}

class DotTests extends AnyWordSpec {

    "Exporter should export valid dot" in {
        val graph = SimpleDomain.newGraph()

        val node2 = graph.addNode(2, TestNode.LABEL, TestNode.STRING_PROPERTY, """string"Prop2\""")
        val node3 = graph.addNode(3, TestNode.LABEL, TestNode.INT_PROPERTY, 13)

        // only allows values defined in FunkyList.funkyWords
        val funkyList = new FunkyList()
        funkyList.add("apoplectic")
        funkyList.add("bucolic")
        val node1 = graph.addNode(
            1,
            TestNode.LABEL,
            TestNode.INT_PROPERTY,
            11,
            TestNode.STRING_PROPERTY,
            "<stringProp1>",
            TestNode.STRING_LIST_PROPERTY,
            List("stringListProp1a", "stringList\\Prop1b").asJava,
            TestNode.FUNKY_LIST_PROPERTY,
            funkyList,
            TestNode.INT_LIST_PROPERTY,
            List(21, 31, 41).asJava
        )

        node1.addEdge(TestEdge.LABEL, node2, TestEdge.LONG_PROPERTY, Long.MaxValue)
        node2.addEdge(TestEdge.LABEL, node3)

        File.usingTemporaryDirectory(getClass.getName) { exportRootDirectory =>
            val exportResult = DotExporter.runExport(graph, exportRootDirectory.pathAsString)
            exportResult.nodeCount shouldBe 3
            exportResult.edgeCount shouldBe 2
            val Seq(exportedFile) = exportResult.files

            val result = better.files.File(exportedFile).contentAsString.trim

            // Basic Structure Checks
            result should startWith("digraph {")
            result should endWith("}")

            // Check Nodes exist with correct IDs and Labels
            result should include("1 [label=testNode")
            result should include("2 [label=testNode")
            result should include("3 [label=testNode")

            // Check Edges exist
            result should include("1 -> 2 [label=testEdge")
            result should include("2 -> 3 [label=testEdge")

            // Check Properties (Independent of order)
            // Node 1 properties
            result should include("StringProperty=\"<stringProp1>\"")
            result should include("IntProperty=11")
            result should include("FunkyListProperty=\"apoplectic;bucolic\"")
            // Check list property formatting with semicolon
            result should include("IntListProperty=\"21;31;41\"")

            // Check escaping
            // Original: string"Prop2\" -> Expected in DOT: string\"Prop2\\
            result should include("StringProperty=\"string\\\"Prop2\\\\\"")
        }
    }
}