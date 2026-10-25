package com.acme.rag.document.parsing;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TextDocumentParserTest {
    private final TextDocumentParser parser = new TextDocumentParser();

    @Test
    void extractsHeadingPathsAndNormalizesLineEndings() {
        ParsedDocument result = parser.parse("# Policy\r\nIntro\r\n## Limits\r\n1000 requests\r\n".getBytes(StandardCharsets.UTF_8));

        assertThat(result.sections()).containsExactly(
                new ParsedDocument.Section("Policy", "Intro", null),
                new ParsedDocument.Section("Policy > Limits", "1000 requests", null));
    }

    @Test
    void supportsOnlyMarkdownAndPlainText() {
        assertThat(parser.supports("notes.MD")).isTrue();
        assertThat(parser.supports("notes.txt")).isTrue();
        assertThat(parser.supports("notes.pdf")).isFalse();
    }
}
