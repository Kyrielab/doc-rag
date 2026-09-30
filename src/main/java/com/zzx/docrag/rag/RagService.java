package com.zzx.docrag.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzx.docrag.config.LlmProperties;
import com.zzx.docrag.config.RagProperties;
import com.zzx.docrag.es.Chunk;
import com.zzx.docrag.es.ChunkGateway;
import com.zzx.docrag.llm.LlmClient;
import com.zzx.docrag.retrieve.Merged;
import com.zzx.docrag.retrieve.RetrievalResult;
import com.zzx.docrag.retrieve.RetrievalService;
import com.zzx.docrag.retrieve.RetrievalTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Orchestrates the answer path: cache -> retrieve -> fuse -> rerank -> prompt -> generate -> cite.
 *
 * <p>The cache is deliberate about what it stores. It keeps the answer text plus the chunk ids
 * and their rerank scores, then refetches chunk content on a hit. Caching the whole response
 * object would freeze document text in Redis and serve stale excerpts after a document is
 * re-ingested; storing ids keeps the cache small and lets citations follow the live corpus.
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private final RetrievalService retrievalService;
    private final PromptBuilder promptBuilder;
    private final LlmClient llmClient;
    private final ChunkGateway chunkGateway;
    private final RagProperties ragProperties;
    private final LlmProperties llmProperties;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public RagService(RetrievalService retrievalService,
                      PromptBuilder promptBuilder,
                      LlmClient llmClient,
                      ChunkGateway chunkGateway,
                      RagProperties ragProperties,
                      LlmProperties llmProperties,
                      StringRedisTemplate redis,
                      ObjectMapper mapper) {
        this.retrievalService = retrievalService;
        this.promptBuilder = promptBuilder;
        this.llmClient = llmClient;
        this.chunkGateway = chunkGateway;
        this.ragProperties = ragProperties;
        this.llmProperties = llmProperties;
        this.redis = redis;
        this.mapper = mapper;
    }

    /**
     * @param question user question
     * @param topK     chunks to ground on; falls back to {@code rag.default-top-k}
     * @return answer with citations and a full retrieval trace, possibly served from cache
     */
    public QaAnswer answer(String question, Integer topK) {
        return answer(question, topK, true);
    }

    /**
     * @param question   user question
     * @param topK       chunks to ground on; falls back to {@code rag.default-top-k}
     * @param allowCache false bypasses the answer cache in both directions. Evaluation runs
     *                   must measure the live pipeline: a cached answer would hide both the
     *                   real latency and any behaviour regression since the answer was stored.
     * @return answer with citations and a full retrieval trace
     */
    public QaAnswer answer(String question, Integer topK, boolean allowCache) {
        long started = System.currentTimeMillis();
        int effectiveTopK = (topK == null || topK <= 0) ? ragProperties.defaultTopK() : topK;
        String cacheKey = allowCache ? cacheKey(question, effectiveTopK) : null;

        if (allowCache) {
            Optional<CachedAnswer> cached = readCache(cacheKey);
            if (cached.isPresent()) {
                QaAnswer fromCache = rebuildFromCache(cached.get(), question, started);
                log.debug("Cache hit for question='{}'", question);
                return fromCache;
            }
        }

        RetrievalResult retrieval = retrievalService.retrieve(question, effectiveTopK);
        List<Merged> chunks = retrieval.chunks();

        long generateStarted = System.currentTimeMillis();
        String answerText;
        int promptContextChars = 0;
        if (chunks.isEmpty()) {
            // No grounding material at all: refuse without paying for an LLM call.
            answerText = "INSUFFICIENT_CONTEXT";
        } else {
            PromptBuilder.UserPrompt prompt = promptBuilder.userPrompt(question, chunks);
            promptContextChars = prompt.contextChars();
            answerText = llmClient.complete(promptBuilder.systemPrompt(), prompt.text());
        }
        long generateMillis = System.currentTimeMillis() - generateStarted;

        boolean refused = promptBuilder.isRefusal(answerText);
        List<CitationRef> refs = promptBuilder.parseCitations(answerText, chunks);
        List<Citation> citations = promptBuilder.distinctCitations(refs, chunks);

        RetrievalTrace trace = new RetrievalTrace(
                question,
                retrieval.rewrittenQuery(),
                retrieval.rewriteMillis(),
                retrieval.lexicalHits(),
                retrieval.vectorHits(),
                retrieval.fusedCount(),
                retrieval.lexicalMillis(),
                retrieval.vectorMillis(),
                retrieval.fusionMillis(),
                retrieval.rerankMillis(),
                generateMillis,
                promptContextChars,
                System.currentTimeMillis() - started,
                chunks,
                Merged.idsOf(chunks),
                false,
                retrieval.rerankSkipped());

        QaAnswer answer = QaAnswer.of(answerText, citations, refs, refused, trace);
        if (allowCache) {
            writeCache(cacheKey, answer);
        }
        return answer;
    }

    /**
     * Retrieval without generation - the free path used by the evaluation harness when
     * {@code skipGeneration=true}. Before this method existed, "retrieval-only" evaluation
     * still paid for a full chat call per case; the docs claimed otherwise. Deliberately
     * cache-free: evaluation must measure the live retrieval path every run.
     */
    public RetrievalResult retrieveOnly(String question, Integer topK) {
        int effectiveTopK = (topK == null || topK <= 0) ? ragProperties.defaultTopK() : topK;
        return retrievalService.retrieve(question, effectiveTopK);
    }

    // ------------------------------------------------------------------ caching

    private String cacheKey(String question, int topK) {
        // The key includes every input that changes the answer, so two configurations never
        // poison each other's cache entries. The prompt fingerprint and chat model matter:
        // editing the system prompt or swapping the model must invalidate stale answers
        // automatically instead of silently serving the old behaviour.
        String material = question.trim().toLowerCase()
                + "|topK=" + topK
                + "|lex=" + ragProperties.enableLexical()
                + "|vec=" + ragProperties.enableVector()
                + "|rerank=" + ragProperties.enableRerank()
                + "|rewrite=" + ragProperties.enableRewrite()
                + "|compress=" + ragProperties.enableCompression() + ":" + ragProperties.compressionChunkChars()
                + "|chunk=" + ragProperties.chunkSize()
                + "|model=" + llmProperties.chatModel()
                + "|prompt=" + sha256(promptBuilder.systemPrompt() + "|" + PromptBuilder.TEMPLATE_VERSION).substring(0, 16);
        return "rag:answer:" + sha256(material);
    }

    private Optional<CachedAnswer> readCache(String key) {
        try {
            String raw = redis.opsForValue().get(key);
            if (raw == null) {
                return Optional.empty();
            }
            return Optional.of(mapper.readValue(raw, CachedAnswer.class));
        } catch (Exception e) {
            // A cache failure must never break the request path.
            log.warn("Cache read failed, falling through to a live answer: {}", e.toString());
            return Optional.empty();
        }
    }

    private void writeCache(String key, QaAnswer answer) {
        try {
            CachedAnswer payload = CachedAnswer.from(answer);
            redis.opsForValue().set(key, mapper.writeValueAsString(payload),
                    Duration.ofSeconds(ragProperties.cacheTtlSeconds()));
        } catch (Exception e) {
            log.warn("Cache write failed: {}", e.toString());
        }
    }

    private QaAnswer rebuildFromCache(CachedAnswer cached, String question, long started) {
        List<Chunk> stored = chunkGateway.findByIds(cached.chunkIds());
        List<Merged> chunks = new ArrayList<>(stored.size());
        for (int i = 0; i < stored.size(); i++) {
            Chunk chunk = stored.get(i);
            Double rerankScore = i < cached.rerankScores().size() ? cached.rerankScores().get(i) : null;
            chunks.add(new Merged(
                    chunk.chunkId(),
                    chunk.docId(),
                    chunk.title(),
                    chunk.source(),
                    chunk.content(),
                    0.0,
                    rerankScore,
                    Merged.MISS,
                    Merged.MISS,
                    Merged.previewOf(chunk.content())));
        }
        List<CitationRef> refs = promptBuilder.parseCitations(cached.answer(), chunks);
        List<Citation> citations = promptBuilder.distinctCitations(refs, chunks);
        RetrievalTrace trace = new RetrievalTrace(
                question, question, 0, 0, 0, chunks.size(),
                0, 0, 0, 0, 0, 0,
                System.currentTimeMillis() - started,
                chunks, cached.chunkIds(), true, cached.rerankSkipped());
        return QaAnswer.of(cached.answer(), citations, refs, cached.refused(), trace);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Cache payload. Flat and Jackson-friendly on purpose: no records with optional
     * components, nothing that breaks when a domain class is refactored.
     *
     * @param answer       generated answer text
     * @param chunkIds     cited-order chunk ids that were in the prompt
     * @param rerankScores rerank score per chunk, aligned with {@code chunkIds}
     * @param refused      whether the model refused
     * @param rerankSkipped why reranking did not run
     */
    public record CachedAnswer(
            String answer,
            List<String> chunkIds,
            List<Double> rerankScores,
            boolean refused,
            String rerankSkipped
    ) {
        public static CachedAnswer from(QaAnswer answer) {
            List<Merged> chunks = answer.trace().topChunks();
            List<String> ids = new ArrayList<>(chunks.size());
            List<Double> rerankScores = new ArrayList<>(chunks.size());
            for (Merged chunk : chunks) {
                ids.add(chunk.chunkId());
                rerankScores.add(chunk.rerankScore());
            }
            return new CachedAnswer(answer.answer(), ids, rerankScores, answer.refused(), answer.trace().rerankSkipped());
        }
    }
}
