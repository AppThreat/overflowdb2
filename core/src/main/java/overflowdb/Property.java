package overflowdb;

public class Property<A> {
  public final PropertyKey<A> key;
  public final A value;

  public Property(PropertyKey<A> key, A value) {
    this.key = key;
    this.value = value;
  }

  public Property(String key, A value) {
    this.key = new PropertyKey<>(key);
    this.value = value;
  }
}
