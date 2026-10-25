package com.acme.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ExactIdentifierExtractorTest {
    private final ExactIdentifierExtractor extractor = new ExactIdentifierExtractor();

    @Test
    void extractsTechnicalIdentifiersButNotOrdinaryWords() {
        assertThat(extractor.extract("How should I handle HTTP 429 and GW-204 from API/v1?"))
                .containsExactly("429", "gw-204", "api/v1");
    }
}
