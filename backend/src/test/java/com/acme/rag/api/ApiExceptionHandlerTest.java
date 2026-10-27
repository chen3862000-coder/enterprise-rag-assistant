package com.acme.rag.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.rag.document.ingestion.IngestionException;
import com.acme.rag.generation.ChatServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ApiExceptionHandlerTest {
    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void mapsInputConflictContentAndProviderErrorsToStableStatusAndCodes() {
        assertError(IngestionException.Kind.INVALID_INPUT, HttpStatus.BAD_REQUEST, "INGESTION_INVALID_INPUT");
        assertError(IngestionException.Kind.CONFLICT, HttpStatus.CONFLICT, "INGESTION_CONFLICT");
        assertError(IngestionException.Kind.UNPROCESSABLE, HttpStatus.UNPROCESSABLE_ENTITY, "INGESTION_UNPROCESSABLE");
        assertError(IngestionException.Kind.PROVIDER_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE,
                "INGESTION_PROVIDER_UNAVAILABLE");
    }

    @Test
    void mapsChatStorageFailureToServiceUnavailableApiError() {
        var response = handler.chatService(new ChatServiceException("STORAGE_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE,
                "Document storage is temporarily unavailable", new IllegalStateException("private detail")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isEqualTo(new ApiExceptionHandler.ApiError(
                "STORAGE_UNAVAILABLE", "Document storage is temporarily unavailable"));
    }

    private void assertError(IngestionException.Kind kind, HttpStatus status, String code) {
        var response = handler.ingestion(new IngestionException(kind, "Safe validation detail"));

        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody()).isEqualTo(new ApiExceptionHandler.ApiError(code, "Safe validation detail"));
    }
}
