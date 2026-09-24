package com.zzx.docrag.retrieve;

import com.zzx.docrag.config.RagProperties;
import com.zzx.docrag.es.ChunkGateway;
import com.zzx.docrag.es.Retrieved;
import com.zzx.docrag.llm.EmbeddingClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Hybrid retrieval: BM25 and dense retrieval run in parallel, then Reciprocal Rank Fusion
 * merges them, then an optional cross-encoder reranks the fused candidates.
 *
 * <p>Three deliberate choices:
 * <ul>
 *   <li><b>Parallel retrievers.</b> The two calls are independent, so running them
 *       concurrently makes retrieval latency {@code max(a, b)} instead of {@code a + b}.
 *       Virtual threads keep this readable with no pool sizing to get wrong.</li>
 *   <li><b>Retriever isolation.</b> If the embedding provider is down, the lexical retriever
 *       still answers. Degrading one retriever beats failing the query.</li>
 *   <li><b>Fusion operates on ranks,</b> so no score normalization is needed when the corpus
 *       or the embedding model changes.</li>
 * </ul>
 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    private final ChunkGateway chunkGateway;
    private final EmbeddingClient embeddingClient;
    private final RagProperties ragProperties;
    private final ObjectProvider<Reranker> rerankerProvider;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public RetrievalService(ChunkGateway chunkGateway,
                            EmbeddingClient embeddingClient,
                            RagProperties ragProperties,
                            ObjectProvider<Reranker> rerankerProvider) {
        this.chunkGateway = chunkGateway;
        this.embeddingClient = embeddingClient;
        this.ragProperties = ragProperties;
        this.rerankerProvider = rerankerProvider;
    }

    /**
     * @param query           question, already rewritten if rewriting is enabled
     * @param topK            number of chunks to return
     * @return ranked chunks plus per-stage evidence
     */
    public RetrievalResult retrieve(String query, int topK) {
        int candidateK = Math.max(ragProperties.candidateK(), topK);
        long started = System.currentTimeMillis();

        CompletableFuture<Timed<List<Retrieved>>> lexicalFuture =
                ragProperties.enableLexical() ? submitLexical(query, candidateK) : completed(Timed.<Retrieved>empty());
        CompletableFuture<Timed<List<Retrieved>>> vectorFuture =
                ragProperties.enableVector() ? submitVector(query, candidateK) : completed(Timed.<Retrieved>empty());

        CompletableFuture.allOf(lexicalFuture, vectorFuture).join();
        Timed<List<Retrieved>> lexical = lexicalFuture.join();
        Timed<List<Retrieved>> vector = vectorFuture.join();

        long fusionStarted = System.currentTimeMillis();
        List<Merged> fused = ReciprocalRankFusion.fuse(
                lexical.value(), vector.value(), ragProperties.rrfK(),
                ragProperties.lexWeight(), ragProperties.vecWeight());
        long fusionMillis = System.currentTimeMillis() - fusionStarted;

        long rerankMillis = 0L;
        String rerankSkipped = "";
        List<Merged> selected;

        Reranker reranker = rerankerProvider.getIfAvailable();
        if (!ragProperties.enableRerank()) {
            rerankSkipped = "disabled by config";
            selected = take(fused, topK);
        } else if (reranker == null) {
            rerankSkipped = "no Reranker bean available";
            selected = take(fused, topK);
        } else if (fused.size() <= ragProperties.rerankMinCandidates()) {
            // Adaptive skip: with few candidates the fused order is already nearly all there
            // is to show; paying the rerank latency tax buys (almost) nothing. Threshold is
            // a config knob so the precision/latency trade stays measurable.
            rerankSkipped = "fused=" + fused.size() + " <= rerank-min-candidates="
                    + ragProperties.rerankMinCandidates();
            selected = take(fused, topK);
        } else {
            long rerankStarted = System.currentTimeMillis();
            selected = reranker.rerank(query, fused, topK);
            rerankMillis = System.currentTimeMillis() - rerankStarted;
        }

        RetrievalResult result = new RetrievalResult(
                selected,
                lexical.value().size(),
                vector.value().size(),
                fused.size(),
                lexical.millis(),
                vector.millis(),
                fusionMillis,
                rerankMillis,
                rerankSkipped);

        log.debug("Retrieval query='{}' lexicalHits={} vectorHits={} fused={} kept={} total={}ms",
                query, result.lexicalHits(), result.vectorHits(), result.fusedCount(),
                selected.size(), System.currentTimeMillis() - started);
        return result;
    }

    private CompletableFuture<Timed<List<Retrieved>>> submitLexical(String query, int candidateK) {
        return CompletableFuture.supplyAsync(() -> {
            long started = System.currentTimeMillis();
            try {
                return new Timed<>(chunkGateway.lexicalSearch(query, candidateK), System.currentTimeMillis() - started);
            } catch (RuntimeException e) {
                log.warn("Lexical retrieval failed, continuing with vector results only: {}", e.toString());
                return Timed.empty();
            }
        }, executor);
    }

    private CompletableFuture<Timed<List<Retrieved>>> submitVector(String query, int candidateK) {
        return CompletableFuture.supplyAsync(() -> {
            long started = System.currentTimeMillis();
            try {
                float[] queryVector = embeddingClient.embed(query);
                List<Retrieved> hits = chunkGateway.vectorSearch(queryVector, candidateK, ragProperties.minVectorScore());
                return new Timed<>(hits, System.currentTimeMillis() - started);
            } catch (RuntimeException e) {
                log.warn("Vector retrieval failed, continuing with lexical results only: {}", e.toString());
                return Timed.empty();
            }
        }, executor);
    }

    private static <T> CompletableFuture<Timed<T>> completed(Timed<T> value) {
        return CompletableFuture.completedFuture(value);
    }

    private static List<Merged> take(List<Merged> merged, int topK) {
        return merged.size() > topK ? merged.subList(0, topK) : merged;
    }

    /** A value paired with how long it took to produce. Never carries a null value. */
    private record Timed<T>(T value, long millis) {
        private static <T> Timed<List<T>> empty() {
            return new Timed<>(List.of(), 0L);
        }
    }
}
