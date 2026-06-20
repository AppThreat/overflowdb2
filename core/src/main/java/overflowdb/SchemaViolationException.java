package overflowdb;

public class SchemaViolationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public SchemaViolationException(String message) {
        super(message);
    }

    public SchemaViolationException(String message, Throwable cause) {
        super(message, cause);
    }
}
