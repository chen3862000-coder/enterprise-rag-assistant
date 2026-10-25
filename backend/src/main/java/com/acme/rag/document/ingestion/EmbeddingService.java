package com.acme.rag.document.ingestion;

import java.util.List;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
final class EmbeddingService {
    private static final int MAX_ATTEMPTS = 3;

    private final EmbeddingModel model;
    private final int dimensions;

    EmbeddingService(
            EmbeddingModel model,
            @Value("${app.ingestion.embedding-dimensions:768}") int dimensions) {
        this.model = model;
        this.dimensions = dimensions;
    }

    List<float[]> embed(List<String> texts) {
        List<float[]> vectors = embedWithRetry(texts);
        if (vectors.size() != texts.size()) {
            throw new IngestionException("Embedding provider returned an unexpected number of vectors");
        }
        if (vectors.stream().anyMatch(vector -> vector == null || vector.length != dimensions)) {
            throw new IngestionException("Embedding dimension does not match configured index dimension");
        }
        return vectors;
    }

    private List<float[]> embedWithRetry(List<String> texts) {
        RuntimeException failure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                EmbeddingResponse response = model.embedForResponse(texts);
                return response.getResults().stream().map(result -> result.getOutput()).toList();
            } catch (org.springframework.ai.retry.NonTransientAiException exception) {
                throw new IngestionException("Embedding provider rejected the request", exception);
            } catch (org.springframework.ai.retry.TransientAiException exception) {
                failure = exception;
            }
        }
        throw new IngestionException("Embedding provider failed after bounded retries", failure);
    }
}
