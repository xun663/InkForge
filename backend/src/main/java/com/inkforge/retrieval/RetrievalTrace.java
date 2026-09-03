package com.inkforge.retrieval;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Observability record of one retrieval run: what was queried and how every pipeline
 * stage ranked the candidates. Deliberately NOT part of Story Memory — it is a
 * separate observation object (own repository, own table) for debug / explainability /
 * the P3-G benchmark.
 */
public record RetrievalTrace(
        String id,
        String novelId,
        String generationId,           // nullable when built outside a generation
        List<String> queries,
        Map<String, List<RetrievalResult>> pipeline,   // bm25 / vector / fusion / rerank / final
        Instant createdAt) {

    public RetrievalTrace {
        queries = queries == null ? List.of() : List.copyOf(queries);
        pipeline = pipeline == null ? Map.of() : Map.copyOf(pipeline);
    }
}
