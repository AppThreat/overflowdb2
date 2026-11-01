package overflowdb.traversal

import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import overflowdb._
import overflowdb.traversal.testdomains.simple.Thing.Properties.Name
import overflowdb.traversal.testdomains.simple.{Connection, SimpleDomain, Thing}

class TraversalSourceTest extends AnyWordSpec {

  "id starter step" when {

    "cast to given node type" in new Fixture {
      def thingViaId = traversal.id[Thing](one.id)
      thingViaId.out.next() shouldBe two1

      // `.outV` is only available on Traversal[Edge] (via EdgeTraversal)
      assertCompiles("thingViaId.out")
      assertDoesNotCompile("thingViaId.outV")
    }

    "default to `Node` if no type parameter given" in new Fixture {
      def thingViaId = traversal.id(one.id)
      thingViaId.out.next() shouldBe two1

      // `.outV` is only available on Traversal[Edge] (via EdgeTraversal)
      assertCompiles("thingViaId.out")
      assertDoesNotCompile("thingViaId.outV")
    }
  }

  "property lookup with and without indexes" in new Fixture {
    verifyTraversalResults()
    graph.indexManager.createNodePropertyIndex(Name.name)
    verifyTraversalResults()
  }

  "property lookup with an index created before any node has the property" in {
    val graph = SimpleDomain.newGraph
    graph.indexManager.createNodePropertyIndex(Name.name)
    val one   = graph + (Thing.Label, Name.of("one"))
    val two   = graph + (Thing.Label, Name.of("two"))
    val trav  = SimpleDomain.traversal(graph)
    trav.has(Name.of("one")).toSetMutable shouldBe Set(one)
    trav.labelAndProperty(Thing.Label, Name.of("two")).toSetMutable shouldBe Set(two)
    InitialTraversal.from[Node](graph, Thing.Label).getByIndex(Name.name, "one").get.toSet shouldBe Set(one)
    graph.close()
  }

  "label starter step" should {
    "visit the nodes present when it is first used, not those added while it runs" in {
      val graph = SimpleDomain.newGraph
      (1 to 5).foreach(i => graph + (Thing.Label, Name.of(s"n$i")))
      val trav    = SimpleDomain.traversal(graph).things
      val visited = trav.map { thing => graph + (Thing.Label, Name.of("added")); thing.name }.take(1000).l
      visited shouldBe List("n1", "n2", "n3", "n4", "n5")
      SimpleDomain.traversal(graph).things.size shouldBe 10
      graph.close()
    }

    "see nodes added after it was created but before it is used" in {
      val graph = SimpleDomain.newGraph
      graph + (Thing.Label, Name.of("before"))
      val trav = SimpleDomain.traversal(graph).things
      graph + (Thing.Label, Name.of("after"))
      trav.map(_.name).l shouldBe List("before", "after")
      graph.close()
    }

    "use an index only while it has not been advanced" in {
      val graph = SimpleDomain.newGraph
      graph.indexManager.createNodePropertyIndex(Name.name)
      graph + (Thing.Label, Name.of("one"))
      val trav = InitialTraversal.from[Node](graph, Thing.Label)
      trav.hasNext shouldBe true
      trav.canUseIndex(Name.name) shouldBe true
      trav.next()
      trav.canUseIndex(Name.name) shouldBe false
      graph.close()
    }
  }

  private class Fixture {
    val graph = SimpleDomain.newGraph

    val one = graph + (Thing.Label, Name.of("one"))
    val two1 = graph + (Thing.Label, Name.of("two"))
    val two2 = graph + (Thing.Label, Name.of("two"))
    one.addEdge(Connection.Label, two1)

    def traversal = SimpleDomain.traversal(graph)

    def verifyTraversalResults() = {
      traversal.has(Name.of("one")).toSetMutable shouldBe Set(one)
      traversal.has(Name.of("two")).toSetMutable shouldBe Set(two1, two2)
      traversal.has(Name.of("unknown")).toSetMutable shouldBe Set.empty

      traversal.labelAndProperty(Thing.Label, Name.of("two")).toSetMutable shouldBe Set(two1, two2)
      traversal.labelAndProperty(Thing.Label, Name.of("unknown")).toSetMutable shouldBe Set.empty
      traversal.labelAndProperty("unknown", Name.of("two")).toSetMutable shouldBe Set.empty
    }

  }
}
