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

    @Test
    void excludesSentenceEndingPeriodsButPreservesInternalDots() {
        assertThat(extractor.extract("Check IR-204. Then call API.v1."))
                .containsExactly("ir-204", "api.v1");
    }

    @Test
    void extractsAsciiTechnicalNamesFromMixedCjkQueries() {
        assertThat(extractor.extract("什么是OKR"))
                .containsExactly("okr");
    }

    @Test
    void extractsUppercaseSymbolsAndRequestHeaderNames() {
        assertThat(extractor.extract("RDEXPEND and X-Request-Id"))
                .containsExactly("rdexpend", "x-request-id");
    }

    @Test
    void keepsAsciiErrorCodeSeparateFromAdjacentChineseText() {
        assertThat(extractor.extract("GW-204 错误代码 means what?"))
                .contains("gw-204");
    }
}
