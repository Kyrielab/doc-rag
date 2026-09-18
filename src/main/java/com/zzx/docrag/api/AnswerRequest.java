package com.zzx.docrag.api;

import jakarta.validation.constraints.NotBlank;

/**
 * Question answering request.
 *
 * @param question the user question
 * @param topK     optional retrieval depth; null falls back to {@code rag.default-top-k}
 */
public record AnswerRequest(
        @NotBlank(message = "question must not be blank") String question,
        Integer topK
) {
}
