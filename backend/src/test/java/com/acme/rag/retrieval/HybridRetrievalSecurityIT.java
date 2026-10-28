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
    @Autowired HybridRetrievalService retrievalService;
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
    void exactIdentifierSearchRequiresWholeIdentifierToken() {
        jdbc.update("UPDATE document_chunks SET chunk_text = 'Restricted incident IR-204.' "
                + "WHERE id = 'doc-security-v1-c0'");
        String identifier = new ExactIdentifierExtractor().extract("What happened in IR-204.").getFirst();

        assertThat(retrieval.exact(new ExactIdentifierExtractor().extract("What happened in IR-20?").getFirst(),
                Set.of("SECURITY_ADMIN", "PUBLIC"), 20))
                .isEmpty();
        assertThat(retrieval.exact(identifier, Set.of("SECURITY_ADMIN", "PUBLIC"), 20))
                .extracting(RetrievalCandidate::documentId).containsExactly("doc-security");
    }

    @Test
    void exactIdentifierSearchKeepsInternalDotsSignificant() {
        jdbc.update("UPDATE document_chunks SET chunk_text = 'The API.1 endpoint is available.' "
                + "WHERE id = 'doc-public-v1-c0'");

        ExactIdentifierExtractor extractor = new ExactIdentifierExtractor();
        assertThat(retrieval.exact(extractor.extract("Call API.1.").getFirst(), Set.of("PUBLIC"), 20))
                .extracting(RetrievalCandidate::documentId).containsExactly("doc-public");
        assertThat(retrieval.exact(extractor.extract("Call API1.").getFirst(), Set.of("PUBLIC"), 20)).isEmpty();
    }

    @Test
    void rolledBackQueryMatchesRollbackSourceAndPassesEvidenceGate() {
        jdbc.update("UPDATE document_chunks SET chunk_text = 'For the current gateway deployment, restore the most recent healthy artifact during rollback.' "
                + "WHERE id = 'doc-engineering-v1-c0'");

        var result = retrievalService.retrieveForEvaluation(
                "How should the current gateway deployment be rolled back?", Set.of("ENGINEERING"),
                Set.of("doc-engineering"));

        assertThat(result.evidenceSufficient()).isTrue();
        assertThat(result.gateReason()).isEqualTo("LEXICAL_AND_RELEVANCE_MATCH");
        assertThat(result.chunks()).extracting(RankedChunk::documentId).contains("doc-engineering");
    }

    @Test
    void rollbackPhraseNormalizationDoesNotMatchUnrelatedTerms() {
        jdbc.update("UPDATE document_chunks SET chunk_text = 'The procedure is rollback.' "
                + "WHERE id = 'doc-engineering-v1-c0'");

        assertThat(retrieval.keyword("rolled forward", Set.of("ENGINEERING"), 20)).isEmpty();
        assertThat(retrieval.keyword("roll back", Set.of("ENGINEERING"), 20)).extracting(RetrievalCandidate::documentId)
                .containsExactly("doc-engineering");
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
    void sameContentReindexReappliesAclAfterConcurrentFirstCreateUpdatesMetadata() {
        String id = "doc-first-create-acl";
        byte[] content = "initial content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ingestion.ingest(new IngestionRequest(id, "Initial", "PUBLIC", "PUBLIC", Set.of("PUBLIC"),
                "initial.txt", content, null));

        ingestion.ingest(new IngestionRequest(id, "Latest", "HR", "CONFIDENTIAL", Set.of("HR"),
                "initial.txt", content, null));

        assertThat(jdbc.queryForList("SELECT allowed_role FROM document_permissions WHERE document_id = ?",
                String.class, id)).containsExactly("HR");
        assertThat(documentsVersionCount(id)).isEqualTo(1);
    }

    private int documentsVersionCount(String documentId) {
        return jdbc.queryForObject("SELECT count(*) FROM document_versions WHERE document_id = ?",
                Integer.class, documentId);
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
    void syntheticSeederSkipsInactiveFixturesWithoutAbortingStartup() throws java.io.IOException {
        String documentId = "doc-public-product-overview";
        ingestion.ingest(new IngestionRequest(documentId, "Product overview", "PUBLIC", "PUBLIC",
                Set.of("PUBLIC"), "product-overview.md", "Initial seeded content.".getBytes(
                java.nio.charset.StandardCharsets.UTF_8), null));
        jdbc.update("UPDATE documents SET status = 'DISABLED' WHERE id = ?", documentId);
        var seeder = new SyntheticSeedRunner(ingestion, true, Path.of("../synthetic-docs"));

        seeder.seedNow();

        assertThat(jdbc.queryForObject("SELECT status FROM documents WHERE id = ?", String.class, documentId))
                .isEqualTo("DISABLED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_versions WHERE document_id = ?",
                Integer.class, documentId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM documents WHERE id = 'doc-public-support-faq'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void syntheticSeederRepairsOnlyTransientFirstEmbeddingFailure() throws java.io.IOException {
        String documentId = "doc-public-support-faq";
        var fixture = Path.of("../synthetic-docs/public/support-faq.md");
        byte[] fixtureBytes = Files.readAllBytes(fixture);
        UUID failedVersion = UUID.randomUUID();
        String hash;
        try {
            hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(fixtureBytes));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
        jdbc.update("INSERT INTO documents (id,title,department,classification,status,seed_managed,seed_repairable) "
                + "VALUES (?, 'Support FAQ', 'PUBLIC', 'PUBLIC', 'DISABLED', true, true)", documentId);
        jdbc.update("INSERT INTO document_permissions (document_id,allowed_role) VALUES (?, 'PUBLIC')", documentId);
        jdbc.update("INSERT INTO document_versions (id,document_id,version_number,content_hash,source_filename,effective_at,"
                + "index_status,embedding_model_id,embedding_dimensions) VALUES (?, ?, 1, ?, 'support-faq.md', now(), "
                + "'FAILED', 'nomic-embed-text', 768)", failedVersion, documentId, hash);
        jdbc.update("INSERT INTO ingestion_jobs (id,document_id,document_version_id,status,error_message,completed_at) "
                + "VALUES (?, ?, ?, 'FAILED', 'synthetic transient outage', now())", UUID.randomUUID(), documentId, failedVersion);

        assertThat(jdbc.queryForObject("SELECT seed_repairable FROM documents WHERE id = ?", Boolean.class,
                documentId)).isTrue();
        var repair = new SyntheticSeedRunner(ingestion, true, Path.of("../synthetic-docs"));
        repair.seedNow();

        assertThat(jdbc.queryForObject("SELECT status FROM documents WHERE id = ?", String.class, documentId))
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT seed_repairable FROM documents WHERE id = ?", Boolean.class, documentId))
                .isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_chunks c JOIN document_versions v "
                + "ON v.id = c.document_version_id WHERE v.document_id = ? AND v.index_status = 'ACTIVE'",
                Integer.class, documentId)).isGreaterThan(0);
    }

    @Test
    void concurrentFirstCreatesForSameLogicalDocumentSerializeAndAllocateVersions() throws Exception {
        String documentId = "doc-first-create-race";
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> concurrentIngest(documentId,
                    "Seeded", "seed.txt", "seed content", ready, start));
            var second = executor.submit(() -> concurrentIngest(documentId,
                    "Seeded v2", "seed-v2.txt", "second content", ready, start));
            assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            IngestionResult firstResult = first.get(10, java.util.concurrent.TimeUnit.SECONDS);
            IngestionResult secondResult = second.get(10, java.util.concurrent.TimeUnit.SECONDS);

            assertThat(firstResult.version()).isNotEqualTo(secondResult.version());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM documents WHERE id = ?", Integer.class, documentId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM document_versions WHERE document_id = ?",
                    Integer.class, documentId)).isEqualTo(2);
            assertThat(jdbc.queryForList("SELECT allowed_role FROM document_permissions WHERE document_id = ?", String.class, documentId))
                    .containsExactly("PUBLIC");
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private IngestionResult concurrentIngest(String documentId, String title, String filename, String content,
                                               java.util.concurrent.CountDownLatch ready,
                                               java.util.concurrent.CountDownLatch start) {
        ready.countDown();
        try {
            start.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent ingestion test interrupted", exception);
        }
        return ingestion.ingest(new IngestionRequest(documentId, title, "PUBLIC", "PUBLIC", Set.of("PUBLIC"),
                filename, content.getBytes(java.nio.charset.StandardCharsets.UTF_8), null));
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
