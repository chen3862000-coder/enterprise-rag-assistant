package com.acme.rag.document.ingestion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.Comparator;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SyntheticSeedRunner implements ApplicationRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(SyntheticSeedRunner.class);
    private final DocumentIngestionService ingestionService;
    private final boolean enabled;
    private final Path root;

    public SyntheticSeedRunner(
            DocumentIngestionService ingestionService,
            @Value("${app.seed.enabled:false}") boolean enabled,
            @Value("${app.seed.path:../synthetic-docs}") Path root) {
        this.ingestionService = ingestionService;
        this.enabled = enabled;
        this.root = root;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        if (!Files.isDirectory(root)) {
            throw new IngestionException("Synthetic corpus directory does not exist: " + root.toAbsolutePath());
        }
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(Files::isRegularFile)
                    .sorted(Comparator.comparingInt(SyntheticSeedRunner::versionOrder).thenComparing(Path::toString))
                    .forEach(this::ingestFixture);
        } catch (IOException e) {
            throw new IngestionException("Unable to load synthetic seed corpus", e);
        }
    }

    public void seedNow() {
        run(new org.springframework.boot.DefaultApplicationArguments(new String[0]));
    }

    private void ingestFixture(Path file) {
        Path relative = root.relativize(file);
        String area = relative.getName(0).toString();
        Path filename = relative.getFileName();
        if (filename == null) {
            throw new IngestionException("Synthetic fixture has no filename: " + relative);
        }
        String stem = filename.toString().replaceFirst("\\.[^.]+$", "");
        String documentId = logicalDocumentId(area, stem);
        if (ingestionService.isInactiveDocument(documentId)) {
            LOGGER.warn("Skipping inactive synthetic fixture {}; explicitly restore the document to reindex it", documentId);
            return;
        }
        if (isArchivedRatePolicyAlreadyIndexed(area, stem, file, documentId)) {
            return;
        }
        String title = stem.replace('-', ' ').replace('_', ' ');
        FixtureAccess access = accessFor(area);
        try {
            ingestionService.ingest(new IngestionRequest(documentId, title, access.department(), access.classification(), access.roles(),
                    filename.toString(), Files.readAllBytes(file), Instant.now()));
        } catch (IOException e) {
            throw new IngestionException("Unable to read synthetic fixture: " + relative, e);
        }
    }

    private boolean isArchivedRatePolicyAlreadyIndexed(String area, String stem, Path archive, String documentId) {
        if (!"engineering".equals(area) || !"rate-limit-configuration-v1".equals(stem)) {
            return false;
        }
        Path current = archive.resolveSibling("rate-limit-configuration.md");
        try {
            return Files.isRegularFile(current)
                    && ingestionService.isActiveContent(documentId, Files.readAllBytes(current));
        } catch (IOException exception) {
            throw new IngestionException("Unable to inspect current synthetic policy", exception);
        }
    }

    private static String logicalDocumentId(String area, String stem) {
        if ("engineering".equals(area) && "rate-limit-configuration-v1".equals(stem)) {
            return "doc-engineering-rate-limit-configuration";
        }
        return "doc-" + area + "-" + stem;
    }

    private static int versionOrder(Path path) {
        return java.util.Optional.ofNullable(path.getFileName())
                .map(Path::toString)
                .filter(filename -> filename.endsWith("-v1.md"))
                .map(ignored -> 0)
                .orElse(1);
    }

    private static FixtureAccess accessFor(String area) {
        return switch (area) {
            case "public" -> new FixtureAccess("PUBLIC", "PUBLIC", Set.of("PUBLIC", "ENGINEERING", "HR", "SECURITY_ADMIN"));
            case "engineering" -> new FixtureAccess("ENGINEERING", "INTERNAL", Set.of("ENGINEERING"));
            case "hr" -> new FixtureAccess("HR", "CONFIDENTIAL", Set.of("HR"));
            case "security" -> new FixtureAccess("SECURITY", "RESTRICTED", Set.of("SECURITY_ADMIN"));
            default -> throw new IngestionException("Unexpected synthetic corpus directory: " + area);
        };
    }

    private record FixtureAccess(String department, String classification, Set<String> roles) {}
}
