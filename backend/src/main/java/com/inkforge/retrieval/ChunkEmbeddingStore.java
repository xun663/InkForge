package com.inkforge.retrieval;

import java.util.Optional;

/**
 * Storage port for chunk embeddings — retrieval infrastructure data, deliberately
 * separate from the P3-B MemoryChunk record (which stays embedding-free).
 *
 * <p>Validity is judged by {@code (chunkId, contentHash)}: a stored embedding is only
 * valid when its content hash equals the hash of the chunk's CURRENT searchText —
 * otherwise the chunk must be re-embedded (never silently reuse a stale vector).
 */
public interface ChunkEmbeddingStore {

    Optional<StoredEmbedding> find(String chunkId);

    /** Idempotent upsert of the embedding for one chunk. */
    void save(String chunkId, String novelId, float[] values, String contentHash);

    void deleteByNovelId(String novelId);

    record StoredEmbedding(String novelId, float[] values, String contentHash) {

        public StoredEmbedding {
            values = values == null ? new float[0] : values.clone();
        }

        @Override
        public float[] values() {
            return values.clone();
        }
    }
}
