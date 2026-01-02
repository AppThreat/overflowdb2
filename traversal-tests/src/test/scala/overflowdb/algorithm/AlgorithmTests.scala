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
}