package overflowdb;

import org.h2.mvstore.MVMap;
import overflowdb.storage.OdbStorage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.LongStream;

public final class IndexManager {

  private final Graph graph;
  private final Map<String, Map<Object, Set<NodeRef<?>>>> indexes = new ConcurrentHashMap<>();
  private final Map<String, Boolean> dirtyFlags = new ConcurrentHashMap<>();

  public IndexManager(Graph graph) {
    this.graph = graph;
  }

  /**
   * Create an index for specified node property.
   * Whenever an element has the specified key mutated, the index is updated.
   * When the index is created, all existing elements are indexed to ensure that they are captured by the index.
   */
  public void createNodePropertyIndex(final String propertyName) {
    checkPropertyName(propertyName);
    /* The index exists from here on, also when no node has the property yet: otherwise putIfIndexed would ignore the
     * nodes that get it later. */
    if (indexes.putIfAbsent(propertyName, new ConcurrentHashMap<>()) != null) return;
    dirtyFlags.put(propertyName, true);
    /* Under the node's monitor, as NodeDb's property changes: a thread that sets the property meanwhile either
     * finds the index and moves the node itself, or has set the value before it is read here. */
    graph.nodes.iterator().forEachRemaining(node -> {
      final NodeRef<?> ref = (NodeRef<?>) node;
      final NodeDb nodeDb = ref.get();
      synchronized (nodeDb) {
        Object value = nodeDb.property(propertyName);
        if (value != null) put(propertyName, value, ref);
      }
    });
  }

  public boolean isIndexed(final String propertyName) {
    return indexes.containsKey(propertyName);
  }

  private void checkPropertyName(String propertyName) {
    if (propertyName == null || propertyName.isEmpty())
      throw new IllegalArgumentException("Illegal property name: " + propertyName);
  }

  private void loadNodePropertyIndex(final String propertyName, Map<Object, long[]> valueToNodeIds) {
    indexes.putIfAbsent(propertyName, new ConcurrentHashMap<>());
    dirtyFlags.put(propertyName, false);
    valueToNodeIds.entrySet().parallelStream().forEach(entry ->
            LongStream.of(entry.getValue())
                    .forEach(nodeId -> put(propertyName, entry.getKey(), (NodeRef<?>) graph.node(nodeId))));
  }

  public void putIfIndexed(final String key, final Object newValue, final NodeRef<?> nodeRef) {
    if (newValue != null && indexes.containsKey(key)) {
      dirtyFlags.put(key, true);
      put(key, newValue, nodeRef);
    }
  }

  /** Moves a node from its old value of an indexed property to its new one; does nothing for a property that is not
   * indexed. A null value is not indexed. */
  void replaceIfIndexed(final String key, final Object oldValue, final Object newValue, final NodeRef<?> nodeRef) {
    if (!indexes.containsKey(key)) return;
    if (oldValue != null && !oldValue.equals(newValue)) remove(key, oldValue, nodeRef);
    putIfIndexed(key, newValue, nodeRef);
  }

  /* put and remove change a value's set inside compute, so a removal that empties and drops the set cannot race an
   * addition to it. */
  private void put(final String key, final Object value, final NodeRef<?> nodeRef) {
    final Map<Object, Set<NodeRef<?>>> keyMap = indexes.get(key);
    if (keyMap == null) return; // dropped meanwhile
    keyMap.compute(value, (k, objects) -> {
      if (objects == null) objects = ConcurrentHashMap.newKeySet();
      objects.add(nodeRef);
      return objects;
    });
  }

  /**
   * Drop the index for specified node property.
   */
  public void dropNodePropertyIndex(final String key) {
    if (indexes.containsKey(key)) {
      indexes.remove(key).clear();
      dirtyFlags.remove(key);
    }
  }

  public Set<String> getIndexedNodeProperties() {
    return indexes.keySet();
  }

  public int getIndexedNodeCount(String propertyName) {
    final Map<Object, Set<NodeRef<?>>> indexMap = this.indexes.get(propertyName);
    return indexMap == null ? 0 : indexMap.values().stream().mapToInt(Set::size).sum();
  }

  public List<NodeRef<?>> lookup(final String key, final Object value) {
    final Map<Object, Set<NodeRef<?>>> keyMap = indexes.get(key);
    if (null == keyMap) {
      return Collections.emptyList();
    } else {
      Set<NodeRef<?>> set = keyMap.get(value);
      return set == null ? Collections.emptyList() : new ArrayList<>(set);
    }
  }

  void remove(final String key, final Object value, final NodeRef<?> nodeRef) {
    final Map<Object, Set<NodeRef<?>>> keyMap = indexes.get(key);
    if (null != keyMap && null != value) {
      dirtyFlags.put(key, true);
      keyMap.computeIfPresent(value, (k, objects) -> {
        objects.remove(nodeRef);
        return objects.isEmpty() ? null : objects;
      });
    }
  }

  void removeElement(final NodeRef<?> nodeRef) {
    NodeDb node = nodeRef.get();
    for (String propertyName : node.propertyKeys()) {
        if (indexes.containsKey(propertyName)) {
            Object value = node.property(propertyName);
            if (value != null) {
                remove(propertyName, value, nodeRef);
            }
        }
    }
  }

  private Map<Object, Set<NodeRef<?>>> getIndexMap(String propertyName) {
    return this.indexes.get(propertyName);
  }

  void initializeStoredIndices(OdbStorage storage) {
    storage.getIndexNames().forEach(indexName -> loadIndex(indexName, storage));
  }

  private void loadIndex(String indexName, OdbStorage storage) {
    final MVMap<Object, long[]> indexMVMap = storage.openIndex(indexName);
    loadNodePropertyIndex(indexName, indexMVMap);
  }

  void storeIndexes(OdbStorage storage) {
    getIndexedNodeProperties().forEach(propertyName ->
            saveIndex(storage, propertyName, getIndexMap(propertyName)));
  }

  private void saveIndex(OdbStorage storage, String propertyName, Map<Object, Set<NodeRef<?>>> indexMap) {
    if (dirtyFlags.getOrDefault(propertyName, false)) {
      storage.clearIndex(propertyName);
      final MVMap<Object, long[]> indexStore = storage.openIndex(propertyName);
      indexMap.entrySet().parallelStream().forEach(entry -> {
        final Object propertyValue = entry.getKey();
        final Set<NodeRef<?>> nodeRefs = entry.getValue();
        if (!nodeRefs.isEmpty()) {
          indexStore.put(propertyValue, nodeRefs.stream().mapToLong(nodeRef -> nodeRef.id).toArray());
        }
      });
      dirtyFlags.put(propertyName, false);
    }
  }
}