package overflowdb.algorithm

import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import overflowdb.Node
import overflowdb.traversal.testdomains.simple.{Connection, SimpleDomain}

import java.util.ArrayList
import scala.jdk.CollectionConverters.IteratorHasAsScala

class AlgorithmTests extends AnyWordSpec {

    "TopologicalSort" should {
        "sort a simple DAG" in {
            val graph = SimpleDomain.newGraph

            // Create a DAG:
            // A -> B -> D
            // A -> C -> D
            val a = graph.addNode("thing")
            val b = graph.addNode("thing")
            val c = graph.addNode("thing")
            val d = graph.addNode("thing")

            a.addEdge(Connection.Label, b)
            a.addEdge(Connection.Label, c)
            b.addEdge(Connection.Label, d)
            c.addEdge(Connection.Label, d)

            // Collect all nodes into a Java List
            val nodes = new ArrayList[Node]()
            graph.nodes().forEachRemaining(nodes.add)

            val sorted = TopologicalSort.sort(nodes, n => n.out(Connection.Label))

            sorted.size shouldBe 4

            val idxA = sorted.indexOf(a)
            val idxB = sorted.indexOf(b)
            val idxC = sorted.indexOf(c)
            val idxD = sorted.indexOf(d)

            // Dependencies must come *before* dependents
            withClue("A must come before B") { idxA should be < idxB }
            withClue("A must come before C") { idxA should be < idxC }
            withClue("B must come before D") { idxB should be < idxD }
            withClue("C must come before D") { idxC should be < idxD }

            graph.close()
        }

        "detect cycles" in {
            val graph = SimpleDomain.newGraph
            val a = graph.addNode("thing")
            val b = graph.addNode("thing")

            a.addEdge(Connection.Label, b)
            b.addEdge(Connection.Label, a) // Cycle

            val nodes = new ArrayList[Node]()
            graph.nodes().forEachRemaining(nodes.add)

            assertThrows[TopologicalSort.CycleDetectedException] {
                TopologicalSort.sort(nodes, n => n.out(Connection.Label))
            }
            graph.close()
        }
    }

    "UnionFind" should {
        "manage disjoint sets correctly" in {
            val uf = new UnionFind()
            uf.union(1, 2) shouldBe true
            withClue("1 and 2 should have same root") {
                uf.find(1) shouldBe uf.find(2)
            }
            withClue("1 and 3 should be different") {
                uf.find(1) shouldNot be(uf.find(3))
            }
            uf.union(3, 4)
            uf.union(2, 4) shouldBe true
            val root1 = uf.find(1)
            uf.find(2) shouldBe root1
            uf.find(3) shouldBe root1
            uf.find(4) shouldBe root1
            uf.union(1, 4) shouldBe false
        }
    }

    "HeapWalker" should {
        "traverse tree in DFS order" in {
            val graph = SimpleDomain.newGraph

            // Create Tree:
            //      Root
            //     /    \
            //   L1      R1
            //  /  \       \
            // L2   L3      R2

            val root = graph.addNode("thing")
            val l1 = graph.addNode("thing")
            val l2 = graph.addNode("thing")
            val l3 = graph.addNode("thing")
            val r1 = graph.addNode("thing")
            val r2 = graph.addNode("thing")

            root.addEdge(Connection.Label, l1)
            root.addEdge(Connection.Label, r1)

            l1.addEdge(Connection.Label, l2)
            l1.addEdge(Connection.Label, l3)

            r1.addEdge(Connection.Label, r2)

            val walker = HeapWalker.forNode(root, Connection.Label)

            val visited = new ArrayList[Node]()
            while (walker.hasNext) {
                visited.add(walker.next())
            }
            val expected = java.util.Arrays.asList(root, l1, l2, l3, r1, r2)
            visited shouldBe expected

            graph.close()
        }

        "handle deep graphs without StackOverflow" in {
            val graph = SimpleDomain.newGraph
            var current = graph.addNode("thing")
            val root = current

            // Create a chain of 10k nodes (enough to blow default stack in recursive calls often)
            for (_ <- 0 until 10000) {
                val next = graph.addNode("thing")
                current.addEdge(Connection.Label, next)
                current = next
            }

            val walker = HeapWalker.forNode(root, Connection.Label)
            var count = 0
            while (walker.hasNext) {
                walker.next()
                count += 1
            }

            count shouldBe 10001
            graph.close()
        }
    }

    "DominatorTree" should {
        "compute immediate dominators correctly" in {
            val graph = SimpleDomain.newGraph
            val a = graph.addNode("thing")
            val b = graph.addNode("thing")
            val c = graph.addNode("thing")
            val d = graph.addNode("thing")

            a.addEdge(Connection.Label, b)
            a.addEdge(Connection.Label, c)
            b.addEdge(Connection.Label, d)
            c.addEdge(Connection.Label, d)

            val idoms = DominatorTree.computeDominators(a, n => n.out(Connection.Label))
            idoms.get(b.id()) shouldBe a.id()
            idoms.get(c.id()) shouldBe a.id()
            idoms.get(d.id()) shouldBe a.id()
            graph.close()
        }
    }

    "StronglyConnectedComponents" should {
        "find cyclic components correctly" in {
            val graph = SimpleDomain.newGraph
            val a = graph.addNode("thing")
            val b = graph.addNode("thing")
            val c = graph.addNode("thing")

            a.addEdge(Connection.Label, b)
            b.addEdge(Connection.Label, c)
            c.addEdge(Connection.Label, a) // Cycle A-B-C

            val d = graph.addNode("thing")
            c.addEdge(Connection.Label, d) // Node D outside the cycle

            val nodes = java.util.Arrays.asList(a, b, c, d)
            val sccs = StronglyConnectedComponents.compute(nodes, n => n.out(Connection.Label))
            
            sccs.size() shouldBe 2
            
            import scala.jdk.CollectionConverters._
            val sccSets = sccs.asScala.map(_.asScala.toSet).toSet
            sccSets should contain (Set(a, b, c))
            sccSets should contain (Set(d))
            
            graph.close()
        }
    }

    "ContextSensitivePathFinder" should {
        "find valid paths and reject mismatched return contexts" in {
            val graph = SimpleDomain.newGraph
            val entry = graph.addNode("thing")
            val call1 = graph.addNode("thing")
            val body = graph.addNode("thing")
            val ret1 = graph.addNode("thing")
            val exit = graph.addNode("thing")

            // Context edge definition helper
            import ContextSensitivePathFinder.ContextEdge
            import ContextSensitivePathFinder.ContextEdge.Type
            import scala.jdk.CollectionConverters._

            val getEdges: java.util.function.Function[Node, java.util.Iterator[ContextEdge]] = (n: Node) => {
                val list = new ArrayList[ContextEdge]()
                if (n == entry) {
                    list.add(new ContextEdge(call1, Type.NEUTRAL, 0))
                } else if (n == call1) {
                    list.add(new ContextEdge(body, Type.OPEN, 101)) // Call site 101
                } else if (n == body) {
                    list.add(new ContextEdge(ret1, Type.CLOSE, 101)) // Return to call site 101
                    list.add(new ContextEdge(exit, Type.CLOSE, 999)) // Mismatched return site
                }
                list.iterator()
            }

            val pathOpt = ContextSensitivePathFinder.findPath(entry, ret1, getEdges, 5)
            pathOpt.isPresent shouldBe true
            pathOpt.get().nodes.asScala shouldBe Seq(entry, call1, body, ret1)

            val invalidPathOpt = ContextSensitivePathFinder.findPath(entry, exit, getEdges, 5)
            invalidPathOpt.isPresent shouldBe false

            graph.close()
        }
    }

    "AsynchronousPrefetcher" should {
        "preload cleared NodeRefs in background" in {
            val graph = SimpleDomain.newGraph
            val node = graph.addNode("thing").asInstanceOf[overflowdb.NodeRef[?]]
            val prefetcher = new AsynchronousPrefetcher(2)
            prefetcher.prefetch(java.util.Collections.singletonList(node.asInstanceOf[overflowdb.Node]))
            prefetcher.shutdown()
            node.isSet shouldBe true
            graph.close()
        }
    }

    "GnnExporter" should {
        "export induced subgraph to flat primitive arrays" in {
            val graph = SimpleDomain.newGraph
            val a = graph.addNode("thing")
            val b = graph.addNode("thing")
            a.addEdge(Connection.Label, b)

            val nodes = java.util.Arrays.asList(a, b)
            val exporterResult = GnnExporter.exportGraph(nodes)

            exporterResult.nodeIds should contain theSameElementsAs Array(a.id(), b.id())
            exporterResult.nodeLabels should contain theSameElementsAs Array("thing", "thing")
            exporterResult.edgeSrcIds shouldBe Array(a.id())
            exporterResult.edgeDstIds shouldBe Array(b.id())
            exporterResult.edgeLabels shouldBe Array(Connection.Label)

            graph.close()
        }
    }
}