package com.acme.rag.document.ingestion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Predicate;

/** Scans only direct source-file children and delegates path validation before deletion. */
final class DurableSourceCleanup {
    private final DurableSourceFiles files;

    DurableSourceCleanup(DurableSourceFiles files) {
        this.files = files;
    }

    void discard(Predicate<Path> referenced, Duration retention) {
        Path root = files.root();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var entries = Files.list(root)) {
            entries.filter(DurableSourceCleanup::isSourceFile)
                    .filter(files::isStoredSource)
                    .filter(path -> !referenced.test(path))
                    .filter(path -> retention == null || modifiedBefore(path, retention))
                    .forEach(files::delete);
        } catch (IOException exception) {
            throw new IngestionException(IngestionException.Kind.UNPROCESSABLE,
                    "Unable to clean ingestion sources", exception);
        }
    }

    private static boolean isSourceFile(Path path) {
        Path filename = path.getFileName();
        return filename != null && filename.toString().endsWith(".source");
    }

    private static boolean modifiedBefore(Path source, Duration retention) {
        try {
            return Files.getLastModifiedTime(source, LinkOption.NOFOLLOW_LINKS).toInstant()
                    .isBefore(Instant.now().minus(retention));
        } catch (IOException exception) {
            return false;
        }
    }
}
