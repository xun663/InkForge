package com.inkforge.retrieval;

import com.inkforge.novel.Novel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Default retrieval → trace pipeline for continuation context:
 *
 * <pre>
 * Novel → RetrievalQueryBuilder (≤3 queries)
 *      → HybridRetrievalService.retrieveTraced per query
 *      → merge finals (highest score per chunk)
 *      → RetrievalTrace saved (best-effort)
 *      → RetrievedMemory
 * </pre>
 *
 * <p>Every failure degrades: warn log → empty RetrievedMemory with null traceId.
 * Trace persistence failure never blocks the continuation (trace is observability,
 * not a requirement for generation success).
 */
@Service
public class DefaultRetrievedMemoryProvider implements RetrievedMemoryProvider {

    private static final Logger log = LoggerFactory.getLogger(DefaultRetrievedMemoryProvider.class);

    private final RetrievalQueryBuilder queryBuilder;
    private final HybridRetrievalService hybridRetrievalService;
    private final RetrievalTraceRepository traceRepository;

    public DefaultRetrievedMemoryProvider(RetrievalQueryBuilder queryBuilder,
                                          HybridRetrievalService hybridRetrievalService,
                                          RetrievalTraceRepository traceRepository) {
        this.queryBuilder = queryBuilder;
        this.hybridRetrievalService = hybridRetrievalService;
        this.traceRepository = traceRepository;
    }

    @Override
    public RetrievedMemory retrieve(Novel novel, int contextMaxTokens, String generationId) {
        try {
            List<RetrievalQuery> queries = queryBuilder.build(novel);
            if (queries.isEmpty()) {
                return RetrievedMemory.empty(); // 无有效查询 → 不执行无意义检索
            }

            Map<String, List<RetrievalResult>> pipeline = new LinkedHashMap<>();
            List<RetrievalResult> merged = new ArrayList<>();
            for (RetrievalQuery query : queries) {
                HybridRetrievalService.HybridTrace trace =
                        hybridRetrievalService.retrieveTraced(novel.id(), query.text());
                trace.stages().forEach((stage, results) ->
                        pipeline.merge(stage, results, DefaultRetrievedMemoryProvider::concatLists));
                merged.addAll(trace.finalResults());
            }

            List<RetrievalResult> finalMerged = mergeByChunkId(merged);
            if (finalMerged.isEmpty()) {
                return RetrievedMemory.empty();
            }
            pipeline.put("final", finalMerged);

            String traceId = UUID.randomUUID().toString();
            RetrievalTrace retrievalTrace = new RetrievalTrace(
                    traceId, novel.id(), generationId,
                    queries.stream().map(RetrievalQuery::text).toList(),
                    pipeline, Instant.now());
            try {
                traceRepository.save(retrievalTrace);
            } catch (Exception e) {
                log.warn("RetrievalTrace 保存失败（不影响续写）: {}", e.getMessage());
            }
            return new RetrievedMemory(finalMerged, traceId);
        } catch (Exception e) {
            log.warn("检索失败，降级为空记忆（续写继续走 P2 路径）: {}", e.getMessage());
            return RetrievedMemory.empty();
        }
    }

    private static List<RetrievalResult> mergeByChunkId(List<RetrievalResult> results) {
        Map<String, RetrievalResult> best = new LinkedHashMap<>();
        for (RetrievalResult result : results) {
            best.merge(result.chunkId(), result, (a, b) -> a.score() >= b.score() ? a : b);
        }
        return best.values().stream()
                .sorted(java.util.Comparator.comparingDouble(RetrievalResult::score).reversed())
                .toList();
    }

    private static List<RetrievalResult> concatLists(List<RetrievalResult> a, List<RetrievalResult> b) {
        List<RetrievalResult> combined = new ArrayList<>(a);
        combined.addAll(b);
        return combined;
    }
}
