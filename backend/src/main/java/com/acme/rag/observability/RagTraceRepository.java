package com.acme.rag.observability;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

@Repository
public class RagTraceRepository {
    private final JdbcTemplate jdbc;
    private final JdbcClient client;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Injected JDBC templates are private Spring service collaborators and are not exposed")
    public RagTraceRepository(JdbcTemplate jdbc, JdbcClient client) {
        this.jdbc = jdbc;
        this.client = client;
    }

    public void save(RagTrace trace, String queryHash) {
        jdbc.update("""
                INSERT INTO rag_traces (id, user_id, query_hash, retrieval_duration_ms, generation_duration_ms,
                    total_duration_ms, vector_candidate_count, keyword_candidate_count, exact_candidate_count,
                    context_chunk_count, citation_count, model_provider, model_id, grounding_status, status, error_category, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, trace.id(), trace.userId(), queryHash, trace.retrievalDurationMs(), trace.generationDurationMs(),
                trace.totalDurationMs(), trace.vectorCandidateCount(), trace.keywordCandidateCount(),
                trace.exactCandidateCount(), trace.contextChunkCount(), trace.citationCount(), trace.modelProvider(),
                trace.modelId(), trace.groundingStatus(), trace.status(), trace.errorCategory(), Timestamp.from(trace.createdAt()));
    }

    public Optional<RagTrace> find(UUID id, String requestedBy, boolean admin) {
        String select = """
                SELECT id, user_id, retrieval_duration_ms, generation_duration_ms, total_duration_ms,
                    vector_candidate_count, keyword_candidate_count, exact_candidate_count, context_chunk_count,
                    citation_count, model_provider, model_id, grounding_status, status, error_category, created_at
                FROM rag_traces WHERE id = :id
                """ + (admin ? "" : " AND user_id = :requestedBy");
        var query = client.sql(select).param("id", id);
        if (!admin) {
            query = query.param("requestedBy", requestedBy);
        }
        return query
                .query(RagTraceRepository::mapTrace).optional();
    }

    private static RagTrace mapTrace(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new RagTrace(rs.getObject("id", UUID.class), rs.getString("user_id"),
                rs.getLong("retrieval_duration_ms"), rs.getLong("generation_duration_ms"),
                rs.getLong("total_duration_ms"), rs.getInt("vector_candidate_count"),
                rs.getInt("keyword_candidate_count"), rs.getInt("exact_candidate_count"),
                rs.getInt("context_chunk_count"), rs.getInt("citation_count"), rs.getString("model_provider"),
                rs.getString("model_id"), rs.getString("grounding_status"), rs.getString("status"),
                rs.getString("error_category"), rs.getTimestamp("created_at").toInstant());
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
