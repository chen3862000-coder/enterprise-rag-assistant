package com.acme.rag.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.rag.M2TestConfiguration;
import com.acme.rag.evaluation.EvaluationCategory;
import com.acme.rag.evaluation.EvaluationMetrics;
import com.acme.rag.evaluation.EvaluationRunRepository;
import com.acme.rag.evaluation.EvaluationRunResult;
import com.acme.rag.evaluation.EvaluationScenarioResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {"spring.ai.model.chat=none", "app.seed.enabled=false"})
@Import(M2TestConfiguration.class)
@Testcontainers
class EvaluationTracePersistenceIT {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:pg17")
            .withDatabaseName("rag_observability_test").withUsername("rag").withPassword("rag");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired RagTraceRepository traces;
    @Autowired EvaluationRunRepository runs;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clearTables() {
        jdbc.update("DELETE FROM evaluation_scenario_results");
        jdbc.update("DELETE FROM evaluation_runs");
        jdbc.update("DELETE FROM rag_traces");
    }

    @Test
    void flywaySchemaHasRenamedGateAndCitationValidationMetrics() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='evaluation_runs' AND column_name='evidence_gate_accuracy'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='evaluation_runs' AND column_name='citation_id_validation_accuracy'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='evaluation_scenario_results' AND column_name='gate_reason'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void tracePersistsOnlyHashAndAccessIsScopedByOwnerOrAdmin() {
        UUID id = UUID.randomUUID();
        String hash = "a".repeat(64);
        traces.save(trace(id, "alice"), hash);

        assertThat(traces.find(id, "alice", false)).isPresent();
        assertThat(traces.find(id, "bob", false)).isEmpty();
        assertThat(traces.find(id, "carol", true)).isPresent();
        assertThat(traces.queryHash(id)).contains(hash);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_name = 'rag_traces' AND column_name IN ('raw_query', 'retrieved_text', 'prompt')",
                Integer.class)).isZero();
    }

    @Test
    void failedTraceStoresBoundedCategoryWithoutQueryText() {
        UUID id = UUID.randomUUID();
        RagTrace failed = new RagTrace(id, "alice", 3, 0, 3, 0, 0, 0, 0, 0,
                false, "NO_AUTHORIZED_CANDIDATES", "none", "none", "INSUFFICIENT_EVIDENCE",
                "FAILED", "RETRIEVAL_STORAGE_ERROR", Instant.now());
        traces.save(failed, "c".repeat(64));

        assertThat(traces.find(id, "alice", false)).get()
                .extracting(RagTrace::status, RagTrace::errorCategory, RagTrace::modelProvider)
                .containsExactly("FAILED", "RETRIEVAL_STORAGE_ERROR", "none");
    }

    @Test
    void evaluationRunAndScenarioResultsArePersistedAndReloaded() {
        Instant now = Instant.now();
        EvaluationScenarioResult scenario = new EvaluationScenarioResult("rag-001", EvaluationCategory.FACT_LOOKUP,
                List.of("doc-policy"), List.of("doc-policy"), List.of(), List.of(), 2, Map.of("doc-policy", 2),
                1.0, 1.0, true, false, true, true, true, "EXACT_IDENTIFIER_MATCH",
                new EvaluationScenarioResult.GateDiagnostics(1, 0.8, 0.02, 0.8, 0.02, true,
                        1, 1, 1, Map.of("CURRENT_AND", true),
                        new EvaluationScenarioResult.GateDiagnostics.PrefixShadowDiagnostics(
                                List.of("doc-policy"), 1.0, 1.0, true, 0.8)), true);
        EvaluationRunResult run = new EvaluationRunResult(UUID.randomUUID(), "dataset-v1", "OFFLINE_RETRIEVAL",
                "test-embedding", Map.of("candidateDepth", 10), 1,
                new EvaluationMetrics(1.0, 1.0, 0, 1.0, 1.0, 1.0,
                        Map.of("CURRENT_AND", new EvaluationMetrics.GateAblationMetrics(1, 0, 0, 0, 1.0, 0.0, 0.0)),
                        new EvaluationMetrics.PrefixShadowMetrics(true, 1.0, 1.0, 1.0, 0, 0)),
                "SUCCEEDED", now, now, List.of(scenario));

        runs.save(run, "carol", Map.of("candidateDepth", 10, "fusedDepth", 8, "rrfConstant", 60));

        assertThat(runs.find(run.runId(), "carol").scenarios()).containsExactly(scenario);
        assertThat(runs.find(run.runId(), "carol").metrics().gateAblationMetrics())
                .containsEntry("CURRENT_AND", new EvaluationMetrics.GateAblationMetrics(1, 0, 0, 0, 1.0, 0.0, 0.0));
        assertThat(runs.find(run.runId(), "carol").metrics().prefixShadowMetrics().available()).isTrue();
        assertThat(runs.recent(10)).extracting(summary -> summary.requestedBy()).contains("carol");
    }

    private static RagTrace trace(UUID id, String username) {
        Instant now = Instant.now();
        return new RagTrace(id, username, 10, 20, 30, 4, 3, 1, 5, 2, true, "LEXICAL_AND_RELEVANCE_MATCH",
                "deepseek", "deepseek-chat", "CITATIONS_PRESENT", "SUCCEEDED", null, now);
    }
}
