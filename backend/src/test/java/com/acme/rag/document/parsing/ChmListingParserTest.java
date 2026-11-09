package com.acme.rag.document.parsing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.rag.document.ingestion.IngestionException;
import org.junit.jupiter.api.Test;

class ChmListingParserTest {
    private final ChmListingParser parser = new ChmListingParser(10);

    @Test
    void parsesMembersAndTheirSizesAfterListingSeparator() {
        String listing = "archive.chm\n----------\nPath = manual/index.html\nSize = 25\nAttributes = A\n"
                + "Path = images/logo.png\nSize = 12\nAttributes = A\n";

        assertThat(parser.parse(listing)).containsExactly(
                new ChmMember("manual/index.html", 25), new ChmMember("images/logo.png", 12));
    }

    @Test
    void rejectsTraversalPathsSymbolicLinksAndInvalidSizes() {
        assertThatThrownBy(() -> parser.parse("----------\nPath = ../escape.html\nSize = 1\n"))
                .isInstanceOf(IngestionException.class).hasMessageContaining("unsafe path");
        assertThatThrownBy(() -> parser.parse("----------\nPath = page.html\nSize = 1\nAttributes = Symbolic Link\n"))
                .isInstanceOf(IngestionException.class).hasMessageContaining("unsafe link");
        assertThatThrownBy(() -> parser.parse("----------\nPath = page.html\nSize = -1\n"))
                .isInstanceOf(IngestionException.class).hasMessageContaining("invalid member metadata");
    }

    @Test
    void rejectsListingThatExceedsEntryLimit() {
        ChmListingParser limited = new ChmListingParser(1);
        String listing = "----------\nPath = one.html\nSize = 1\nPath = two.html\nSize = 1\n";

        assertThatThrownBy(() -> limited.parse(listing))
                .isInstanceOf(IngestionException.class).hasMessageContaining("too many entries");
    }
}
