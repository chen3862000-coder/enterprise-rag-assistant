package com.acme.rag;

import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import static org.mockito.Mockito.mock;

@TestConfiguration(proxyBeanMethods = false)
public class M2TestConfiguration {
    @Bean
    @Primary
    EmbeddingModel deterministicEmbeddingModel() {
        return new EmbeddingModel() {
            @Override
            public float[] embed(Document document) {
                return vector();
            }

            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                List<Embedding> results = new ArrayList<>();
                for (int index = 0; index < request.getInstructions().size(); index++) {
                    results.add(new Embedding(vector(), index));
                }
                return new EmbeddingResponse(results);
            }

            @Override
            public int dimensions() {
                return 768;
            }

            private float[] vector() {
                float[] vector = new float[768];
                vector[0] = 1.0f;
                return vector;
            }
        };
    }

    @Bean
    @Primary
    ChatModel deterministicChatModel() {
        return mock(ChatModel.class);
    }
}
