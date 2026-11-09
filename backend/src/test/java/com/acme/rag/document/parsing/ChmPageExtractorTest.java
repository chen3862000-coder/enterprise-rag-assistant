package com.acme.rag.document.parsing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.rag.document.ingestion.IngestionException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChmPageExtractorTest {
    @TempDir
    Path root;

    @Test
    void extractsOnlyListedHtmlWithinAggregateExpandedLimit() throws Exception {
        String pageText = "<p>First line</p><p>Second line</p>";
        byte[] page = pageText.getBytes(StandardCharsets.UTF_8);
        Files.write(root.resolve("one.html"), page);
        Files.write(root.resolve("two.html"), page);
        ChmPageExtractor extractor = new ChmPageExtractor(page.length, page.length * 2L);

        ParsedDocument parsed = extractor.read(root, List.of(
                new ChmMember("one.html", page.length), new ChmMember("two.html", page.length)));

        assertThat(parsed.sections()).hasSize(2);
        assertThat(parsed.sections()).allSatisfy(section -> assertThat(section.text()).contains("First line", "Second line"));
    }

    @Test
    void rejectsAggregateExpandedBytesBeyondConfiguredLimit() throws Exception {
        byte[] page = "<p>text</p>".getBytes(StandardCharsets.UTF_8);
        Files.write(root.resolve("one.html"), page);
        Files.write(root.resolve("two.html"), page);
        ChmPageExtractor extractor = new ChmPageExtractor(page.length, page.length);

        assertThatThrownBy(() -> extractor.read(root, List.of(
                new ChmMember("one.html", page.length), new ChmMember("two.html", page.length))))
                .isInstanceOf(IngestionException.class).hasMessageContaining("configured limit");
    }
}
