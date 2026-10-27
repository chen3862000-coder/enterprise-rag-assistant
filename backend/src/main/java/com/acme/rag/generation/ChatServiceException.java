package com.acme.rag.generation;

import org.springframework.http.HttpStatus;

public class ChatServiceException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String code;
    private final HttpStatus status;
    private final String safeMessage;

    public ChatServiceException(String code, HttpStatus status, String safeMessage, Throwable cause) {
        super(safeMessage, cause);
        this.code = code;
        this.status = status;
        this.safeMessage = safeMessage;
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    public String safeMessage() {
        return safeMessage;
    }
}
