package com.acme.rag.document.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ContentHasherTest {
    @Test
    void returnsStableSha256ForContent() {
        byte[] content = "synthetic policy".getBytes(StandardCharsets.UTF_8);

        assertThat(ContentHasher.sha256(content)).isEqualTo(ContentHasher.sha256(content));
        assertThat(ContentHasher.sha256(content)).hasSize(64);
        assertThat(ContentHasher.sha256(content)).isNotEqualTo(ContentHasher.sha256("different".getBytes(StandardCharsets.UTF_8)));
    }
}
