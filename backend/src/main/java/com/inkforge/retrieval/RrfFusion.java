package com.inkforge.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion — pure function, no Spring/LLM/database, no side effects.
 *
 * <p>RRF(d) = Σ 1 / (k + rank(d)); rank starts at 1. The same chunkId appearing in
 * multiple rankings accumulates its RRF score; the FIRST occurrence's original
 * RetrievalResult (with its original text) is kept — fusion never fabricates data.
 */
public final class RrfFusion {

    private RrfFusion() {
    }

    /**
     * @param rankings per-retriever ranked lists (BM25, Vector, …)
     * @param k        RRF constant (standard 60)
     * @param topK     result cap
     */
    public static List<RetrievalResult> fuse(List<List<RetrievalResult>> rankings, int k, int topK) {
        if (rankings == null || rankings.isEmpty() || topK <= 0 || k <= 0) {
            return List.of();
        }
        Map<String, RetrievalResult> byChunkId = new LinkedHashMap<>();
        Map<String, Double> scores = new LinkedHashMap<>();

        for (List<RetrievalResult> ranking : rankings) {
            if (ranking == null) {
                continue;
            }
            int rank = 1;
            for (RetrievalResult result : ranking) {
                if (result == null || result.chunkId() == null) {
                    continue;
                }
                byChunkId.putIfAbsent(result.chunkId(), result);
                scores.merge(result.chunkId(), 1.0 / (k + rank), Double::sum);
                rank++;
            }
        }

        List<Map.Entry<String, Double>> sorted = new ArrayList<>(scores.entrySet());
        sorted.sort(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()));

        return sorted.stream()
                .limit(topK)
                .map(entry -> {
                    RetrievalResult original = byChunkId.get(entry.getKey());
                    // the fused result keeps the original identity/text; the score becomes
                    // the accumulated RRF score (the ranking basis)
                    return new RetrievalResult(original.chunkId(), original.novelId(),
                            original.chapterOrdinal(), original.memoryType(), original.sourceId(),
                            original.text(), entry.getValue());
                })
                .toList();
    }
}
