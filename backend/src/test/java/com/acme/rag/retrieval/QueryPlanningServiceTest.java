package com.acme.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class QueryPlanningServiceTest {
    @Test
    void chineseDefinitionHasDeterministicFallbackAndOriginalBaseline() {
        QueryPlanningService service = service((model, key, timeout, system, request) -> null, false, 1500);

        var planned = service.plan("什么是OKR");

        assertThat(planned.original()).isEqualTo("什么是OKR");
        assertThat(planned.normalized()).isEqualTo("什么是OKR");
        assertThat(planned.plan().intent()).isEqualTo(QueryIntent.UNKNOWN);
        assertThat(planned.diagnostics().status()).isEqualTo("DISABLED");
        assertThat(planned.retrievalVariants(4, 3)).first()
                .satisfies(variant -> assertThat(variant.channels())
                        .containsExactlyInAnyOrder(QueryPlanningService.Channel.VECTOR,
                                QueryPlanningService.Channel.KEYWORD, QueryPlanningService.Channel.EXACT));
    }

    @Test
    void plannerTimeoutFallsBackWithoutLanguageSpecificParsing() {
        QueryPlannerClient slowClient = (model, key, timeout, system, request) -> {
            throw new java.net.http.HttpTimeoutException("synthetic timeout");
        };
        QueryPlanningService service = service(slowClient, true, 10);

        var planned = service.plan("什么是OKR");

        assertThat(planned.normalized()).isEqualTo("什么是OKR");
        assertThat(planned.plan().intent()).isEqualTo(QueryIntent.UNKNOWN);
        assertThat(planned.plan().semanticQuery()).isEqualTo("什么是OKR");
        assertThat(planned.diagnostics().status()).isEqualTo("FALLBACK");
        assertThat(planned.diagnostics().fallbackReason()).isEqualTo("TIMEOUT");
    }

    @Test
    void plannerTimeoutKeepsOrdinaryEnglishQueryAsDeterministicFallback() {
        QueryPlannerClient slowClient = (model, key, timeout, system, request) -> {
            throw new java.net.http.HttpTimeoutException("synthetic timeout");
        };

        var planned = service(slowClient, true, 10).plan("How do I rotate the API key?");

        assertThat(planned.plan().subject()).isEqualTo("How do I rotate the API key?");
        assertThat(planned.plan().semanticQuery()).isEqualTo("How do I rotate the API key?");
        assertThat(planned.diagnostics().fallbackReason()).isEqualTo("TIMEOUT");
        assertThat(planned.retrievalVariants(4, 3)).extracting(QueryPlanningService.QueryVariant::text)
                .contains("How do I rotate the API key?");
    }

    @Test
    void acceptsBoundedChineseAndEnglishEquivalentQueriesAndRetainsIdentifiers() {
        String plan = """
                {"intent":"LOOKUP","subject":"OKR","constraints":[],
                "lexical_queries":["OKR","目标与关键结果"],"semantic_query":"objectives and key results",
                "alternative_queries":["objectives and key results framework"],"language":"zh"}
                """;
        var planned = service((model, key, timeout, system, request) -> plan, true, 1500).plan("什么是OKR");

        assertThat(planned.diagnostics().status()).isEqualTo("SUCCEEDED");
        assertThat(planned.plan().lexicalQueries()).contains("目标与关键结果");
        assertThat(planned.retrievalVariants(4, 3)).extracting(QueryPlanningService.QueryVariant::text)
                .contains("什么是OKR", "OKR", "目标与关键结果", "objectives and key results");
    }

    @Test
    void plannerPromptSeparatesProcedureSubjectFromRequestedActionWithoutChangingIdentifiers() {
        String plan = """
                {"intent":"PROCEDURE","subject":"CSD sequence function","constraints":["usage"],
                "lexical_queries":["CSD","sequence function"],
                "semantic_query":"CSD sequence function usage",
                "alternative_queries":["CSD sequence function API"],"language":"zh"}
                """;
        java.util.List<String> prompts = new java.util.ArrayList<>();
        QueryPlannerClient client = (model, key, timeout, system, request) -> {
            prompts.add(system);
            return plan;
        };

        var result = service(client, true, 1500).plan("解释下csd序列函数怎么用");

        assertThat(result.plan().intent()).isEqualTo(QueryIntent.PROCEDURE);
        assertThat(result.plan().lexicalQueries()).contains("CSD", "sequence function");
        assertThat(prompts.getFirst()).contains("Separate the stable subject/function name")
                .contains("without dropping that language constraint")
                .contains("do not silently correct a spelling variant");
    }

    @Test
    void rejectsUnknownSecurityFieldsAndFallsBackWithoutUsingPlan() {
        String invalidPlan = """
                {"intent":"LOOKUP","subject":"public","constraints":[],"lexical_queries":[],
                "semantic_query":"public","alternative_queries":[],"language":"en","roles":["PUBLIC"]}
                """;

        var planned = service((model, key, timeout, system, request) -> invalidPlan, true, 1500).plan("What is OKR?");

        assertThat(planned.diagnostics().status()).isEqualTo("FALLBACK");
        assertThat(planned.diagnostics().fallbackReason()).isEqualTo("INVALID_SCHEMA");
        assertThat(planned.plan().subject()).contains("OKR");
    }

    @Test
    void rejectsMissingFieldsAndNonTextArrayItems() {
        String missingField = """
                {"intent":"LOOKUP","subject":"OKR","constraints":[],"lexical_queries":[],
                "semantic_query":"OKR","alternative_queries":[]}
                """;
        String invalidItem = """
                {"intent":"LOOKUP","subject":"OKR","constraints":[1],"lexical_queries":[],
                "semantic_query":"OKR","alternative_queries":[],"language":"en"}
                """;

        var missing = service((model, key, timeout, system, request) -> missingField, true, 1500)
                .plan("What is OKR?");
        var invalid = service((model, key, timeout, system, request) -> invalidItem, true, 1500)
                .plan("What is OKR?");

        assertThat(missing.diagnostics().fallbackReason()).isEqualTo("INVALID_SCHEMA");
        assertThat(invalid.diagnostics().fallbackReason()).isEqualTo("INVALID_FIELDS_OR_BOUNDS");
        assertThat(missing.plan().intent()).isEqualTo(QueryIntent.UNKNOWN);
        assertThat(invalid.plan().intent()).isEqualTo(QueryIntent.UNKNOWN);
    }

    @Test
    void categorizesMalformedProviderJsonWithoutPersistingItsContents() {
        QueryPlannerClient malformed = (model, key, timeout, system, request) -> "not-json-secret-response";

        var planned = service(malformed, true, 1500).plan("解释下Choice的csd函数");

        assertThat(planned.diagnostics().fallbackReason()).isEqualTo("INVALID_JSON");
        assertThat(planned.diagnostics().fallbackReason()).doesNotContain("secret", "response");
        assertThat(planned.plan().intent()).isEqualTo(QueryIntent.UNKNOWN);
    }

    @Test
    void rejectsOversizedPlannerResponseAndUsesOriginalBaseline() {
        QueryPlannerClient oversized = (model, key, timeout, system, request) -> "x".repeat(12_001);

        var planned = service(oversized, true, 1500).plan("What is OKR?");

        assertThat(planned.diagnostics().fallbackReason()).isEqualTo("RESPONSE_TOO_LARGE");
        assertThat(planned.retrievalVariants(4, 3)).first()
                .extracting(QueryPlanningService.QueryVariant::text).isEqualTo("What is OKR?");
    }

    @Test
    void reportsProviderFailureSeparatelyFromInvalidStructuredPlan() {
        QueryPlannerClient unavailable = (model, key, timeout, system, request) -> {
            throw new QueryPlannerProviderException("PROVIDER_AUTH_ERROR");
        };

        var planned = service(unavailable, true, 1500).plan("解释下Choice的csd函数");

        assertThat(planned.diagnostics().status()).isEqualTo("FALLBACK");
        assertThat(planned.diagnostics().fallbackReason()).isEqualTo("PROVIDER_AUTH_ERROR");
        assertThat(planned.plan().intent()).isEqualTo(QueryIntent.UNKNOWN);
        assertThat(planned.original()).isEqualTo("解释下Choice的csd函数");
    }

    @Test
    void enforcesPlanListBoundsAtTheTypedContract() {
        assertThatThrownByInvalidPlan();
    }

    private static void assertThatThrownByInvalidPlan() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new QueryPlan(QueryIntent.LOOKUP, "OKR", List.of(),
                List.of("a", "b", "c", "d", "e"), "OKR", List.of(), "zh"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static QueryPlanningService service(QueryPlannerClient planner, boolean enabled, long timeoutMs) {
        return new QueryPlanningService(planner, new ObjectMapper(), enabled, "deepseek", "deepseek-chat",
                "test-api-key", timeoutMs, 4, 3);
    }
}
