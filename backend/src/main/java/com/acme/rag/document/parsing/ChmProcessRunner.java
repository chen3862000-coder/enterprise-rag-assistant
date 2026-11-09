package com.acme.rag.document.parsing;

import com.acme.rag.document.ingestion.IngestionException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs bounded 7-Zip listing and extraction processes for CHM archives. */
final class ChmProcessRunner {
    private static final long EXTRACTION_TIMEOUT_SECONDS = 300;

    private ChmProcessRunner() {}

    static List<ChmMember> inspect(String extractor, Path archive, long maximumListingBytes,
                                   long timeoutSeconds, int maximumEntries) throws IOException, InterruptedException {
        Path listing = Files.createTempFile("chm-listing-", ".txt");
        try {
            Process process = listingProcess(extractor, archive, listing);
            waitForListing(process, listing, maximumListingBytes, timeoutSeconds);
            validateListingResult(process, listing, maximumListingBytes);
            return new ChmListingParser(maximumEntries).parse(Files.readString(listing, StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(listing);
        }
    }

    static void extract(String extractor, Path archive, Path root, List<ChmMember> pages)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(extractor, "x", "-y", "-sccUTF-8", "-o" + root,
                archive.toString()));
        pages.forEach(page -> command.add(page.path()));
        Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        if (!process.waitFor(EXTRACTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw ChmDocumentParser.invalid("CHM extraction exceeded its time limit");
        }
        if (process.exitValue() != 0) {
            throw new IngestionException(IngestionException.Kind.UNPROCESSABLE, "Unable to safely extract CHM document");
        }
    }

    private static Process listingProcess(String extractor, Path archive, Path listing) throws IOException {
        return new ProcessBuilder(extractor, "l", "-slt", "-sccUTF-8", archive.toString())
                .redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(listing.toFile()).start();
    }

    private static void waitForListing(Process process, Path listing, long maximumListingBytes, long timeoutSeconds)
            throws IOException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (!process.waitFor(50, TimeUnit.MILLISECONDS)) {
            if (Files.size(listing) > maximumListingBytes) {
                process.destroyForcibly();
                throw ChmDocumentParser.invalid("CHM metadata exceeds the configured limit");
            }
            if (System.nanoTime() > deadline) {
                process.destroyForcibly();
                throw ChmDocumentParser.invalid("CHM inspection exceeded its time limit");
            }
        }
    }

    private static void validateListingResult(Process process, Path listing, long maximumListingBytes) throws IOException {
        if (Files.size(listing) > maximumListingBytes) {
            throw ChmDocumentParser.invalid("CHM metadata exceeds the configured limit");
        }
        if (process.exitValue() != 0) {
            throw new IngestionException(IngestionException.Kind.UNPROCESSABLE, "Unable to inspect CHM archive");
        }
    }
}
