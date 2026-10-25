package com.acme.rag.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

import com.acme.rag.citation.AssembledContext;
import com.acme.rag.citation.CitationEvidence;
import com.acme.rag.retrieval.RankedChunk;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.ObjectProvider;

class AnswerGeneratorTest {
    @Test
    void returnsDeterministicAbstentionWithoutCallingProviderWhenNoEvidence() {
        ObjectProvider<ChatModel> models = mock(ObjectProvider.class);
        String answer = new AnswerGenerator(models).generate("Question?", new AssembledContext("", List.of()));

        assertThat(answer).contains("sufficient authorized evidence");
        org.mockito.Mockito.verify(models, never()).getIfAvailable();
    }

    @Test
    void usesEvidenceAndReturnsProviderAnswer() {
        ChatModel model = mock(ChatModel.class);
        ObjectProvider<ChatModel> models = mock(ObjectProvider.class);
        when(models.getIfAvailable()).thenReturn(model);
        when(model.call(org.mockito.ArgumentMatchers.any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("The limit is 1000 [S1].")))));
        CitationEvidence evidence = new CitationEvidence("S1",
                new RankedChunk("chunk", UUID.randomUUID(), "doc", "Policy", 2, "Limits", "1000 per minute", 1.0,
                        0.9, 0.5, false));

        String answer = new AnswerGenerator(models).generate("What is the limit?", new AssembledContext("[S1] Policy", List.of(evidence)));

        assertThat(answer).isEqualTo("The limit is 1000 [S1].");
    }

    @Test
    void reportsProviderDisabledWithoutClaimingEvidenceWasFactChecked() {
        ObjectProvider<ChatModel> models = mock(ObjectProvider.class);
        when(models.getIfAvailable()).thenReturn(null);
        CitationEvidence evidence = new CitationEvidence("S1",
                new RankedChunk("chunk", UUID.randomUUID(), "doc", "Policy", 1, "Limits", "1000 per minute", 1.0,
                        0.9, 0.5, false));

        String answer = new AnswerGenerator(models).generate("Question", new AssembledContext("[S1] Policy", List.of(evidence)));

        assertThat(answer).contains("generation is disabled", "[S1]");
        assertThat(answer).doesNotContain("SUPPORTED", "fact-checked", "verified answer");
    }
}
