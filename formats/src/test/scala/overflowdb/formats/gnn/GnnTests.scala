package overflowdb.formats.gnn

import better.files._
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import overflowdb.testdomains.simple.{SimpleDomain, TestEdge, TestNode}

class GnnTests extends AnyWordSpec {

    "GnnExporter" should {
        "write parallel arrays as JSON for the whole graph" in {
            val graph = SimpleDomain.newGraph()
            val node1 = graph.addNode(1, TestNode.LABEL)
            val node2 = graph.addNode(2, TestNode.LABEL)
            node1.addEdge(TestEdge.LABEL, node2)

            File.usingTemporaryDirectory(getClass.getName) { dir =>
                val result = GnnExporter.runExport(graph, dir.pathAsString)
                result.nodeCount shouldBe 2
                result.edgeCount shouldBe 1
                val Seq(exported) = result.files

                val json = better.files.File(exported).contentAsString.trim
                json should startWith("{")
                json should endWith("}")
                json should include("\"nodeIds\":[1,2]")
                json should include(s""""nodeLabels":["${TestNode.LABEL}","${TestNode.LABEL}"]""")
                json should include("\"edgeSrcIds\":[1]")
                json should include("\"edgeDstIds\":[2]")
                json should include(s""""edgeLabels":["${TestEdge.LABEL}"]""")
            }
        }

        "only emit edges that stay within the exported node set" in {
            val graph = SimpleDomain.newGraph()
            val node1 = graph.addNode(1, TestNode.LABEL)
            val node2 = graph.addNode(2, TestNode.LABEL)
            val node3 = graph.addNode(3, TestNode.LABEL)
            node1.addEdge(TestEdge.LABEL, node2)
            node2.addEdge(TestEdge.LABEL, node3) // node3 is left out of the selection below

            File.usingTemporaryDirectory(getClass.getName) { dir =>
                val result =
                    GnnExporter.runExport(Seq(node1, node2), Iterator.empty, dir.path.resolve("slice.json"))
                result.nodeCount shouldBe 2
                result.edgeCount shouldBe 1
                val json = better.files.File(result.files.head).contentAsString
                json should include("\"edgeSrcIds\":[1]")
                json should include("\"edgeDstIds\":[2]")
            }
        }
    }
}
