package com.inkforge.retrieval;

import java.util.List;

/**
 * Persistence port for retrieval chunks. Implementations: InMemory (default) and JPA
 * ("postgres" profile). The chunk store is a rebuildable cache over P2 Story Memory.
 */
public interface MemoryChunkRepository {

    /** All chunks of a novel (bulk read for index building). */
    List<MemoryChunk> findByNovelId(String novelId);

    /** Chunks projected from one chapter. */
    List<MemoryChunk> findByNovelIdAndChapter(String novelId, int chapterOrdinal);

    /** Atomically replaces all chunks projected from one chapter (idempotent re-projection). */
    void replaceForChapter(String novelId, int chapterOrdinal, List<MemoryChunk> chunks);

    /** Drops the whole projection of a novel. */
    void deleteByNovelId(String novelId);

    /**
     * Monotonic revision of a novel's chunk set — retrieval caches use it to detect
     * staleness and rebuild deterministically. Bumps on every mutation.
     */
    long revision(String novelId);
}
