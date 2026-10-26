package com.acme.rag.document.ingestion;

public class IngestionException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final Kind kind;

    public IngestionException(String message) {
        this(Kind.UNPROCESSABLE, message, null);
    }

    public IngestionException(String message, Throwable cause) {
        this(Kind.UNPROCESSABLE, message, cause);
    }

    public IngestionException(Kind kind, String message) {
        this(kind, message, null);
    }

    public IngestionException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    public enum Kind {
        INVALID_INPUT,
        CONFLICT,
        UNPROCESSABLE,
        PROVIDER_UNAVAILABLE
    }
}
