package overflowdb.testdomains.simple;

import overflowdb.DetachedNodeData;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A detached {@link TestNode} that is not a {@link overflowdb.DetachedNodeGeneric}, like the generated New* nodes of
 * a domain. Its {@link #containedTestNode} may be another detached node: when a diff is applied, {@link TestNodeDb}
 * initializes from it and creates that node through the applier's mapper, as cpg2's generated nodes do for
 * node-valued properties.
 */
public class DetachedTestNode implements DetachedNodeData {
  private Object refOrId;
  public final String stringProperty;
  public Object containedTestNode;

  public DetachedTestNode(String stringProperty) {
    this(stringProperty, null);
  }

  public DetachedTestNode(String stringProperty, Object containedTestNode) {
    this.stringProperty = stringProperty;
    this.containedTestNode = containedTestNode;
  }

  /** The properties in the order {@link TestNodeDb} initializes them. */
  public Map<String, Object> properties() {
    Map<String, Object> properties = new LinkedHashMap<>();
    if (stringProperty != null) properties.put(TestNode.STRING_PROPERTY, stringProperty);
    if (containedTestNode != null) properties.put(TestNode.CONTAINED_TESTNODE_PROPERTY, containedTestNode);
    return properties;
  }

  @Override
  public String label() {
    return TestNode.LABEL;
  }

  @Override
  public Object getRefOrId() {
    return refOrId;
  }

  @Override
  public void setRefOrId(Object refOrId) {
    this.refOrId = refOrId;
  }
}
