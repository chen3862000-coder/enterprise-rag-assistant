package com.acme.rag.retrieval;

import java.io.IOException;
import java.time.Duration;

/** Makes the bounded structured model request independently of answer generation configuration. */
@FunctionalInterface
public interface QueryPlannerClient {
    String plan(String model, String apiKey, Duration timeout, String systemPrompt, String userPayload)
            throws IOException, InterruptedException;
}
