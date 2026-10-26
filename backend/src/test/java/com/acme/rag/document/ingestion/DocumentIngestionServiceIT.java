package com.acme.rag.document.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.acme.rag.document.chunking.DocumentChunker;
import com.acme.rag.document.parsing.DocumentParser;
import com.acme.rag.document.parsing.PdfDocumentParser;
import com.acme.rag.document.parsing.TextDocumentParser;
import com.acme.rag.observability.RagMetrics;
import com.acme.rag.M2TestConfiguration;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
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
class DocumentIngestionServiceIT {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:pg17")
            .withDatabaseName("rag_test")
            .withUsername("rag")
            .withPassword("rag");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired DocumentIngestionService ingestionService;
    @Autowired DocumentManagementRepository documentManagement;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM ingestion_jobs");
        jdbc.update("DELETE FROM document_chunks");
        jdbc.update("DELETE FROM document_permissions");
        jdbc.update("UPDATE documents SET current_version_id = NULL");
        jdbc.update("DELETE FROM document_versions");
        jdbc.update("DELETE FROM documents");
    }

    @Test
    void activatesNewVersionOnlyAfterChunksArePersisted() {
        IngestionResult first = ingestionService.ingest(request("doc-version-test", "# Policy\nOld rule"));
        IngestionResult second = ingestionService.ingest(request("doc-version-test", "# Policy\nNew rule"));

        assertThat(first.version()).isEqualTo(1);
        assertThat(second.version()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT current_version_id FROM documents WHERE id = ?", UUID.class, "doc-version-test"))
                .isEqualTo(second.versionId());
        assertThat(jdbc.queryForObject("SELECT index_status FROM document_versions WHERE id = ?", String.class, first.versionId()))
                .isEqualTo("SUPERSEDED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_chunks WHERE document_version_id = ?", Integer.class, second.versionId()))
                .isEqualTo(second.chunkCount());
    }

    @Test
    void duplicateActiveContentAvoidsNewEmbeddingAndVersion() {
        IngestionResult first = ingestionService.ingest(request("doc-duplicate-test", "Same content"));
        IngestionResult duplicate = ingestionService.ingest(request("doc-duplicate-test", "Same content"));

        assertThat(duplicate.duplicate()).isTrue();
        assertThat(duplicate.versionId()).isEqualTo(first.versionId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ingestion_jobs WHERE document_id = ?", Integer.class,
                "doc-duplicate-test")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_versions WHERE document_id = ?", Integer.class, "doc-duplicate-test"))
                .isEqualTo(1);
    }

    @Test
    void sameContentAclAndMetadataUpdatesAreAppliedWithoutCreatingVersion() {
        byte[] bytes = "# Leave\nSynthetic policy".getBytes(StandardCharsets.UTF_8);
        IngestionRequest original = new IngestionRequest("doc-acl-update", "Policy", "ENGINEERING", "INTERNAL",
                Set.of("ENGINEERING"), "policy.md", bytes, null);
        ingestionService.ingest(original);
        IngestionResult updated = ingestionService.ingest(new IngestionRequest("doc-acl-update", "Updated title", "HR",
                "CONFIDENTIAL", Set.of("HR"), "policy.md", bytes, null));

        assertThat(updated.duplicate()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_versions WHERE document_id = ?", Integer.class,
                "doc-acl-update")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT title FROM documents WHERE id = ?", String.class, "doc-acl-update"))
                .isEqualTo("Updated title");
        assertThat(jdbc.queryForList("SELECT allowed_role FROM document_permissions WHERE document_id = ?", String.class,
                "doc-acl-update")).containsExactly("HR");
    }

    @Test
    void identicalNoOpUploadDoesNotCreateAnotherIngestionJob() {
        IngestionRequest request = request("doc-noop-dedup", "Unchanged current content");
        ingestionService.ingest(request);
        int jobsBefore = jdbc.queryForObject("SELECT count(*) FROM ingestion_jobs WHERE document_id = ?", Integer.class,
                request.documentId());

        IngestionResult duplicate = ingestionService.ingest(request);

        assertThat(duplicate.duplicate()).isTrue();
        assertThat(duplicate.metadataUpdated()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ingestion_jobs WHERE document_id = ?", Integer.class,
                request.documentId())).isEqualTo(jobsBefore);
    }

    @Test
    void inactiveDocumentsRequireExplicitRestoreBeforeDuplicateUpload() {
        IngestionRequest disabledRequest = request("doc-disabled-restore", "Disabled policy content");
        ingestionService.ingest(disabledRequest);
        assertThat(documentManagement.disable(disabledRequest.documentId())).isTrue();

        assertThatThrownBy(() -> ingestionService.ingest(disabledRequest))
                .isInstanceOf(IngestionException.class)
                .hasMessageContaining("Restore the inactive document");
        assertThat(jdbc.queryForObject("SELECT status FROM documents WHERE id = ?", String.class,
                disabledRequest.documentId())).isEqualTo("DISABLED");

        assertThat(documentManagement.restore(disabledRequest.documentId())).isTrue();
        assertThat(ingestionService.ingest(disabledRequest).duplicate()).isTrue();

        IngestionRequest deletedRequest = request("doc-deleted-restore", "Deleted policy content");
        ingestionService.ingest(deletedRequest);
        assertThat(documentManagement.delete(deletedRequest.documentId())).isTrue();
        assertThatThrownBy(() -> ingestionService.ingest(deletedRequest))
                .isInstanceOf(IngestionException.class)
                .hasMessageContaining("Restore the inactive document");
        assertThat(jdbc.queryForObject("SELECT status FROM documents WHERE id = ?", String.class,
                deletedRequest.documentId())).isEqualTo("DELETED");
        assertThat(documentManagement.restore(deletedRequest.documentId())).isTrue();
        assertThat(ingestionService.ingest(deletedRequest).duplicate()).isTrue();
    }

    @Test
    void activeContentLookupIsDeterministicForSeedRestartCheck() {
        IngestionRequest request = request("doc-seed-restart", "Stable current policy");
        ingestionService.ingest(request);

        assertThat(ingestionService.isActiveContent(request.documentId(), request.content())).isTrue();
        assertThat(ingestionService.isActiveContent(request.documentId(), "Older policy".getBytes(StandardCharsets.UTF_8))).isFalse();
    }

    @Test
    void invalidEmbeddingDimensionDoesNotReplaceActiveVersion() {
        ingestionService.ingest(request("doc-rollback-test", "First version"));
        UUID activeBefore = jdbc.queryForObject("SELECT current_version_id FROM documents WHERE id = ?", UUID.class, "doc-rollback-test");
        List<DocumentParser> parsers = List.of(new TextDocumentParser(), new PdfDocumentParser());
        TransactionTemplate transactionTemplate = new TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        EmbeddingModel wrongDimensionModel = new EmbeddingModel() {
            @Override public float[] embed(org.springframework.ai.document.Document document) { return new float[2]; }
            @Override public EmbeddingResponse call(org.springframework.ai.embedding.EmbeddingRequest request) {
                return new EmbeddingResponse(request.getInstructions().stream()
                        .map(text -> new org.springframework.ai.embedding.Embedding(new float[2], 0)).toList());
            }
            @Override public int dimensions() { return 2; }
        };
        DocumentIngestionService invalidEmbeddingService = new DocumentIngestionService(
                new UploadValidator(new UploadContentValidator(parsers, 10_485_760)),
                new DocumentVersionRepository(jdbc, org.springframework.jdbc.core.simple.JdbcClient.create(jdbc), transactionTemplate,
                        "bad-model", 768),
                new EmbeddingService(wrongDimensionModel, 768),
                parsers,
                new DocumentChunker(1800, 240), new RagMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));

        assertThatThrownBy(() -> invalidEmbeddingService.ingest(request("doc-rollback-test", "Second version")))
                .isInstanceOf(IngestionException.class)
                .hasMessageContaining("dimension");
        assertThat(jdbc.queryForObject("SELECT current_version_id FROM documents WHERE id = ?", UUID.class, "doc-rollback-test"))
                .isEqualTo(activeBefore);
        assertThat(jdbc.queryForObject("SELECT index_status FROM document_versions WHERE document_id = ? ORDER BY version_number DESC LIMIT 1",
                String.class, "doc-rollback-test")).isEqualTo("FAILED");
    }

    @Test
    void replacementPermissionsActivateOnlyAlongsideSuccessfulNewVersion() {
        byte[] original = "# Policy\nOriginal".getBytes(StandardCharsets.UTF_8);
        ingestionService.ingest(new IngestionRequest("doc-permission-version", "Policy", "ENGINEERING", "INTERNAL",
                Set.of("ENGINEERING"), "policy.md", original, null));

        assertThat(jdbc.queryForList("SELECT allowed_role FROM document_permissions WHERE document_id = ?", String.class,
                "doc-permission-version")).containsExactly("ENGINEERING");
        DocumentParser parser = new TextDocumentParser();
        List<DocumentParser> parsers = List.of(parser, new PdfDocumentParser());
        EmbeddingModel invalid = mock(EmbeddingModel.class);
        when(invalid.embedForResponse(org.mockito.ArgumentMatchers.<String>anyList()))
                .thenReturn(new EmbeddingResponse(List.of(new org.springframework.ai.embedding.Embedding(new float[2], 0))));
        DocumentIngestionService service = new DocumentIngestionService(
                new UploadValidator(new UploadContentValidator(parsers, 10_485_760)),
                new DocumentVersionRepository(jdbc, org.springframework.jdbc.core.simple.JdbcClient.create(jdbc),
                        new TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource())),
                        "bad-model", 768), new EmbeddingService(invalid, 768), parsers, new DocumentChunker(1800, 240),
                new RagMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));

        assertThatThrownBy(() -> service.ingest(new IngestionRequest("doc-permission-version", "Policy", "HR", "CONFIDENTIAL",
                Set.of("HR"), "policy.md", "# Policy\nChanged content that forces embedding".getBytes(StandardCharsets.UTF_8), null)))
                .isInstanceOf(IngestionException.class);

        assertThat(jdbc.queryForList("SELECT allowed_role FROM document_permissions WHERE document_id = ?", String.class,
                "doc-permission-version")).containsExactly("ENGINEERING");
    }

    private static IngestionRequest request(String id, String text) {
        return new IngestionRequest(id, "Synthetic policy", "ENGINEERING", "INTERNAL", Set.of("ENGINEERING"),
                "policy.md", text.getBytes(StandardCharsets.UTF_8), null);
    }
}
