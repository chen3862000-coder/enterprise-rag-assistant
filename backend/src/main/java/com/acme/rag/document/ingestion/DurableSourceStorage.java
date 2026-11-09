package com.acme.rag.document.ingestion;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Predicate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/** Coordinates private durable source files, their metadata, and safe cleanup. */
@Component
public final class DurableSourceStorage {
    private final DurableSourceFiles files;
    private final DurableSourceMetadata metadata;
    private final DurableSourceCleanup cleanup;

    public DurableSourceStorage(
            @Value("${app.ingestion.source-dir:${java.io.tmpdir}/enterprise-rag-ingestion}") String root,
            @Value("${app.ingestion.max-file-bytes:10485760}") long maximumBytes) {
        files = new DurableSourceFiles(Path.of(root).toAbsolutePath().normalize(), maximumBytes);
        metadata = new DurableSourceMetadata(files);
        cleanup = new DurableSourceCleanup(files);
    }

    /** Stores a non-empty upload within the configured limit and returns its size and SHA-256. */
    public StoredSource store(InputStream input) {
        return files.store(input);
    }

    /** Deletes only a regular source file under the configured root and its safe metadata sibling. */
    public void delete(Path source) {
        files.delete(source);
    }

    /** Removes unreferenced files older than the retention period. */
    public void discardStaleSources(Predicate<Path> referenced, Duration retention) {
        cleanup.discard(referenced, retention);
    }

    /** Removes source files that have no durable job reference. */
    public void discardOrphanSources(Predicate<Path> referenced) {
        cleanup.discard(referenced, null);
    }

    /** Deletes a failed upload unless a durable job references its path. */
    public void discardUnreferenced(Path source, Predicate<Path> referenced) {
        if (!referenced.test(source)) {
            delete(source);
        }
    }

    /** Keeps a source on database failures because the reservation outcome may be unknown. */
    public void discardOnFailure(StoredSource source, RuntimeException failure, Predicate<Path> referenced) {
        if (!(failure instanceof DataAccessException)) {
            discardUnreferenced(source.path(), referenced);
        }
    }

    /** Writes content-free metadata beside the durable source. */
    public void writeMetadata(StoredSource source, ValidatedUpload upload) {
        metadata.write(source, upload);
    }

    /** Reads metadata required to resume activation after a process restart. */
    public IngestionMetadata readMetadata(Path source) {
        return metadata.read(source);
    }

    /** Checks that a path is a regular, non-symlink source file below the configured root. */
    public boolean isStoredSource(Path source) {
        return files.isStoredSource(source);
    }

    /** Immutable result of a successful durable upload. */
    public record StoredSource(Path path, long size, String sha256) {}
}
