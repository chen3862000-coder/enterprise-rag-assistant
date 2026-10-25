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
    void evaluationRunAndScenarioResultsArePersistedAndReloaded() {
        Instant now = Instant.now();
        EvaluationScenarioResult scenario = new EvaluationScenarioResult("rag-001", EvaluationCategory.FACT_LOOKUP,
                List.of("doc-policy"), List.of("doc-policy"), List.of(), List.of(), 2, Map.of("doc-policy", 2),
                1.0, 1.0, false, true, true, true, true);
        EvaluationRunResult run = new EvaluationRunResult(UUID.randomUUID(), "dataset-v1", "OFFLINE_RETRIEVAL",
                "test-embedding", 1, new EvaluationMetrics(1.0, 1.0, 0, 1.0, 1.0, 1.0),
                "SUCCEEDED", now, now, List.of(scenario));

        runs.save(run, "carol", Map.of("candidateDepth", 10, "fusedDepth", 8, "rrfConstant", 60));

        assertThat(runs.find(run.runId(), "carol").scenarios()).containsExactly(scenario);
        assertThat(runs.recent(10)).extracting(summary -> summary.requestedBy()).contains("carol");
    }

    private static RagTrace trace(UUID id, String username) {
        Instant now = Instant.now();
        return new RagTrace(id, username, 10, 20, 30, 4, 3, 1, 5, 2,
                "deepseek", "deepseek-chat", "SUPPORTED", "SUCCEEDED", null, now);
    }
}
