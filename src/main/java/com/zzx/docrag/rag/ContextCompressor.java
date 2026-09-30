package com.zzx.docrag.rag;

import com.zzx.docrag.config.RagProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Sentence-level context compression: keeps only the sentences of a retrieved chunk that
 * carry retrieval signal for the question, cutting prompt cost without (measurably) cutting
 * answer quality.
 *
 * <p>Scoring is deliberately cheap and deterministic - CJK bigram + latin word overlap
 * between sentence and query, no extra model calls. A cross-encoder sentence filter would
 * score better on hard cases but would cost more than the tokens it saves; the whole point
 * of compression is cost, so the compressor itself must be nearly free.
 *
 * <p>Behaviour contract:
 * <ul>
 *   <li>disabled or content within budget -&gt; returned unchanged (no surprises)</li>
 *   <li>only sentences with a non-zero overlap score are kept; the rest is dropped</li>
 *   <li>kept sentences are emitted in ORIGINAL document order - reordering would break
 *       the narrative flow the generator relies on</li>
 *   <li>no sentence scores at all -&gt; fall back to the content prefix (first definitions
 *       usually live at the front of a chunk)</li>
 * </ul>
 */
@Component
public class ContextCompressor {

    private final RagProperties ragProperties;

    public ContextCompressor(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    public String compress(String query, String content) {
        if (content == null) {
            return "";
        }
        int budget = ragProperties.compressionChunkChars();
        if (!ragProperties.enableCompression() || budget <= 0 || content.length() <= budget) {
            return content;
        }

        String[] sentences = content.split("(?<=[.!?。！？；;])\\s*|\\n+");
        Set<String> queryTokens = tokens(query);
        List<Scored> scored = new ArrayList<>(sentences.length);
        for (int i = 0; i < sentences.length; i++) {
            String sentence = sentences[i].strip();
            if (sentence.isEmpty()) {
                continue;
            }
            scored.add(new Scored(i, sentence, overlap(tokens(sentence), queryTokens), sentence.length()));
        }
        if (scored.isEmpty()) {
            return prefix(content, budget);
        }

        // Best signal first; ties keep the earlier sentence. Skipping an over-budget sentence
        // (continue, not break) lets a shorter but still relevant one fit.
        scored.sort(Comparator.comparingInt(Scored::score).reversed().thenComparingInt(Scored::index));
        List<Scored> kept = new ArrayList<>();
        int used = 0;
        for (Scored candidate : scored) {
            if (candidate.score() == 0) {
                break;
            }
            if (used + candidate.length() + 1 > budget) {
                continue;
            }
            kept.add(candidate);
            used += candidate.length() + 1;
        }
        if (kept.isEmpty()) {
            return prefix(content, budget);
        }
        kept.sort(Comparator.comparingInt(Scored::index));
        StringBuilder out = new StringBuilder();
        for (Scored s : kept) {
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(s.text());
        }
        return out.toString();
    }

    private static String prefix(String content, int budget) {
        return content.length() <= budget ? content : content.substring(0, budget);
    }

    private static int overlap(Set<String> sentence, Set<String> query) {
        int hits = 0;
        for (String token : sentence) {
            if (query.contains(token)) {
                hits++;
            }
        }
        return hits;
    }

    /**
     * CJK bigrams + latin/digit words (length >= 2). Bigrams are the standard cheap CJK
     * tokenization: single characters are too noisy ("的"/"是" match everything), full
     * dictionary segmentation is overkill for ranking sentences.
     */
    static Set<String> tokens(String text) {
        Set<String> out = new HashSet<>();
        if (text == null) {
            return out;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        StringBuilder word = new StringBuilder();
        char prevCjk = 0;
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            boolean isCjk = c >= '\u4e00' && c <= '\u9fff';
            boolean isLatin = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
            if (isLatin) {
                word.append(c);
            } else {
                if (word.length() >= 2) {
                    out.add(word.toString());
                }
                word.setLength(0);
            }
            if (isCjk) {
                if (prevCjk != 0) {
                    out.add(new String(new char[]{prevCjk, c}));
                }
                prevCjk = c;
            } else {
                prevCjk = 0;
            }
        }
        if (word.length() >= 2) {
            out.add(word.toString());
        }
        return out;
    }

    /** A sentence with its query-overlap score and original position. */
    private record Scored(int index, String text, int score, int length) {
    }
}
