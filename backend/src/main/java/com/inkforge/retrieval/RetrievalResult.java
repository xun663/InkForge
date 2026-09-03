package com.inkforge.retrieval;

/**
 * One ranked retrieval hit. The stable shape for P3: VectorRetriever (P3-C) and
 * HybridRetrievalService (P3-D) will produce the same record; fusion/rerank scores
 * are added in later stages, not here.
 */
public record RetrievalResult(
        String chunkId,
        String novelId,
        int chapterOrdinal,
        MemoryChunkType memoryType,
        String sourceId,
        String text,
        double score) {
}
