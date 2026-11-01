package overflowdb.util;

import gnu.trove.map.TLongIntMap;
import gnu.trove.map.hash.TLongIntHashMap;
import overflowdb.Node;
import overflowdb.storage.NodesWriter;

import java.util.*;

public class NodesList {
  private volatile Node[] nodes;
  private int size = 0;

  //index into `nodes` array by node id
  private TLongIntMap nodeIndexByNodeId;

  /** For the node in each slot of `nodes`, its position in the array of its label (see {@link LabelNodes}). */
  private int[] labelPositions;

  private final Map<String, LabelList> nodesByLabel = new HashMap<>(32);

  /** list of available slots in `nodes` array. slots become available after nodes have been removed */
  private final BitSet emptySlots;

  private static final int DEFAULT_CAPACITY = 10000;

  public NodesList() {
    this(DEFAULT_CAPACITY);
  }

  public NodesList(int initialCapacity) {
    nodes = new Node[initialCapacity];
    labelPositions = new int[initialCapacity];
    emptySlots = new BitSet(initialCapacity);
    nodeIndexByNodeId = new TLongIntHashMap(initialCapacity);
  }

  /** store Node in internal collections */
  public synchronized void add(Node node) {
    verifyUniqueId(node);
    int index = tryClaimEmptySlot();
    if (index == -1) {
      // no empty spot available - append to nodes array instead
      index = size;
      ensureCapacity(size + 1);
    }

    nodes[index] = node;
    nodeIndexByNodeId.put(node.id(), index);
    labelPositions[index] = labelList(node.label()).append(node);
    size++;
  }

  private void verifyUniqueId(Node node) {
    if (nodeIndexByNodeId.containsKey(node.id())) {
      Node existingNode = nodeById(node.id());
      throw new AssertionError("different Node with same id already exists in this NodesList: " + existingNode);
    }
  }

  /** @return -1 if no available empty slots, otherwise the successfully claimed slot */
  private int tryClaimEmptySlot() {
    final int nextEmptySlot = emptySlots.nextSetBit(0);
    if (nextEmptySlot != -1) {
      emptySlots.clear(nextEmptySlot);
    }
    return nextEmptySlot;
  }

  /* Lookups take the same monitor as add/remove/compact. The trove map rehashes in place while a node is added, and
   * a lookup racing that rehash can miss a node that is in the list. A pass writer adds nodes while the pass's parts
   * traverse the graph, so a NodeRef built from an id (e.g. the OpNodes wrappers) could come out empty and then fail
   * with "unable to read node from disk" for a node that was never written to disk. */
  public synchronized boolean contains(long id) {
    return nodeIndexByNodeId.containsKey(id);
  }

  public synchronized Node nodeById(long id) {
    final int index = nodeIndexByNodeId.get(id);
    // the map's no-entry value (0) is also a valid index
    if (index == nodeIndexByNodeId.getNoEntryValue() && !nodeIndexByNodeId.containsKey(id)) return null;
    return nodes[index];
  }

  public synchronized void remove(Node node) {
    if (!nodeIndexByNodeId.containsKey(node.id())) return;
    int index = nodeIndexByNodeId.remove(node.id());
    final Node removed = nodes[index];
    nodes[index] = null;
    emptySlots.set(index);

    final LabelList list = nodesByLabel.get(removed.label());
    if (list != null) {
      list.removeAt(labelPositions[index], removed);
      if (list.needsCompaction()) list.compact(this::setLabelPosition);
    }

    size--;
    compactMaybe();
  }

  /** Called while a label's array is compacted: the node now sits at `position` of it. */
  private void setLabelPosition(Node node, int position) {
    labelPositions[nodeIndexByNodeId.get(node.id())] = position;
  }

  public int size() {
    return size;
  }

  private LabelList labelList(String label) {
    return nodesByLabel.computeIfAbsent(label, k -> new LabelList());
  }

  /**
   * The nodes with the given label at the time of the call, in the order they were added. Nodes added later are not
   * included. A node removed later may or may not be: iteration skips it once its removal is visible. The snapshot
   * is safe to iterate while other threads add and remove nodes, and costs no copy.
   */
  public synchronized LabelNodes nodesByLabel(String label) {
    final LabelList list = nodesByLabel.get(label);
    return list == null ? LabelNodes.EMPTY : list.snapshot();
  }

  public synchronized Set<String> nodeLabels() {
    Set<String> ret = new HashSet<>(nodesByLabel.size());
    nodesByLabel.forEach((key, value) -> {
      if (value.live > 0) {
        ret.add(key);
      }
    });
    return ret;
  }

  public synchronized Iterator<Node> iterator() {
    return new NodesIterator(nodes);
  }

  private void ensureCapacity(int minCapacity) {
    if (nodes.length < minCapacity) grow(minCapacity);
  }

  /** compact if there are many empty slots, and they make up >= 30% of the node array */
  private void compactMaybe() {
    final int emptyCount = emptySlots.cardinality();
    if (emptyCount > 10000 &&
        emptyCount * 100 / nodes.length >= 30) {
      compact();
    }
  }

  /** Trims down internal collections to just about the necessary size, in order to allow the remainder to be
   * garbage collected. Creates a new `nodes` array which contains all existing nodes.  */
  synchronized void compact() {
    final Node[] nextNodesArray = new Node[size];
    final int[] nextLabelPositions = new int[size];
    final TLongIntMap nextIndex = new TLongIntHashMap(size);
    int idx = 0;
    for (int old = 0; old < nodes.length; old++) {
      final Node node = nodes[old];
      if (node != null) {
        nextNodesArray[idx] = node;
        nextLabelPositions[idx] = labelPositions[old];
        nextIndex.put(node.id(), idx);
        idx++;
      }
    }

    //reindex helper collections
    emptySlots.clear();
    nodeIndexByNodeId = nextIndex;
    labelPositions = nextLabelPositions;
    nodes = nextNodesArray;
  }

  private static final int MAX_ARRAY_SIZE = Integer.MAX_VALUE - 8;

  private synchronized void grow(int minCapacity) {
    nodes = Arrays.copyOf(nodes, newCapacity(nodes.length, minCapacity));
    labelPositions = Arrays.copyOf(labelPositions, nodes.length);
  }

  static int newCapacity(int oldCapacity, int minCapacity) {
    int newCapacity = oldCapacity + (oldCapacity >> 1);
    if (newCapacity - minCapacity < 0)
      newCapacity = minCapacity;
    if (newCapacity - MAX_ARRAY_SIZE > 0)
      newCapacity = hugeCapacity(minCapacity);
    return newCapacity;
  }

  private static int hugeCapacity(int minCapacity) {
    if (minCapacity < 0)
      throw new OutOfMemoryError();
    return (minCapacity > MAX_ARRAY_SIZE) ?
            Integer.MAX_VALUE :
            MAX_ARRAY_SIZE;
  }

  protected int _elementDataSize() {
    return nodes.length;
  }

  /** Slots taken in the array of the given label, including removed nodes not yet compacted away. For tests. */
  synchronized int _labelDataSize(String label) {
    final LabelList list = nodesByLabel.get(label);
    return list == null ? 0 : list.end;
  }

  public synchronized int cardinality(String label) {
    final LabelList list = nodesByLabel.get(label);
    return list == null ? 0 : list.live;
  }

  public void persistAll(NodesWriter nodesWriter) {
    nodesWriter.writeAndClearBatched(Arrays.spliterator(nodes), nodes.length).throwIfAny();
  }

  /**
   * The nodes of one label, in the order they were added. Removing a node clears its slot, and the array is compacted
   * into a new one once enough slots are clear, so that a snapshot handed out earlier never sees a node move. Only
   * NodesList changes it, under its monitor.
   */
  private static final class LabelList {
    private Node[] array = new Node[16];
    /** slots used, including cleared ones */
    private int end = 0;
    /** nodes present */
    private int live = 0;

    /** @return the node's position */
    int append(Node node) {
      if (end == array.length) array = Arrays.copyOf(array, newCapacity(array.length, end + 1));
      array[end] = node;
      live++;
      return end++;
    }

    void removeAt(int position, Node node) {
      if (position < end && array[position] == node) {
        array[position] = null;
        live--;
      } else {
        // not expected: positions are kept in step; find the node rather than corrupt the list
        for (int i = 0; i < end; i++) {
          if (array[i] == node) {
            array[i] = null;
            live--;
            return;
          }
        }
      }
    }

    boolean needsCompaction() {
      final int cleared = end - live;
      return cleared > 1024 && cleared * 100 / end >= 30;
    }

    void compact(PositionUpdate update) {
      final Node[] compacted = new Node[Math.max(16, live + (live >> 1))];
      int to = 0;
      for (int from = 0; from < end; from++) {
        final Node node = array[from];
        if (node != null) {
          compacted[to] = node;
          update.moved(node, to);
          to++;
        }
      }
      array = compacted;
      end = to;
    }

    LabelNodes snapshot() {
      return new LabelNodes(array, end, live);
    }
  }

  @FunctionalInterface
  private interface PositionUpdate {
    void moved(Node node, int position);
  }

  /** A snapshot of the nodes of one label; see {@link #nodesByLabel}. Read-only. */
  public static final class LabelNodes extends AbstractCollection<Node> {
    static final LabelNodes EMPTY = new LabelNodes(new Node[0], 0, 0);

    private final Node[] array;
    private final int end;
    private final int size;

    LabelNodes(Node[] array, int end, int size) {
      this.array = array;
      this.end = end;
      this.size = size;
    }

    /** The number of nodes when the snapshot was taken. */
    @Override
    public int size() {
      return size;
    }

    @Override
    public Iterator<Node> iterator() {
      return new NodesIterator(array, end);
    }
  }

  public static class NodesIterator implements Iterator<Node> {
    private final Node[] snapshot;
    private final int end;
    private int idx = 0;
    private Node nextPeeked = null;

    public NodesIterator(Node[] snapshot) {
      this(snapshot, snapshot.length);
    }

    public NodesIterator(Node[] snapshot, int end) {
      this.snapshot = snapshot;
      this.end = end;
    }

    @Override
    public boolean hasNext() {
      while (nextPeeked == null && idx < end) {
        nextPeeked = snapshot[idx++];
      }
      return nextPeeked != null;
    }

    @Override
    public Node next() {
      if (!hasNext()) throw new NoSuchElementException("next on empty iterator");
      else {
        Node ret = nextPeeked;
        nextPeeked = null;
        return ret;
      }
    }
  }
}
