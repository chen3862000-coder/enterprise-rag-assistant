package com.acme.rag.document.parsing;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ChmHtmlTextExtractorTest {
    @Test
    void extractsVisibleTextAndIgnoresHeadScriptAndStyle() {
        byte[] html = ("<html><head><title>hidden</title></head><body><h1>Policy</h1>"
                + "<script>secret()</script><p>Visible <b>text</b></p></body></html>")
                .getBytes(StandardCharsets.UTF_8);

        assertThat(ChmHtmlTextExtractor.extract(html)).isEqualTo("Policy\n\nVisible text");
    }

    @Test
    void honorsDeclaredLegacyCharset() {
        byte[] body = "<meta charset=GB18030><p>中文说明</p>".getBytes(Charset.forName("GB18030"));

        assertThat(ChmHtmlTextExtractor.extract(body)).isEqualTo("中文说明");
    }
}
