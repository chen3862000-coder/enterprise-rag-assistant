package com.acme.rag.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import com.acme.rag.document.ingestion.IngestionException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> denied(AccessDeniedException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ApiError("FORBIDDEN", "Access is denied"));
    }

    @ExceptionHandler({IngestionException.class, IllegalArgumentException.class, MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class})
    public ResponseEntity<ApiError> invalidRequest(RuntimeException exception) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_REQUEST", safeMessage(exception)));
    }

    private static String safeMessage(RuntimeException exception) {
        return exception.getMessage() == null ? "Request validation failed" : exception.getMessage();
    }

    public record ApiError(String code, String message) {}
}
