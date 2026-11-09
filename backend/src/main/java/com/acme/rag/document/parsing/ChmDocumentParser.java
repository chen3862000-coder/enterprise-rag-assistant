package com.acme.rag.document.parsing;

import com.acme.rag.document.ingestion.IngestionException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Extracts bounded visible HTML pages from CHM files and rejects unsafe archive members. */
@Component
public final class ChmDocumentParser implements DocumentParser {
    private static final long DEFAULT_LISTING_TIMEOUT_SECONDS = 60;
    private final String extractor;
    private final int maximumEntries;
    private final long maximumExpandedBytes;
    private final long maximumPageBytes;
    private final long maximumListingBytes;

    public ChmDocumentParser(@Value("${app.ingestion.chm-extractor:7z}") String extractor,
                             @Value("${app.ingestion.chm-max-entries:20000}") int maximumEntries,
                             @Value("${app.ingestion.chm-max-expanded-bytes:67108864}") long maximumExpandedBytes,
                             @Value("${app.ingestion.chm-max-page-bytes:2097152}") long maximumPageBytes,
                             @Value("${app.ingestion.chm-max-listing-bytes:16777216}") long maximumListingBytes) {
        this.extractor = extractor;
        this.maximumEntries = maximumEntries;
        this.maximumExpandedBytes = maximumExpandedBytes;
        this.maximumPageBytes = maximumPageBytes;
        this.maximumListingBytes = maximumListingBytes;
    }

    @Override
    public boolean supports(String filename) {
        return filename.toLowerCase(Locale.ROOT).endsWith(".chm");
    }

    @Override
    public ParsedDocument parse(byte[] content) {
        try (PathScope scope = new PathScope()) {
            Path archive = scope.root().resolve("source.chm");
            Files.write(archive, content);
            List<ChmMember> members = inspect(archive);
            validateArchiveBounds(members);
            List<ChmMember> pages = htmlPages(members);
            ChmProcessRunner.extract(extractor, archive, scope.root(), pages);
            return new ChmPageExtractor(maximumPageBytes, maximumExpandedBytes).read(scope.root(), pages);
        } catch (IngestionException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw extractionFailure(exception);
        } catch (IOException exception) {
            throw extractionFailure(exception);
        }
    }

    private List<ChmMember> inspect(Path archive) throws IOException, InterruptedException {
        return ChmProcessRunner.inspect(extractor, archive, maximumListingBytes,
                DEFAULT_LISTING_TIMEOUT_SECONDS, maximumEntries);
    }

    private void validateArchiveBounds(List<ChmMember> members) {
        long expanded = 0;
        for (ChmMember member : members) {
            expanded = Math.addExact(expanded, member.size());
        }
        if (members.size() > maximumEntries || expanded > maximumExpandedBytes) {
            throw invalid("CHM archive exceeds configured extraction limits");
        }
    }

    private List<ChmMember> htmlPages(List<ChmMember> members) {
        List<ChmMember> pages = members.stream().filter(member -> ChmMember.isHtml(member.path())).toList();
        if (pages.isEmpty()) {
            throw new IngestionException(IngestionException.Kind.UNPROCESSABLE, "CHM archive contains no HTML pages");
        }
        if (pages.stream().anyMatch(page -> page.size() > maximumPageBytes)) {
            throw invalid("CHM page exceeds the configured size limit");
        }
        return pages;
    }

    private static IngestionException extractionFailure(Exception cause) {
        return new IngestionException(IngestionException.Kind.UNPROCESSABLE,
                "Unable to safely extract CHM document", cause);
    }

    static IngestionException invalid(String message) {
        return new IngestionException(IngestionException.Kind.INVALID_INPUT, message);
    }

    private static final class PathScope implements AutoCloseable {
        private final Path root;

        private PathScope() throws IOException {
            root = Files.createTempDirectory("rag-chm-");
        }

        private Path root() {
            return root;
        }

        @Override
        public void close() throws IOException {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }
}
