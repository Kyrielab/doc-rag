package com.zzx.docrag.retrieve;

import com.zzx.docrag.config.LlmProperties;
import com.zzx.docrag.config.RagProperties;
import com.zzx.docrag.llm.Completion;
import com.zzx.docrag.llm.LlmClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Query rewriting sits on the request path of every retrieval, so its failure behaviour
 * matters more than its happy path: it must never break a query and must never leak model
 * chattiness into the retrieval query.
 */
class QueryRewriterTest {

    private static RagProperties props(boolean enableRewrite) {
        return new RagProperties(500, 80, 8, 30, 0.35, 60, true, true, false, 600,
                1.0, 1.0, 0, enableRewrite, false, 250, 0);
    }

    private static LlmProperties llm(String rewriteModel) {
        return new LlmProperties("http://localhost:11434/v1", "", "bge-m3", 1024,
                "qwen-plus", 0.1, 6000, rewriteModel);
    }

    @Test
    @DisplayName("disabled rewriting returns the original query without calling the model")
    void disabledIsPassThrough() {
        LlmClient client = mock(LlmClient.class);
        QueryRewriter rewriter = new QueryRewriter(client, llm("qwen-turbo"), props(false));

        assertThat(rewriter.rewrite("断电了数据怎么办")).isEqualTo("断电了数据怎么办");
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("provider failure falls back to the original query (fail-open)")
    void failsOpenOnProviderError() {
        LlmClient client = mock(LlmClient.class);
        when(client.complete(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("provider down"));
        QueryRewriter rewriter = new QueryRewriter(client, llm("qwen-turbo"), props(true));

        assertThat(rewriter.rewrite("断电了数据怎么办")).isEqualTo("断电了数据怎么办");
    }

    @Test
    @DisplayName("the configured rewrite model is used, not the chat model")
    void usesRewriteModel() {
        LlmClient client = mock(LlmClient.class);
        when(client.complete(anyString(), anyString(), anyString())).thenReturn(Completion.of("崩溃恢复如何保证数据不丢失"));
        QueryRewriter rewriter = new QueryRewriter(client, llm("qwen-turbo"), props(true));

        rewriter.rewrite("断电了数据怎么办");

        org.mockito.Mockito.verify(client)
                .complete(org.mockito.ArgumentMatchers.eq("qwen-turbo"), anyString(), anyString());
    }

    @Test
    @DisplayName("blank rewrite-model falls back to the chat model")
    void blankRewriteModelFallsBackToChatModel() {
        LlmClient client = mock(LlmClient.class);
        when(client.complete(anyString(), anyString(), anyString())).thenReturn(Completion.of("改写后的问题"));
        QueryRewriter rewriter = new QueryRewriter(client, llm(""), props(true));

        rewriter.rewrite("原问题");

        org.mockito.Mockito.verify(client)
                .complete(org.mockito.ArgumentMatchers.eq("qwen-plus"), anyString(), anyString());
    }

    @Test
    @DisplayName("an overlong model output is rejected instead of used as a query")
    void rejectsOverlongOutput() {
        LlmClient client = mock(LlmClient.class);
        when(client.complete(anyString(), anyString(), anyString())).thenReturn(Completion.of("长".repeat(201)));
        QueryRewriter rewriter = new QueryRewriter(client, llm("qwen-turbo"), props(true));

        assertThat(rewriter.rewrite("原问题")).isEqualTo("原问题");
    }

    @Test
    @DisplayName("sanitize keeps the first line and strips wrapping quotes")
    void sanitizeCleansModelChattiness() {
        assertThat(QueryRewriter.sanitize("\"崩溃恢复怎么做\"\n解释：因为...")).isEqualTo("崩溃恢复怎么做");
        assertThat(QueryRewriter.sanitize("“崩溃恢复怎么做”")).isEqualTo("崩溃恢复怎么做");
        assertThat(QueryRewriter.sanitize("  崩溃恢复怎么做  ")).isEqualTo("崩溃恢复怎么做");
        assertThat(QueryRewriter.sanitize(null)).isEmpty();
        assertThat(QueryRewriter.sanitize("")).isEmpty();
    }
}
