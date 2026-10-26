package com.acme.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.rag.M2TestConfiguration;
import com.acme.rag.evaluation.DemoRoleResolver;
import com.acme.rag.evaluation.EvaluationDatasetLoader;
import com.acme.rag.evaluation.EvaluationScenarioEvaluator;
import com.acme.rag.evaluation.PrefixShadowEvaluator;
import com.acme.rag.evaluation.EvaluationScenarioResult;
import com.acme.rag.evaluation.EvaluationCategory;
import com.acme.rag.evaluation.EvaluationDataset;
import com.acme.rag.evaluation.EvaluationScenario;
import com.acme.rag.document.ingestion.IngestionResult;
import com.acme.rag.document.ingestion.IngestionRequest;
import com.acme.rag.document.ingestion.SyntheticSeedRunner;
import com.acme.rag.citation.CitationValidator;
import com.acme.rag.citation.SourceRepository;
import java.sql.Timestamp;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.List;
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

@SpringBootTest(properties = {
        "spring.ai.model.embedding=none",
        "spring.ai.model.chat=none",
        "app.seed.enabled=false"
})
@Testcontainers
@Import(M2TestConfiguration.class)
class HybridRetrievalSecurityIT {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:pg17")
            .withDatabaseName("rag_security_test")
            .withUsername("rag")
            .withPassword("rag");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired HybridRetrievalRepository retrieval;
    @Autowired SourceRepository sources;
    @Autowired EvaluationDatasetLoader datasetLoader;
    @Autowired EvaluationScenarioEvaluator scenarioEvaluator;
    @Autowired PrefixShadowEvaluator prefixShadow;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.acme.rag.document.ingestion.DocumentIngestionService ingestion;

    @BeforeEach
    void seedDocuments() {
        jdbc.update("DELETE FROM ingestion_jobs");
        jdbc.update("DELETE FROM document_chunks");
        jdbc.update("DELETE FROM document_permissions");
        jdbc.update("UPDATE documents SET current_version_id = NULL");
        jdbc.update("DELETE FROM document_versions");
        jdbc.update("DELETE FROM documents");
        createDocument("doc-public", "Public service FAQ", "PUBLIC", "PUBLIC", "Bob may use the public service.");
        createDocument("doc-hr", "Restricted leave policy", "CONFIDENTIAL", "HR", "HR-LEAVE-204 confidential leave detail.");
        createDocument("doc-engineering", "Gateway error guide", "INTERNAL", "ENGINEERING", "GW-204 gateway troubleshooting.");
        createDocument("doc-security", "Restricted incident exercise", "RESTRICTED", "SECURITY_ADMIN", "IR-204 restricted incident exercise.");
    }

    @Test
    void vectorKeywordExactAndSourceQueriesNeverReturnUnauthorizedChunks() {
        Set<String> aliceRoles = Set.of("PUBLIC", "ENGINEERING");
        float[] queryVector = new float[768];
        queryVector[0] = 1.0f;

        assertThat(retrieval.vector(queryVector, aliceRoles, 20)).extracting(RetrievalCandidate::documentId)
                .contains("doc-public", "doc-engineering").doesNotContain("doc-hr");
        assertThat(retrieval.keyword("confidential HR-LEAVE-204", aliceRoles, 20))
                .extracting(RetrievalCandidate::documentId).doesNotContain("doc-hr");
        assertThat(retrieval.exact("HR-LEAVE-204", aliceRoles, 20))
                .extracting(RetrievalCandidate::documentId).doesNotContain("doc-hr");
        assertThat(sources.findAuthorized("doc-hr-v1-c0", aliceRoles)).isEmpty();
        assertThat(sources.findAuthorized("doc-engineering-v1-c0", aliceRoles)).isPresent();

        assertThat(retrieval.vector(queryVector, Set.of("HR", "PUBLIC"), 20))
                .extracting(RetrievalCandidate::documentId).contains("doc-hr").doesNotContain("doc-engineering");
        assertThat(retrieval.keyword("HR-LEAVE-204", Set.of("HR", "PUBLIC"), 20))
                .extracting(RetrievalCandidate::documentId).contains("doc-hr");
        assertThat(retrieval.exact("HR-LEAVE-204", Set.of("HR", "PUBLIC"), 20))
                .extracting(RetrievalCandidate::documentId).contains("doc-hr");
        assertThat(sources.findAuthorized("doc-hr-v1-c0", Set.of("HR", "PUBLIC"))).isPresent();

        assertThat(retrieval.keyword("HR-LEAVE-204", Set.of("ENGINEERING", "PUBLIC"), 20))
                .extracting(RetrievalCandidate::documentId).doesNotContain("doc-hr");
        assertThat(retrieval.keyword("IR-204", Set.of("SECURITY_ADMIN", "PUBLIC"), 20))
                .extracting(RetrievalCandidate::documentId).contains("doc-security");
        assertThat(retrieval.keyword("IR-204", Set.of("ENGINEERING", "PUBLIC"), 20))
                .extracting(RetrievalCandidate::documentId).doesNotContain("doc-security");
    }

    @Test
    void disabledAndSupersededDocumentsAreExcludedFromEverySearchPath() {
        jdbc.update("UPDATE documents SET status = 'DISABLED' WHERE id = 'doc-engineering'");
        Set<String> aliceRoles = Set.of("PUBLIC", "ENGINEERING");

        assertThat(retrieval.keyword("GW-204 gateway", aliceRoles, 20)).isEmpty();
        assertThat(retrieval.exact("GW-204", aliceRoles, 20)).isEmpty();
        assertThat(sources.findAuthorized("doc-engineering-v1-c0", aliceRoles)).isEmpty();
    }

    @Test
    void staleVersionIsExcludedEvenWhenItsAclStillAllowsTheUser() {
        UUID currentVersion = UUID.randomUUID();
        jdbc.update("INSERT INTO document_versions (id, document_id, version_number, content_hash, source_filename, effective_at, index_status, embedding_model_id, embedding_dimensions) VALUES (?, 'doc-engineering', 2, ?, 'fixture.md', now(), 'ACTIVE', 'test-model', 768)",
                currentVersion, hash("doc-engineering-v2"));
        jdbc.update("UPDATE document_versions SET index_status = 'SUPERSEDED' WHERE document_id = 'doc-engineering' AND version_number = 1");
        jdbc.update("UPDATE documents SET current_version_id = ? WHERE id = 'doc-engineering'", currentVersion);
        jdbc.update("INSERT INTO document_chunks (id, document_version_id, chunk_index, chunk_text, heading_path, embedding) VALUES ('doc-engineering-v2-c0', ?, 0, 'Current gateway manual', 'Current', ?::vector)",
                currentVersion, vectorLiteral());

        assertThat(retrieval.keyword("GW-204", Set.of("ENGINEERING"), 20))
                .extracting(RetrievalCandidate::chunkId).doesNotContain("doc-engineering-v1-c0");
        assertThat(sources.findAuthorized("doc-engineering-v1-c0", Set.of("ENGINEERING"))).isEmpty();
    }

    @Test
    void permissionRevocationAffectsFollowingRetrievalAndSourceRequests() {
        jdbc.update("DELETE FROM document_permissions WHERE document_id = 'doc-engineering' AND allowed_role = 'ENGINEERING'");

        assertThat(retrieval.keyword("GW-204", Set.of("ENGINEERING"), 20)).isEmpty();
        assertThat(sources.findAuthorized("doc-engineering-v1-c0", Set.of("ENGINEERING"))).isEmpty();
    }

    @Test
    void prefixShadowEmbedsAndRetrievesOnlySqlAuthorizedChunks() {
        EvaluationScenario scenario = new EvaluationScenario("prefix-acl", EvaluationCategory.PERMISSION_ISOLATION,
                "alice", "What is the confidential leave policy?", List.of("doc-hr"), List.of("doc-hr"),
                null, false, true, null);

        PrefixShadowEvaluator.PrefixShadowResult result = prefixShadow.evaluate(List.of(scenario));

        assertThat(result.scenarios().get("prefix-acl").retrievedDocumentIds())
                .doesNotContain("doc-hr").contains("doc-public", "doc-engineering");
    }

    @Test
    void sameContentReindexRevokesAclImmediatelyWithoutAddingVersion() {
        String id = "doc-engineering";
        byte[] identicalContent = "GW-204 gateway troubleshooting.".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ingestion.ingest(new IngestionRequest(id, "Gateway error guide", "ENGINEERING", "INTERNAL",
                Set.of("ENGINEERING"), "guide.txt", identicalContent, null));
        int versionCount = jdbc.queryForObject("SELECT count(*) FROM document_versions WHERE document_id = ?", Integer.class, id);

        IngestionResult update = ingestion.ingest(new IngestionRequest(id, "Gateway guide for HR", "HR", "CONFIDENTIAL",
                Set.of("HR"), "guide.txt", identicalContent, null));

        assertThat(update.duplicate()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_versions WHERE document_id = ?", Integer.class, id))
                .isEqualTo(versionCount);
        assertThat(jdbc.queryForList("SELECT allowed_role FROM document_permissions WHERE document_id = ?", String.class, id))
                .containsExactly("HR");
        assertThat(retrieval.keyword("GW-204", Set.of("ENGINEERING"), 20)).extracting(RetrievalCandidate::documentId)
                .doesNotContain(id);
        assertThat(retrieval.exact("GW-204", Set.of("ENGINEERING"), 20)).extracting(RetrievalCandidate::documentId)
                .doesNotContain(id);
        assertThat(sources.findAuthorized(id + "-v1-c0", Set.of("ENGINEERING"))).isEmpty();
    }

    @Test
    void versionedCorpusFreshnessScenariosResolveActiveVersionAfterOldFixtureSeedsFirst() {
        IngestionResult oldVersion = ingestion.ingest(new IngestionRequest("doc-engineering-rate-limit-configuration",
                "Enterprise API Policy", "ENGINEERING", "INTERNAL", Set.of("ENGINEERING"), "policy-v1.md",
                "# Historical rate limits\nThe prior limit was 600 requests per minute.".getBytes(java.nio.charset.StandardCharsets.UTF_8), null));
        IngestionResult currentVersion = ingestion.ingest(new IngestionRequest("doc-engineering-rate-limit-configuration",
                "Enterprise API Policy", "ENGINEERING", "INTERNAL", Set.of("ENGINEERING"), "policy.md",
                "# Rate Limits\nThe current Enterprise limit is 1000 requests per minute.".getBytes(java.nio.charset.StandardCharsets.UTF_8), null));

        assertThat(oldVersion.version()).isEqualTo(1);
        assertThat(currentVersion.version()).isEqualTo(2);
        EvaluationDataset dataset = datasetLoader.load();
        EvaluationScenario fresh = new EvaluationScenario("fresh-active-version", EvaluationCategory.DOCUMENT_FRESHNESS,
                "alice", "1000", List.of("doc-engineering-rate-limit-configuration"), List.of(), 2, true, false, null);
        EvaluationScenarioResult result = scenarioEvaluator.evaluate(fresh);
        assertThat(result.expectedVersion()).isEqualTo(2);
        assertThat(result.retrievedVersions()).containsKey("doc-engineering-rate-limit-configuration");
        assertThat(result.freshnessCorrect()).isTrue();
    }

    @Test
    void syntheticSeederSkipsOldPolicyWhenCurrentVersionIsAlreadyActive() throws java.io.IOException {
        ingestion.ingest(new IngestionRequest("doc-engineering-rate-limit-configuration", "Enterprise API Policy",
                "ENGINEERING", "INTERNAL", Set.of("ENGINEERING"), "policy-v1.md",
                "# Historical rate limits\nThe prior limit was 600 requests per minute.".getBytes(java.nio.charset.StandardCharsets.UTF_8), null));
        ingestion.ingest(new IngestionRequest("doc-engineering-rate-limit-configuration", "Enterprise API Policy",
                "ENGINEERING", "INTERNAL", Set.of("ENGINEERING"), "policy.md",
                Files.readAllBytes(Path.of("../synthetic-docs/engineering/rate-limit-configuration.md")), null));
        var seeder = new SyntheticSeedRunner(ingestion, true, Path.of("../synthetic-docs"));

        seeder.seedNow();

        assertThat(jdbc.queryForObject("SELECT max(version_number) FROM document_versions WHERE document_id = ?",
                Integer.class, "doc-engineering-rate-limit-configuration")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT v.version_number FROM documents d JOIN document_versions v ON v.id = d.current_version_id WHERE d.id = ?",
                Integer.class, "doc-engineering-rate-limit-configuration")).isEqualTo(2);
    }

    @Test
    void newLogicalDocumentSeedCanRaceSafelyWithConcurrentCreation() throws Exception {
        String documentId = "doc-first-create-race";
        org.springframework.jdbc.core.JdbcTemplate contender = new JdbcTemplate(
                new org.springframework.jdbc.datasource.DriverManagerDataSource(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        ingestion.ingest(new IngestionRequest(documentId, "Seeded", "PUBLIC", "PUBLIC", Set.of("PUBLIC"),
                "seed.txt", "seed content".getBytes(java.nio.charset.StandardCharsets.UTF_8), null));

        IngestionResult raced = ingestion.ingest(new IngestionRequest(documentId, "Admin metadata", "HR", "CONFIDENTIAL",
                Set.of("HR"), "seed.txt", "seed content".getBytes(java.nio.charset.StandardCharsets.UTF_8), null));

        assertThat(raced.duplicate()).isTrue();
        assertThat(contender.queryForObject("SELECT count(*) FROM documents WHERE id = ?", Integer.class, documentId)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT allowed_role FROM document_permissions WHERE document_id = ?", String.class, documentId))
                .containsExactly("HR");
    }

    private void createDocument(String documentId, String title, String classification, String role, String text) {
        UUID versionId = UUID.randomUUID();
        jdbc.update("INSERT INTO documents (id, title, department, classification) VALUES (?, ?, ?, ?)",
                documentId, title, role, classification);
        jdbc.update("INSERT INTO document_permissions (document_id, allowed_role) VALUES (?, ?)", documentId, role);
        jdbc.update("INSERT INTO document_versions (id, document_id, version_number, content_hash, source_filename, effective_at, index_status, embedding_model_id, embedding_dimensions) VALUES (?, ?, 1, ?, 'fixture.md', ?, 'ACTIVE', 'test-model', 768)",
                versionId, documentId, hash(documentId), Timestamp.from(java.time.Instant.now()));
        jdbc.update("UPDATE documents SET current_version_id = ? WHERE id = ?", versionId, documentId);
        jdbc.update("INSERT INTO document_chunks (id, document_version_id, chunk_index, chunk_text, heading_path, embedding) VALUES (?, ?, 0, ?, 'Policy', ?::vector)",
                documentId + "-v1-c0", versionId, text, vectorLiteral());
    }

    private static String hash(String value) {
        return String.format("%064x", value.hashCode() & 0xffffffffL);
    }

    private static String vectorLiteral() {
        return "[1," + "0,".repeat(766) + "0]";
    }
}
