package overflowdb.traversal

import scala.annotation.tailrec
import scala.collection.{IterableOnce, Iterator}

class PathAwareTraversal[A](val wrapped: Iterator[(A, Vector[Any])]) extends Iterator[A]:
    type Traversal[A] = Iterator[A]

    override def hasNext: Boolean = wrapped.hasNext

    override def next(): A = wrapped.next()._1

    override def map[B](f: A => B): PathAwareTraversal[B] =
        new PathAwareTraversal[B](wrapped.map { case (a, p) =>
            (f(a), p.appended(a))
        })

    override def flatMap[B](f: A => IterableOnce[B]): PathAwareTraversal[B] =
        new PathAwareTraversal[B](wrapped.flatMap { case (a, p) =>
            val ap = p.appended(a)
            f(a).iterator.map {
                (_, ap)
            }
        })

    override def distinctBy[B](f: A => B): PathAwareTraversal[A] =
        new PathAwareTraversal[A](wrapped.distinctBy {
            case (a, p) => f(a)
        })

    override def collect[B](pf: PartialFunction[A, B]): PathAwareTraversal[B] = flatMap(pf.lift)

    override def filter(p: A => Boolean): PathAwareTraversal[A] =
        new PathAwareTraversal(wrapped.filter { case (a, _) => p(a) })

    override def filterNot(p: A => Boolean): PathAwareTraversal[A] =
        new PathAwareTraversal(wrapped.filterNot { case (a, _) => p(a) })

    override def duplicate: (Iterator[A], Iterator[A]) =
        val (iter1, iter2) = wrapped.duplicate
        (new PathAwareTraversal(iter1), new PathAwareTraversal(iter2))

    private[traversal] def _union[B](traversals: (Traversal[A] => Traversal[B])*): Traversal[B] =
        new PathAwareTraversal(wrapped.flatMap { case (a, p) =>
            traversals.iterator.flatMap { inner =>
                val result = inner(new PathAwareTraversal(Iterator.single((a, p))))
                result match
                    case stillPathAware: PathAwareTraversal[?] =>
                        stillPathAware.asInstanceOf[PathAwareTraversal[B]].wrapped
                    case notPathAware =>
                        notPathAware.iterator.map { (b: B) => (b, p.appended(a)) }
            }
        })

    private[traversal] def _choose[BranchOn >: Null, NewEnd](
      on: Traversal[A] => Traversal[BranchOn]
    )(
      options: PartialFunction[BranchOn, Traversal[A] => Traversal[NewEnd]]
    ): Traversal[NewEnd] =
        new PathAwareTraversal(wrapped.flatMap { case (a, p) =>
            val branchOnValue: BranchOn = on(Iterator.single(a)).nextOption().orNull
            val traversal = options
                .applyOrElse(
                  branchOnValue,
                  (failState: BranchOn) => (unused: Traversal[A]) => Iterator.empty[NewEnd]
                )
                .apply(new PathAwareTraversal(Iterator.single((a, p))))

            traversal match
                case stillPathAware: PathAwareTraversal[?] =>
                    stillPathAware.asInstanceOf[PathAwareTraversal[NewEnd]].wrapped
                case notPathAware =>
                    notPathAware.iterator.map { (b: NewEnd) => (b, p.appended(a)) }
        })

    private[traversal] def _coalesce[NewEnd](options: (Traversal[A] => Traversal[NewEnd])*)
      : Traversal[NewEnd] =
        new PathAwareTraversal(wrapped.flatMap { case (a, p) =>
            options.iterator
                .map { inner =>
                    val result = inner(new PathAwareTraversal(Iterator.single((a, p))))
                    result match
                        case stillPathAware: PathAwareTraversal[?] =>
                            stillPathAware.asInstanceOf[PathAwareTraversal[NewEnd]].wrapped
                        case notPathAware =>
                            notPathAware.iterator.map { (b: NewEnd) => (b, p.appended(a)) }
                }
                .find(_.hasNext)
                .getOrElse(Iterator.empty)
        })

    private[traversal] def _sideEffect(f: A => ?): PathAwareTraversal[A] =
        new PathAwareTraversal(wrapped.map {
            case (a, p) => f(a); (a, p)
        })
end PathAwareTraversal
