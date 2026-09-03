package com.inkforge.retrieval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hybrid retrieval (P3-D, single-query version):
 *
 * <pre>
 * query → BM25(top 30) + Vector(top 30) → RRF Fusion(top 30) → Reranker(top 30)
 * </pre>
 *
 * <p>STRICT degradation (retrieval is an enhancement, never a single point of failure):
 * <ol>
 *   <li>BM25 + Vector ok → RRF</li>
 *   <li>BM25 failed + Vector ok → Vector only</li>
 *   <li>BM25 ok + Vector failed → BM25 only</li>
 *   <li>both failed → empty</li>
 *   <li>RRF exception → safe fallback (merged candidates)</li>
 *   <li>Reranker failure → fusion ranking (PassThrough cap)</li>
 *   <li>empty candidates → Reranker not called</li>
 *   <li>any retrieval exception → logged, never propagated</li>
 * </ol>
 * Degradation never fabricates results.
 */
@Service
public class HybridRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(HybridRetrievalService.class);

    private final MemoryRetriever bm25Retriever;
    private final MemoryRetriever vectorRetriever;
    private final Reranker reranker;
    private final RetrievalProperties properties;

    public HybridRetrievalService(@Qualifier("bm25Retriever") MemoryRetriever bm25Retriever,
                                  @Qualifier("vectorRetriever") MemoryRetriever vectorRetriever,
                                  Reranker reranker,
                                  RetrievalProperties properties) {
        this.bm25Retriever = bm25Retriever;
        this.vectorRetriever = vectorRetriever;
        this.reranker = reranker;
        this.properties = properties;
    }

    /** Single-query hybrid retrieval — original semantics preserved (final results only). */
    public List<RetrievalResult> retrieve(String novelId, String query) {
        return retrieveTraced(novelId, query).finalResults();
    }

    /**
     * Single-query hybrid retrieval with per-stage visibility (P3-E: retrieval traces).
     * The {@code retrieve(...)} behavior is unchanged — this just also exposes the
     * bm25/vector/fusion/rerank stage results.
     */
    public HybridTrace retrieveTraced(String novelId, String query) {
        List<RetrievalResult> bm25 = safeRetrieve(bm25Retriever, novelId, query, properties.bm25TopK());
        List<RetrievalResult> vector = safeRetrieve(vectorRetriever, novelId, query, properties.vectorTopK());

        Map<String, List<RetrievalResult>> stages = new LinkedHashMap<>();
        stages.put("bm25", bm25);
        stages.put("vector", vector);

        if (bm25.isEmpty() && vector.isEmpty()) {
            return new HybridTrace(stages, List.of()); // 规则 4
        }

        List<RetrievalResult> fusion = fuse(bm25, vector); // 规则 1-3, 5
        stages.put("fusion", fusion);
        if (fusion.isEmpty()) {
            return new HybridTrace(stages, List.of());
        }

        List<RetrievalResult> reranked;
        try { // 规则 6：Reranker 失败 → 回退 fusion 排名（PassThrough 语义）
            reranked = reranker.rerank(query, fusion, properties.rerankTopK());
        } catch (RerankException e) {
            log.warn("Reranker 失败，回退 Fusion 排名: {}", e.getMessage());
            reranked = fusion.stream().limit(properties.rerankTopK()).toList();
        }
        stages.put("rerank", reranked);
        return new HybridTrace(stages, reranked);
    }

    /**
     * Multi-query hybrid retrieval (P3-E): each query runs the full pipeline; results
     * are merged by chunkId keeping the HIGHEST score, sorted descending, capped at
     * fusion-top-k. A failing/empty query never fails the others.
     */
    public List<RetrievalResult> retrieveMulti(String novelId, List<String> queries) {
        if (queries == null || queries.isEmpty()) {
            return List.of();
        }
        Map<String, RetrievalResult> best = new LinkedHashMap<>();
        for (String query : queries) {
            if (query == null || query.isBlank()) {
                continue;
            }
            try {
                for (RetrievalResult result : retrieveTraced(novelId, query).finalResults()) {
                    best.merge(result.chunkId(), result,
                            (a, b) -> a.score() >= b.score() ? a : b);
                }
            } catch (Exception e) {
                log.warn("MultiQuery 单个查询失败（继续其余查询）: {}", e.getMessage());
            }
        }
        return best.values().stream()
                .sorted(Comparator.comparingDouble(RetrievalResult::score).reversed())
                .limit(properties.fusionTopK())
                .toList();
    }

    /** One query's pipeline stages plus its final results (for RetrievalTrace). */
    public record HybridTrace(Map<String, List<RetrievalResult>> stages,
                              List<RetrievalResult> finalResults) {
    }

    private List<RetrievalResult> safeRetrieve(MemoryRetriever retriever, String novelId,
                                               String query, int topK) {
        try {
            return retriever.retrieve(novelId, query, topK);
        } catch (Exception e) {
            log.warn("检索失败（降级处理）: retriever={}, novelId={}: {}", retriever.getClass().getSimpleName(),
                    novelId, e.getMessage());
            return List.of(); // 规则 2/3/8
        }
    }

    private List<RetrievalResult> fuse(List<RetrievalResult> bm25, List<RetrievalResult> vector) {
        try {
            return RrfFusion.fuse(List.of(bm25, vector), properties.rrfK(), properties.fusionTopK());
        } catch (Exception e) {
            log.warn("RRF 融合失败，回退为未融合候选: {}", e.getMessage());
            List<RetrievalResult> merged = new ArrayList<>();
            merged.addAll(bm25);
            merged.addAll(vector);
            return merged; // 规则 5：安全返回可用候选（可能含重复，防御路径）
        }
    }
}
