package com.acme.rag.retrieval;

/** Safe planner validation category that never includes provider output or query text. */
final class QueryPlannerValidationException extends IllegalArgumentException {
    private final String category;

    QueryPlannerValidationException(String category) {
        super(category);
        this.category = category;
    }

    String category() {
        return category;
    }
}
