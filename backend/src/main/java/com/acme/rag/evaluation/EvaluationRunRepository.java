package com.acme.rag.evaluation;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

@Repository
public class EvaluationRunRepository {
    private final JdbcTemplate jdbc;
    private final JdbcClient client;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Injected JDBC, transaction, and mapper beans are private collaborators and are not exposed")
    public EvaluationRunRepository(JdbcTemplate jdbc, JdbcClient client, TransactionTemplate transactions, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.client = client;
        this.transactions = transactions;
        this.mapper = mapper;
    }

    public void save(EvaluationRunResult run, String requestedBy, Map<String, Object> retrievalConfiguration) {
        transactions.executeWithoutResult(status -> {
            EvaluationMetrics metrics = run.metrics();
            jdbc.update("""
                    INSERT INTO evaluation_runs (id, requested_by, dataset_version, evaluation_method, embedding_model_id,
                        retrieval_configuration, scenario_count, recall_at_5, mean_reciprocal_rank, acl_leakage_count,
                        freshness_accuracy, citation_logic_accuracy, abstention_accuracy, status, started_at, completed_at)
                    VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, run.runId(), requestedBy, run.datasetVersion(), run.method(), run.embeddingModelId(),
                    json(retrievalConfiguration), run.scenarioCount(), metrics.recallAt5(), metrics.meanReciprocalRank(),
                    metrics.aclLeakageCount(), metrics.freshnessAccuracy(), metrics.citationLogicAccuracy(),
                    metrics.abstentionAccuracy(), run.status(), Timestamp.from(run.startedAt()), Timestamp.from(run.completedAt()));
            for (EvaluationScenarioResult result : run.scenarios()) {
                saveScenario(run.runId(), result);
            }
        });
    }

    public EvaluationRunResult find(UUID id, String requestedBy) {
        RunRow run = client.sql("""
                SELECT id, requested_by, dataset_version, evaluation_method, embedding_model_id, scenario_count, recall_at_5,
                    mean_reciprocal_rank, acl_leakage_count, freshness_accuracy, citation_logic_accuracy, abstention_accuracy,
                    status, started_at, completed_at
                FROM evaluation_runs WHERE id = :id
                """).param("id", id).query((rs, row) -> new RunRow(
                rs.getObject("id", UUID.class), requestedBy, rs.getString("dataset_version"), rs.getString("evaluation_method"),
                rs.getString("embedding_model_id"), rs.getInt("scenario_count"), rs.getDouble("recall_at_5"),
                rs.getDouble("mean_reciprocal_rank"), rs.getInt("acl_leakage_count"),
                rs.getDouble("freshness_accuracy"), rs.getDouble("citation_logic_accuracy"),
                rs.getDouble("abstention_accuracy"), rs.getString("status"), rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("completed_at").toInstant())).single();
        List<EvaluationScenarioResult> results = client.sql("""
                SELECT scenario_id, category, expected_document_ids, retrieved_document_ids, missing_document_ids,
                    forbidden_document_ids, expected_version, retrieved_versions, reciprocal_rank, recall_at_5,
                    acl_leakage, freshness_correct, citation_logic_correct, abstention_correct, passed
                FROM evaluation_scenario_results WHERE run_id = :id ORDER BY scenario_id
                """).param("id", id).query((rs, row) -> scenarioResult(rs)).list();
        return new EvaluationRunResult(run.id(), run.datasetVersion(), run.method(), run.embeddingModelId(), run.scenarioCount(),
                new EvaluationMetrics(run.recallAt5(), run.mrr(), run.leakage(), run.freshness(), run.citation(), run.abstention()),
                run.status(), run.startedAt(), run.completedAt(), results);
    }

    public List<EvaluationRunSummary> recent(int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Evaluation result limit must be between 1 and 100");
        }
        return client.sql("""
                SELECT id, requested_by, dataset_version, evaluation_method, embedding_model_id, scenario_count,
                    recall_at_5, mean_reciprocal_rank, acl_leakage_count, freshness_accuracy,
                    citation_logic_accuracy, abstention_accuracy, status, started_at, completed_at
                FROM evaluation_runs ORDER BY started_at DESC LIMIT :limit
                """).param("limit", limit).query((rs, row) -> new EvaluationRunSummary(
                rs.getObject("id", UUID.class), rs.getString("requested_by"), rs.getString("dataset_version"),
                rs.getString("evaluation_method"), rs.getString("embedding_model_id"), rs.getInt("scenario_count"),
                new EvaluationMetrics(rs.getDouble("recall_at_5"), rs.getDouble("mean_reciprocal_rank"),
                        rs.getInt("acl_leakage_count"), rs.getDouble("freshness_accuracy"),
                        rs.getDouble("citation_logic_accuracy"), rs.getDouble("abstention_accuracy")),
                rs.getString("status"), rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("completed_at").toInstant())).list();
    }

    private void saveScenario(UUID runId, EvaluationScenarioResult result) {
        jdbc.update("""
                INSERT INTO evaluation_scenario_results (run_id, scenario_id, category, expected_document_ids,
                    retrieved_document_ids, missing_document_ids, forbidden_document_ids, expected_version,
                    retrieved_versions, reciprocal_rank, recall_at_5, acl_leakage, freshness_correct,
                    citation_logic_correct, abstention_correct, passed)
                VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?)
                """, runId, result.scenarioId(), result.category().name(), json(result.expectedDocumentIds()),
                json(result.retrievedDocumentIds()), json(result.missingDocumentIds()), json(result.forbiddenDocumentIds()),
                result.expectedVersion(), json(result.retrievedVersions()), result.reciprocalRank(), result.recallAt5(),
                result.aclLeakage(), result.freshnessCorrect(), result.citationLogicCorrect(), result.abstentionCorrect(), result.passed());
    }

    private EvaluationScenarioResult scenarioResult(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new EvaluationScenarioResult(rs.getString("scenario_id"), EvaluationCategory.valueOf(rs.getString("category")),
                readList(rs.getString("expected_document_ids")), readList(rs.getString("retrieved_document_ids")),
                readList(rs.getString("missing_document_ids")), readList(rs.getString("forbidden_document_ids")),
                (Integer) rs.getObject("expected_version"), readMap(rs.getString("retrieved_versions")),
                rs.getDouble("reciprocal_rank"), rs.getDouble("recall_at_5"), rs.getBoolean("acl_leakage"),
                rs.getBoolean("freshness_correct"), rs.getBoolean("citation_logic_correct"),
                rs.getBoolean("abstention_correct"), rs.getBoolean("passed"));
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Unable to serialize evaluation result", exception);
        }
    }

    private List<String> readList(String value) {
        try {
            return mapper.readerForListOf(String.class).readValue(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Stored evaluation list is invalid", exception);
        }
    }

    private Map<String, Integer> readMap(String value) {
        try {
            return mapper.readValue(value, mapper.getTypeFactory().constructMapType(Map.class, String.class, Integer.class));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Stored evaluation map is invalid", exception);
        }
    }

    private record RunRow(UUID id, String requestedBy, String datasetVersion, String method, String embeddingModelId, int scenarioCount,
                          double recallAt5, double mrr, int leakage, double freshness, double citation,
                          double abstention, String status, Instant startedAt, Instant completedAt) {}
}
