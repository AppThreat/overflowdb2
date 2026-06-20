package overflowdb.storage;

public class BackwardsCompatibilityError extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public BackwardsCompatibilityError(String msg) {
    super(msg);
  }
}
