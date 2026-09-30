package com.zzx.docrag;

import com.zzx.docrag.config.LlmProperties;
import com.zzx.docrag.config.RagProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the Spring context wires up and that configuration binds.
 *
 * <p>Redis auto-configuration is excluded and the Redis template is replaced by a mock, so
 * this test passes on a laptop with no Docker running. A failure here therefore always means
 * a wiring or configuration bug, never "I forgot to start the infrastructure" — which is
 * exactly what makes it worth running in CI.
 *
 * <p>The Elasticsearch client, the chunk gateway, the ingestion pipeline and every controller
 * are still created for real: those are the collaborators whose wiring we actually want proven.
 */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
})
class DocRagApplicationTests {

    @Autowired
    private ApplicationContext context;

    @MockitoBean
    private StringRedisTemplate stringRedisTemplate;

    @Test
    @DisplayName("the application context loads with every bean wired")
    void contextLoads() {
        assertThat(context).isNotNull();
        assertThat(context.getBeanDefinitionCount()).isGreaterThan(0);
    }

    @Test
    @DisplayName("the retrieval, answer and ingestion collaborators are all wired")
    void collaboratorsAreWired() {
        assertThat(context.containsBean("retrievalService")).isTrue();
        assertThat(context.containsBean("ragService")).isTrue();
        assertThat(context.containsBean("evalService")).isTrue();
        assertThat(context.containsBean("ingestionService")).isTrue();
        assertThat(context.containsBean("openAiCompatibleClient")).isTrue();
        assertThat(context.containsBean("elasticsearchChunkGateway")).isTrue();
        assertThat(context.containsBean("textChunker")).isTrue();
        assertThat(context.containsBean("promptBuilder")).isTrue();
    }

    @Test
    @DisplayName("every document parser is registered")
    void parsersAreRegistered() {
        assertThat(context.containsBean("pdfDocumentParser")).isTrue();
        assertThat(context.containsBean("docxDocumentParser")).isTrue();
        assertThat(context.containsBean("textDocumentParser")).isTrue();
    }

    @Test
    @DisplayName("all four REST controllers are exposed")
    void controllersAreRegistered() {
        assertThat(context.containsBean("documentController")).isTrue();
        assertThat(context.containsBean("ragController")).isTrue();
        assertThat(context.containsBean("evalController")).isTrue();
        assertThat(context.containsBean("adminController")).isTrue();
    }

    @Test
    @DisplayName("reranking stays absent until rag.enable-rerank is turned on")
    void rerankerIsOffByDefault() {
        assertThat(context.containsBean("httpReranker")).isFalse();
    }

    @Test
    @DisplayName("configuration properties bind with their documented defaults")
    void propertiesBind() {
        RagProperties rag = context.getBean(RagProperties.class);
        assertThat(rag.chunkSize()).isEqualTo(500);
        assertThat(rag.chunkOverlap()).isEqualTo(80);
        assertThat(rag.defaultTopK()).isEqualTo(8);
        assertThat(rag.candidateK()).isEqualTo(30);
        assertThat(rag.rrfK()).isEqualTo(60);
        assertThat(rag.enableLexical()).isTrue();
        assertThat(rag.enableVector()).isTrue();
        assertThat(rag.enableRerank()).isFalse();
        assertThat(rag.enableRewrite()).isFalse();

        LlmProperties llm = context.getBean(LlmProperties.class);
        // Aliyun Bailian text-embedding-v4 default dimension; keep in sync with application.yml
        assertThat(llm.embeddingDimension()).isEqualTo(1024);
        assertThat(llm.maxContextChars()).isEqualTo(6000);
        assertThat(llm.baseUrl()).isNotBlank();
        assertThat(llm.rewriteModel()).isEqualTo("qwen-turbo");
    }
}
