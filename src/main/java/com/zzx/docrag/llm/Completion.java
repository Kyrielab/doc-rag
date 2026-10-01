package com.zzx.docrag.llm;

/**
 * Result of a non-streaming completion: the text plus the provider-reported token usage.
 * Usage travels with the content so cost accounting cannot drift from the call that caused it.
 *
 * @param content          generated text
 * @param promptTokens     provider-reported prompt tokens (0 when the provider omits usage)
 * @param completionTokens provider-reported completion tokens
 */
public record Completion(String content, int promptTokens, int completionTokens) {

    public int totalTokens() {
        return promptTokens + completionTokens;
    }

    public static Completion of(String content) {
        return new Completion(content, 0, 0);
    }
}
