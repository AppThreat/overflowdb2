package overflowdb.traversal

import overflowdb.Graph
import overflowdb.util.NodesList

import scala.jdk.CollectionConverters.IteratorHasAsScala

/** All nodes of one label. The nodes are read from a snapshot taken when the traversal is first used, so nodes added
  * while it runs are not visited, and iterating is safe while other threads add or remove nodes.
  */
class InitialTraversal[+A <: overflowdb.Node] private (
  graph: Graph,
  label: String,
  nodesList: NodesList
) extends Iterator[A]:
    private var underlying: java.util.Iterator[overflowdb.Node] = null
    private var started                                          = false

    private def nodes: java.util.Iterator[overflowdb.Node] =
        if underlying == null then underlying = nodesList.nodesByLabel(label).iterator()
        underlying

    override def hasNext: Boolean = nodes.hasNext

    override def next(): A =
        started = true
        nodes.next().asInstanceOf[A]

    // we can only do this if the iterator itself is virgin, e.g. `val trav = cpg.method; trav.next; trav.fullNameExact(...)` cannot use the index
    def canUseIndex(key: String): Boolean = !started && graph.indexManager.isIndexed(key)

    def getByIndex(key: String, value: Any): Option[Iterator[A]] =
        if canUseIndex(key) then
            val nodes = graph.indexManager.lookup(key, value)
            Some(nodes.iterator().asScala.label(label).cast[A])
        else
            None
end InitialTraversal

object InitialTraversal:
    def from[A <: overflowdb.Node](graph: Graph, label: String): InitialTraversal[A] =
        new InitialTraversal(graph, label, overflowdb.Misc.extractNodesList(graph))
