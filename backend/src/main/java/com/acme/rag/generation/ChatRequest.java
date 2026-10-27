package com.acme.rag.generation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Chat input. The message must contain non-whitespace text and be at most 2,000 characters. */
public record ChatRequest(@NotBlank @Size(max = 2000) String message) {}
