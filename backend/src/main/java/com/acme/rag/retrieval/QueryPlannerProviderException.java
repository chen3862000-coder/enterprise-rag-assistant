package com.acme.rag.retrieval;

import java.io.IOException;

/** Safe bounded provider failure category; deliberately excludes response bodies and credentials. */
final class QueryPlannerProviderException extends IOException {
    private final String category;

    QueryPlannerProviderException(String category) {
        super(category);
        this.category = category;
    }

    String category() {
        return category;
    }
}
