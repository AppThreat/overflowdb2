package overflowdb.testdomains.gratefuldead;

import overflowdb.EdgeFactory;
import overflowdb.EdgeLayoutInformation;
import overflowdb.NodeRef;
import overflowdb.NodeDb;
import overflowdb.Graph;
import overflowdb.Edge;

import java.io.Serializable;
import java.util.Arrays;
import java.util.HashSet;

public class WrittenBy extends Edge implements Serializable {
  private static final long serialVersionUID = 1L;
  public static final String LABEL = "writtenBy";
  public static final HashSet<String> PROPERTY_KEYS = new HashSet<>(Arrays.asList());

  public WrittenBy(Graph graph, NodeRef<?> outVertex, NodeRef<?> inVertex) {
    super(graph, LABEL, outVertex, inVertex, PROPERTY_KEYS);
  }

  public static final EdgeLayoutInformation layoutInformation = new EdgeLayoutInformation(LABEL, PROPERTY_KEYS);

  public static EdgeFactory<WrittenBy> factory = new EdgeFactory<WrittenBy>() {
    @Override
    public String forLabel() {
      return WrittenBy.LABEL;
    }

    @Override
    public WrittenBy createEdge(Graph graph, NodeRef<NodeDb> outVertex, NodeRef<NodeDb> inVertex) {
      return new WrittenBy(graph, outVertex, inVertex);
    }
  };
}
