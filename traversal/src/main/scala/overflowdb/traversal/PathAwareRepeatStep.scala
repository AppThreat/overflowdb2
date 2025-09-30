package overflowdb.traversal

import overflowdb.traversal.RepeatBehaviour.SearchAlgorithm

import scala.collection.{mutable, Iterator}

object PathAwareRepeatStep:
    import RepeatStep.*

    case class WorklistItem[A](traversal: Traversal[A], depth: Int)

    /** @see
      *   [[Traversal.repeat]] for a detailed overview
      *
      * Implementation note: using recursion results in nicer code, but uses the JVM stack, which
      * only has enough space for ~10k steps. So instead, this uses a programmatic Stack which is
      * semantically identical. The RepeatTraversalTests cover this case.
      */
    def apply[A](
      repeatTraversal: Traversal[A] => Traversal[A],
      behaviour: RepeatBehaviour[A]
    ): A => PathAwareTraversal[A] = (element: A) =>
        new PathAwareTraversal[A](new Iterator[(A, Vector[Any])]:
            private val visited                                   = mutable.Set.empty[A]
            private val emitSack: mutable.Queue[(A, Vector[Any])] = mutable.Queue.empty
            private val worklist: Worklist[WorklistItem[A]] = behaviour.searchAlgorithm match
                case SearchAlgorithm.DepthFirst   => new LifoWorklist()
                case SearchAlgorithm.BreadthFirst => new FifoWorklist()

            worklist.addItem(WorklistItem(
              new PathAwareTraversal(Iterator.single((element, Vector.empty))),
              0
            ))

            def hasNext: Boolean =
                if emitSack.isEmpty then
                    traverseOnWorklist()
                emitSack.nonEmpty || worklistTopHasNext

            private def traverseOnWorklist(): Unit =
                var continue = true
                while worklist.nonEmpty && continue do
                    val WorklistItem(trav0, depth) = worklist.head
                    val trav = trav0.asInstanceOf[PathAwareTraversal[A]].wrapped
                    if trav.isEmpty then
                        worklist.removeHead()
                    else if behaviour.maxDepthReached(depth) then
                        continue = false
                    else
                        val (currentElement, currentPath) = trav.next()
                        if behaviour.dedupEnabled then visited.addOne(currentElement)

                        val shouldStop =
                            // `while/repeat` behaviour, i.e. check every time
                            behaviour.whileConditionIsDefinedAndEmpty(currentElement) ||
                                // `repeat/until` behaviour, i.e. only checking the `until` condition from depth 1
                                (depth > 0 && behaviour.untilConditionReached(currentElement))

                        if shouldStop then
                            // we just consumed an element from the traversal, so in lieu adding to the emit sack
                            emitSack.enqueue((currentElement, currentPath))
                            continue = false
                        else
                            val nextLevelTraversal =
                                val repeat =
                                    repeatTraversal(new PathAwareTraversal(Iterator.single((
                                      currentElement,
                                      currentPath
                                    ))))
                                if behaviour.dedupEnabled then repeat.filterNot(visited.contains)
                                else repeat
                            worklist.addItem(WorklistItem(nextLevelTraversal, depth + 1))

                            if behaviour.shouldEmit(currentElement, depth) then
                                emitSack.enqueue((currentElement, currentPath))

                            if emitSack.nonEmpty then
                                continue = false
                        end if
                    end if
                end while
            end traverseOnWorklist

            private inline def worklistTopHasNext: Boolean =
                worklist.nonEmpty && worklist.head.traversal.hasNext

            override def next(): (A, Vector[Any]) =
                val result =
                    if emitSack.nonEmpty then
                        emitSack.dequeue()
                    else if worklistTopHasNext then
                        worklist.head.traversal.asInstanceOf[PathAwareTraversal[A]].wrapped.next()
                    else
                        throw new NoSuchElementException("next on empty iterator")

                if behaviour.dedupEnabled then
                    visited.addOne(result._1)
                result
        )
end PathAwareRepeatStep
