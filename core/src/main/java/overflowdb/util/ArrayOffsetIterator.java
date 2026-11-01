package overflowdb.util;

import java.util.Iterator;
import java.util.NoSuchElementException;

public class ArrayOffsetIterator<T> implements Iterator<T> {
  private final Object[] array;
  private int current;
  private final int exclusiveEnd;
  private final int strideSize;

  /** used for peeking */
  private T nextCached;

  public ArrayOffsetIterator(Object[] array, int begin, int exclusiveEnd, int strideSize) {
    this.array = array;
    this.current = begin;
    this.exclusiveEnd = exclusiveEnd;
    this.strideSize = strideSize;
  }

  @Override
  public final boolean hasNext() {
    return peekNext() != null;
  }

  /**
   * Elements within the arrays may be null, e.g. because an edge was deleted.
   * Therefor we need to have the ability to peek forward to the next element.
   * This is a loop, not recursion: a node can have any number of holes in a row.
  */
  @SuppressWarnings("unchecked")
  private T peekNext() {
    while (nextCached == null && current < exclusiveEnd) {
      // n.b. may be `null`, e.g. if an edge was deleted
      nextCached = (T) array[current];
      current += strideSize;
    }
    return nextCached; // null: we have reached the end of this Iterator
  }

  @Override
  public final T next() {
    if (hasNext()) {
      final T ret = peekNext();
      nextCached = null;
      return ret;
    } else {
      throw new NoSuchElementException();
    }
  }
}
