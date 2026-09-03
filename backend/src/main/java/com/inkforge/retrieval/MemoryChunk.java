package com.inkforge.retrieval;

import java.time.Instant;

/**
 * A retrieval projection of P2 Story Memory — NOT a new fact source. P2 entities
 * (ChapterSummary / CharacterFact / StoryEvent) stay the Source of Truth; chunks
 * exist so BM25/vector retrieval can search them efficiently.
 *
 * <p>CURRENT CharacterFacts are deliberately NEVER projected: the current state is
 * always taken directly from StoryMemoryRepository (current-facts context section),
 * never inferred through retrieval.
 *
 * <p>Chunk ids are deterministic ({@code TYPE:sourceId}), so repeated projection of
 * the same chapter is naturally idempotent.
 */
public record MemoryChunk(
        String id,
        String novelId,
        MemoryChunkType memoryType,
        String sourceId,          // the P2 entity id this chunk projects (traceability)
        int chapterOrdinal,
        String text,              // display/context text
        String searchText,        // text used for indexing/search (may be enriched)
        Instant createdAt) {
}
