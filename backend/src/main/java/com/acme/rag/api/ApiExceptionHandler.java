package com.acme.rag.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import com.acme.rag.document.ingestion.IngestionException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.dao.DataAccessException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> denied(AccessDeniedException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ApiError("FORBIDDEN", "Access is denied"));
    }

    @ExceptionHandler(IngestionException.class)
    public ResponseEntity<ApiError> ingestion(IngestionException exception) {
        HttpStatus status = switch (exception.kind()) {
            case INVALID_INPUT -> HttpStatus.BAD_REQUEST;
            case CONFLICT -> HttpStatus.CONFLICT;
            case UNPROCESSABLE -> HttpStatus.UNPROCESSABLE_ENTITY;
            case PROVIDER_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        String code = "INGESTION_" + exception.kind().name();
        return ResponseEntity.status(status).body(new ApiError(code, safeMessage(exception)));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiError> storageUnavailable(DataAccessException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiError("STORAGE_UNAVAILABLE", "Document storage is temporarily unavailable"));
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class, MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class, MaxUploadSizeExceededException.class})
    public ResponseEntity<ApiError> invalidRequest(Exception exception) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_REQUEST", safeMessage(exception)));
    }

    private static String safeMessage(Exception exception) {
        return exception.getMessage() == null ? "Request validation failed" : exception.getMessage();
    }

    public record ApiError(String code, String message) {}
}
