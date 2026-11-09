package com.acme.rag.retrieval;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Enforces the planner's exact response schema and serializes its bounded request payload. */
final class QueryPlanResponseParser {
    private static final int MAX_RESPONSE_CHARS = 12_000;
    private static final Set<String> PLAN_FIELDS = Set.of("intent", "subject", "constraints", "lexical_queries",
            "semantic_query", "alternative_queries", "language");

    private final ObjectMapper json;

    QueryPlanResponseParser(ObjectMapper json) {
        this.json = json;
    }

    /** Serializes only original and normalized query text for the planner request. */
    String requestPayload(String original, String normalized) throws IOException {
        return json.writeValueAsString(Map.of("original_query", original, "normalized_query", normalized));
    }

    /** Parses exactly the allowed fields; malformed, extra, or oversized data is rejected. */
    QueryPlan parse(String content) throws IOException {
        JsonNode root = parseRoot(content);
        if (root == null || !root.isObject() || !hasExactFields(root)) {
            throw new QueryPlannerValidationException("INVALID_SCHEMA");
        }
        return parseFields(root);
    }

    private JsonNode parseRoot(String content) throws IOException {
        if (content == null || content.isBlank()) {
            throw new QueryPlannerValidationException("EMPTY_RESPONSE");
        }
        if (content.length() > MAX_RESPONSE_CHARS) {
            throw new QueryPlannerValidationException("RESPONSE_TOO_LARGE");
        }
        try {
            return json.readTree(content);
        } catch (JacksonException exception) {
            throw new QueryPlannerValidationException("INVALID_JSON");
        }
    }

    private QueryPlan parseFields(JsonNode root) {
        QueryIntent intent = parseIntent(root);
        try {
            return new QueryPlan(intent, requiredText(root, "subject"), stringList(root, "constraints"),
                    stringList(root, "lexical_queries"), requiredText(root, "semantic_query"),
                    stringList(root, "alternative_queries"), requiredText(root, "language"));
        } catch (IllegalArgumentException exception) {
            throw new QueryPlannerValidationException("INVALID_FIELDS_OR_BOUNDS");
        }
    }

    private static QueryIntent parseIntent(JsonNode root) {
        try {
            return QueryIntent.valueOf(requiredText(root, "intent"));
        } catch (IllegalArgumentException exception) {
            throw new QueryPlannerValidationException("INVALID_INTENT");
        }
    }

    private static boolean hasExactFields(JsonNode root) {
        if (root.size() != PLAN_FIELDS.size()) {
            return false;
        }
        for (var property : root.properties()) {
            if (!PLAN_FIELDS.contains(property.getKey())) {
                return false;
            }
        }
        return true;
    }

    private static String requiredText(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual()) {
            throw new QueryPlannerValidationException("INVALID_TEXT_FIELD");
        }
        return value.textValue();
    }

    private static List<String> stringList(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isArray()) {
            throw new QueryPlannerValidationException("INVALID_ARRAY_FIELD");
        }
        List<String> result = new ArrayList<>();
        value.forEach(item -> {
            if (!item.isTextual()) {
                throw new QueryPlannerValidationException("INVALID_ARRAY_ITEM");
            }
            result.add(item.textValue());
        });
        return result;
    }
}
