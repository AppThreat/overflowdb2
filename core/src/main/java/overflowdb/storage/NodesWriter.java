package overflowdb.storage;

import overflowdb.Node;
import overflowdb.NodeDb;
import overflowdb.NodeRef;

import java.io.IOException;
import java.util.Spliterator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.StreamSupport;

/**
 * Persists collections of nodes in bulk to disk. Used either by ReferenceManager (if overflow to disk is enabled),
 * or alternatively when closing the graph (if storage to disk is enabled).
 */
public class NodesWriter {
  private final NodeSerializer nodeSerializer;
  private final OdbStorage storage;

  public NodesWriter(NodeSerializer nodeSerializer, OdbStorage storage) {
    this.nodeSerializer = nodeSerializer;
    this.storage = storage;
  }

  /**
   * Writes all references to storage, blocks until complete.
   * Serialization happens in parallel, however writing to storage happens sequentially, to avoid lock contention in mvstore.
   */
  public void writeAndClearBatched(Spliterator<? extends Node> nodes, int estimatedTotalCount) {
    if (estimatedTotalCount > 0) {}

    AtomicInteger count = new AtomicInteger(0);

    StreamSupport.stream(nodes, true)
        .map(this::serializeIfDirty)
        .sequential()
        .forEach(serializedNode -> {
          if (serializedNode != null) {
            storage.persist(serializedNode.id, serializedNode.data);

            /** counting only for printing statistics - this is rafher slow, but since persisting to disk is much slower
             * and also disk-bound, it doesn't really matter... */
            int currCount = count.incrementAndGet();
            if (currCount % 100_000 == 0) {
               float progressPercent = 100f * currCount / estimatedTotalCount;
            }
          }
        });

    if (estimatedTotalCount > 0) {}
  }
  private SerializedNode serializeIfDirty(Node node) {
    NodeDb nodeDb = null;
    NodeRef ref = null;
    if (node instanceof NodeDb) {
      nodeDb = (NodeDb) node;
      ref = nodeDb.ref;
    } else if (node instanceof NodeRef) {
      ref = (NodeRef) node;
      if (ref.isSet()) nodeDb = ref.get();
    }

    if (nodeDb != null && nodeDb.isDirty()) {
      try {
        byte[] data = nodeSerializer.serialize(nodeDb);
        NodeRef.clear(ref);
        return new SerializedNode(ref.id(), data);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
    return null;
  }

  private static class SerializedNode {
    private final long id;
    private final byte[] data;

    private SerializedNode(long id, byte[] data) {
      this.id = id;
      this.data = data;
    }
  }

}
