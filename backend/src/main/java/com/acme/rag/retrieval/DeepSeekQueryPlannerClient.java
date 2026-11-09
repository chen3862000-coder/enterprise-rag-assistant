package com.acme.rag.retrieval;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/** Sends one low-temperature structured query-planning request to the DeepSeek chat API. */
@Component
public final class DeepSeekQueryPlannerClient implements QueryPlannerClient {
    private static final URI ENDPOINT = URI.create("https://api.deepseek.com/chat/completions");
    private final HttpClient http;
    private final ObjectMapper json;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "ObjectMapper is a private shared Spring collaborator")
    public DeepSeekQueryPlannerClient(ObjectMapper json) {
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        this.json = json;
    }

    @Override
    public String plan(String model, String apiKey, Duration timeout, String systemPrompt, String userPayload)
            throws IOException, InterruptedException {
        String body = json.writeValueAsString(Map.of(
                "model", model,
                "temperature", 0,
                "max_tokens", 300,
                "response_format", Map.of("type", "json_object"),
                "messages", List.of(Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPayload))));
        HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new QueryPlannerProviderException(providerFailureCategory(response.statusCode()));
        }
        JsonNode root = json.readTree(response.body());
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (!content.isTextual()) {
            throw new QueryPlannerProviderException("PROVIDER_RESPONSE_INVALID");
        }
        return content.textValue();
    }

    private static String providerFailureCategory(int status) {
        if (status == 401 || status == 403) {
            return "PROVIDER_AUTH_ERROR";
        }
        if (status == 429) {
            return "PROVIDER_RATE_LIMITED";
        }
        if (status >= 500) {
            return "PROVIDER_SERVER_ERROR";
        }
        return "PROVIDER_HTTP_ERROR";
    }
}
