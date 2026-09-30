package com.zzx.docrag.retrieve;

import com.zzx.docrag.config.LlmProperties;
import com.zzx.docrag.config.RagProperties;
import com.zzx.docrag.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * LLM-based query rewriting: turns the user's colloquial question into a search-friendly
 * query before retrieval.
 *
 * <p>Why this exists - the measured failure it targets (eval case para-03): the question
 * "程序突然断电了，内存里还没写到磁盘的数据怎么办" retrieves Redis persistence chunks
 * (full of 断电/丢数据 wording) while the MySQL redo-log chunk - which phrases the same
 * concept as 崩溃恢复 - never enters any candidate list. All three retrieval configs and
 * even the cross-encoder reranker missed it, because rerank can only reorder what was
 * retrieved. Rewriting bridges the vocabulary gap BEFORE retrieval, which is the only
 * stage that can still fix a recall miss.
 *
 * <p>Cost and safety:
 * <ul>
 *   <li>One auxiliary LLM call per query, on a cheaper model ({@code llm.rewrite-model},
 *       default qwen-turbo) - the latency tax is real and the eval harness measures it.</li>
 *   <li>Fail-open on any error: the original query proceeds. A rewrite outage degrades
 *       retrieval quality for colloquial queries, never availability.</li>
 *   <li>Output is sanitized hard (first line, quotes stripped, length-capped): a model
 *       that "helpfully" answers or explains must not poison the retrieval query.</li>
 * </ul>
 */
@Component
public class QueryRewriter {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriter.class);

    /** Hard cap on rewritten length; anything longer is an explanation, not a query. */
    private static final int MAX_REWRITTEN_CHARS = 200;

    static final String SYSTEM_PROMPT = """
            You are a search query rewriter for a Chinese technical documentation QA system.
            Rewrite the user's question into exactly ONE search-optimized question.

            Rules:
            1. Resolve pronouns and ellipsis into concrete terms.
            2. Replace colloquial phrasing with the standard technical terms a technical
               document would use. Examples:
               - 突然断电后数据怎么办 -> 崩溃恢复如何保证数据不丢失（redo log 持久化）
               - 线程自己存一份变量副本 -> ThreadLocal 内存泄漏
            3. Keep every entity, number and constraint from the original question.
            4. Do NOT answer the question. Do NOT explain.
            Output only the rewritten question, on a single line, without quotes.
            """;

    private final LlmClient llmClient;
    private final LlmProperties llmProperties;
    private final RagProperties ragProperties;

    public QueryRewriter(LlmClient llmClient, LlmProperties llmProperties, RagProperties ragProperties) {
        this.llmClient = llmClient;
        this.llmProperties = llmProperties;
        this.ragProperties = ragProperties;
    }

    /**
     * @param query the user's original question
     * @return the rewritten query, or the original one when rewriting is disabled, the model
     *         misbehaves, or the call fails - callers never have to handle a failure here
     */
    public String rewrite(String query) {
        if (!ragProperties.enableRewrite() || query == null || query.isBlank()) {
            return query;
        }
        try {
            String model = llmProperties.rewriteModel().isBlank()
                    ? llmProperties.chatModel()
                    : llmProperties.rewriteModel();
            String raw = llmClient.complete(model, SYSTEM_PROMPT, query);
            String cleaned = sanitize(raw);
            if (cleaned.isBlank() || cleaned.length() > MAX_REWRITTEN_CHARS) {
                log.warn("Query rewrite produced unusable output ({} chars), keeping original query",
                        cleaned.length());
                return query;
            }
            return cleaned;
        } catch (RuntimeException e) {
            // Fail-open: rewriting is an optimization, never a hard dependency.
            log.warn("Query rewrite failed, keeping original query: {}", e.toString());
            return query;
        }
    }

    /**
     * Keeps only the first line and strips wrapping quotes. Models told to output "one line,
     * no quotes" still occasionally do neither; the retrieval query must stay clean regardless.
     */
    static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        String firstLine = raw.strip().split("\\R", 2)[0].strip();
        while (firstLine.length() >= 2 && isQuotePair(firstLine.charAt(0), firstLine.charAt(firstLine.length() - 1))) {
            firstLine = firstLine.substring(1, firstLine.length() - 1).strip();
        }
        return firstLine;
    }

    private static boolean isQuotePair(char first, char last) {
        return (first == '"' && last == '"')
                || (first == '\'' && last == '\'')
                || (first == '“' && last == '”')
                || (first == '「' && last == '」');
    }
}
