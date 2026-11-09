package com.acme.rag.observability;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

@Repository
public class RagTraceRepository {
    private final JdbcTemplate jdbc;
    private final JdbcClient client;
    private final ObjectMapper json;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Injected JDBC templates are private Spring service collaborators and are not exposed")
    public RagTraceRepository(JdbcTemplate jdbc, JdbcClient client, ObjectMapper json) {
        this.jdbc = jdbc;
        this.client = client;
        this.json = json;
    }

    public void save(RagTrace trace, String queryHash) {
        jdbc.update("""
                INSERT INTO rag_traces (id, user_id, query_hash, retrieval_duration_ms, generation_duration_ms,
                    total_duration_ms, vector_candidate_count, keyword_candidate_count, exact_candidate_count,
                    context_chunk_count, citation_count, evidence_sufficient, evidence_gate_reason,
                    model_provider, model_id, grounding_status, status, error_category, response_mode, created_at,
                    query_planner_provider, query_planner_model, query_planner_latency_ms, query_planner_status,
                    query_planner_fallback_reason, query_planner_intent, query_planner_generated_count,
                    query_planner_deduplicated_count, query_planner_channel_counts, query_planner_candidate_count,
                    query_planner_retrieval_counts)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    CAST(? AS jsonb), ?, CAST(? AS jsonb))
                """, trace.id(), trace.userId(), queryHash, trace.retrievalDurationMs(), trace.generationDurationMs(),
                trace.totalDurationMs(), trace.vectorCandidateCount(), trace.keywordCandidateCount(),
                trace.exactCandidateCount(), trace.contextChunkCount(), trace.citationCount(), trace.evidenceSufficient(),
                trace.evidenceGateReason(), trace.modelProvider(),
                trace.modelId(), trace.groundingStatus(), trace.status(), trace.errorCategory(), trace.responseMode(),
                Timestamp.from(trace.createdAt()), trace.queryPlanning().provider(), trace.queryPlanning().model(),
                trace.queryPlanning().latencyMs(), trace.queryPlanning().status(), trace.queryPlanning().fallbackReason(),
                trace.queryPlanning().intent().name(), trace.queryPlanning().generatedQueryCount(),
                trace.queryPlanning().deduplicatedQueryCount(), json.writeValueAsString(trace.queryPlanning().channelCandidateCounts()),
                trace.queryPlanning().candidateCount(), json.writeValueAsString(trace.queryPlanning().retrievalCandidateCounts()));
    }

    public Optional<RagTrace> find(UUID id, String requestedBy, boolean admin) {
        String select = """
                SELECT id, user_id, retrieval_duration_ms, generation_duration_ms, total_duration_ms,
                    vector_candidate_count, keyword_candidate_count, exact_candidate_count, context_chunk_count,
                    citation_count, evidence_sufficient, evidence_gate_reason, model_provider, model_id,
                    grounding_status, status, error_category, response_mode, created_at, query_planner_provider,
                    query_planner_model, query_planner_latency_ms, query_planner_status, query_planner_fallback_reason,
                    query_planner_intent, query_planner_generated_count, query_planner_deduplicated_count,
                    query_planner_channel_counts, query_planner_candidate_count, query_planner_retrieval_counts
                FROM rag_traces WHERE id = :id
                """ + (admin ? "" : " AND user_id = :requestedBy");
        var query = client.sql(select).param("id", id);
        if (!admin) {
            query = query.param("requestedBy", requestedBy);
        }
        return query
                .query(this::mapTrace).optional();
    }

    private RagTrace mapTrace(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new RagTrace(rs.getObject("id", UUID.class), rs.getString("user_id"),
                rs.getLong("retrieval_duration_ms"), rs.getLong("generation_duration_ms"),
                rs.getLong("total_duration_ms"), rs.getInt("vector_candidate_count"),
                rs.getInt("keyword_candidate_count"), rs.getInt("exact_candidate_count"),
                rs.getInt("context_chunk_count"), rs.getInt("citation_count"), rs.getBoolean("evidence_sufficient"),
                rs.getString("evidence_gate_reason"), rs.getString("model_provider"),
                rs.getString("model_id"), rs.getString("grounding_status"), rs.getString("status"),
                rs.getString("error_category"), rs.getString("response_mode"), rs.getTimestamp("created_at").toInstant(),
                new com.acme.rag.retrieval.QueryPlanningDiagnostics(rs.getString("query_planner_provider"),
                        rs.getString("query_planner_model"), rs.getInt("query_planner_latency_ms"),
                        rs.getString("query_planner_status"), rs.getString("query_planner_fallback_reason"),
                        com.acme.rag.retrieval.QueryIntent.valueOf(rs.getString("query_planner_intent")),
                        rs.getInt("query_planner_generated_count"), rs.getInt("query_planner_deduplicated_count"),
                        Map.of(),
                        rs.getInt("query_planner_candidate_count"),
                        Map.of()).withRetrieval(
                                mapCounts(rs.getString("query_planner_channel_counts")),
                                rs.getInt("query_planner_candidate_count"),
                                mapCounts(rs.getString("query_planner_retrieval_counts"))));
    }

    private Map<String, Integer> mapCounts(String value) {
        JsonNode counts = json.readTree(value);
        Map<String, Integer> result = new java.util.LinkedHashMap<>();
        for (var property : counts.properties()) {
            if (property.getValue().isIntegralNumber()) {
                result.put(property.getKey(), property.getValue().intValue());
            }
        }
        return Map.copyOf(result);
    }

    public Optional<String> queryHash(UUID id) {
        return client.sql("SELECT query_hash FROM rag_traces WHERE id = :id")
                .param("id", id).query(String.class).optional();
    }

    public int deleteOlderThan(int retentionDays) {
        if (retentionDays < 1 || retentionDays > 365) {
            throw new IllegalArgumentException("Trace retention must be between 1 and 365 days");
        }
        return jdbc.update("DELETE FROM rag_traces WHERE created_at < now() - (? * interval '1 day')", retentionDays);
    }
}
