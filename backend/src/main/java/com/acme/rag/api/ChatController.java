package com.acme.rag.api;

import com.acme.rag.generation.ChatRequest;
import com.acme.rag.generation.ChatResponse;
import com.acme.rag.generation.ChatService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Session-authenticated chat transport; authorization scope comes from the server identity. */
@RestController
@RequestMapping("/api/chat")
public class ChatController {
    private final ChatService chat;

    public ChatController(ChatService chat) {
        this.chat = chat;
    }

    /**
     * Answers with currently authorized evidence, abstaining with no citations when the
     * deterministic evidence gate rejects the retrieved material.
     */
    @PostMapping
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        return chat.chat(request.message());
    }
}
