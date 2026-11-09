package com.acme.rag.document.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DurableSourceStorageTest {
    @TempDir
    Path temporaryDirectory;

    private DurableSourceStorage storage;
    private Path root;

    @BeforeEach
    void setUp() {
        root = temporaryDirectory.resolve("private-sources");
        storage = new DurableSourceStorage(root.toString(), 16);
    }

    @Test
    void storesBoundedContentPrivatelyWithSizeAndHash() throws Exception {
        byte[] content = "source".getBytes(StandardCharsets.UTF_8);

        DurableSourceStorage.StoredSource stored = storage.store(new ByteArrayInputStream(content));

        assertThat(stored.size()).isEqualTo(content.length);
        assertThat(stored.sha256()).isEqualTo(ContentHasher.sha256(content));
        assertThat(Files.readAllBytes(stored.path())).isEqualTo(content);
        assertThat(storage.isStoredSource(stored.path())).isTrue();
        assertThat(Files.getPosixFilePermissions(stored.path()))
                .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }

    @Test
    void rejectsEmptyAndOversizedUploadsWithoutLeavingPartialFiles() throws Exception {
        assertThatThrownBy(() -> storage.store(new ByteArrayInputStream(new byte[0])))
                .isInstanceOf(IngestionException.class)
                .hasMessageContaining("empty");
        assertThatThrownBy(() -> storage.store(new ByteArrayInputStream(new byte[17])))
                .isInstanceOf(IngestionException.class)
                .hasMessageContaining("size limit");

        try (var entries = Files.list(root)) {
            assertThat(entries.count()).isZero();
        }
    }

    @Test
    void persistsMetadataAndDeletesItsSibling() throws Exception {
        DurableSourceStorage.StoredSource source = store("content");
        ValidatedUpload upload = upload();

        storage.writeMetadata(source, upload);

        assertThat(storage.readMetadata(source.path()))
                .isEqualTo(new IngestionMetadata("doc-test", "Title", "ENGINEERING", "INTERNAL",
                        Set.of("ENGINEERING", "SECURITY_ADMIN")));
        Path metadata = source.path().resolveSibling(source.path().getFileName() + ".meta");
        assertThat(Files.getPosixFilePermissions(metadata))
                .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

        storage.delete(source.path());

        assertThat(Files.exists(source.path())).isFalse();
        assertThat(Files.exists(metadata)).isFalse();
    }

    @Test
    void refusesOutsideAndSymlinkSourcePaths() throws Exception {
        DurableSourceStorage.StoredSource source = store("content");
        Path outside = temporaryDirectory.resolve("outside.source");
        Files.writeString(outside, "outside");
        Path link = root.resolve("linked.source");
        Files.createSymbolicLink(link, outside);

        assertThat(storage.isStoredSource(outside)).isFalse();
        assertThat(storage.isStoredSource(link)).isFalse();
        storage.delete(outside);
        storage.delete(link);

        assertThat(Files.exists(outside)).isTrue();
        assertThat(Files.exists(link)).isTrue();
        assertThat(Files.exists(source.path())).isTrue();
    }

    @Test
    void cleanupRemovesOnlyUnreferencedEligibleSources() throws Exception {
        DurableSourceStorage.StoredSource retained = store("retained");
        DurableSourceStorage.StoredSource orphan = store("orphan");
        DurableSourceStorage.StoredSource referenced = store("referenced");
        Path nonSource = root.resolve("notes.txt");
        Files.writeString(nonSource, "keep");
        Files.setLastModifiedTime(retained.path(), java.nio.file.attribute.FileTime.from(
                java.time.Instant.now().minus(Duration.ofDays(10))));

        storage.discardOrphanSources(path -> path.equals(referenced.path()) || path.equals(retained.path()));

        assertThat(Files.exists(orphan.path())).isFalse();
        assertThat(Files.exists(referenced.path())).isTrue();
        assertThat(Files.exists(nonSource)).isTrue();

        storage.discardStaleSources(path -> path.equals(referenced.path()), Duration.ofDays(7));

        assertThat(Files.exists(retained.path())).isFalse();
        assertThat(Files.exists(referenced.path())).isTrue();
        assertThat(Files.exists(nonSource)).isTrue();
    }

    @Test
    void databaseFailureKeepsSourceButOtherFailuresDiscardUnreferencedSource() throws Exception {
        DurableSourceStorage.StoredSource databaseFailureSource = store("database");
        DurableSourceStorage.StoredSource otherFailureSource = store("other");

        storage.discardOnFailure(databaseFailureSource,
                new org.springframework.dao.DataAccessResourceFailureException("temporary"), path -> false);
        storage.discardOnFailure(otherFailureSource, new IllegalStateException("failed"), path -> false);

        assertThat(Files.exists(databaseFailureSource.path())).isTrue();
        assertThat(Files.exists(otherFailureSource.path())).isFalse();
    }

    private DurableSourceStorage.StoredSource store(String content) {
        return storage.store(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    }

    private static ValidatedUpload upload() {
        return new ValidatedUpload("doc-test", "Title", "ENGINEERING", "INTERNAL",
                Set.of("ENGINEERING", "SECURITY_ADMIN"), "source.txt", new byte[0], null, false);
    }
}
