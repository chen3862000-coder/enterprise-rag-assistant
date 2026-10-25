package com.acme.rag.document.ingestion;

public class IngestionException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public IngestionException(String message) {
        super(message);
    }

    public IngestionException(String message, Throwable cause) {
        super(message, cause);
    }
}
