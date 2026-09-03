package com.inkforge.retrieval;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Default in-memory embedding store (rebuildable cache; lost on restart by design). */
@Component
public class InMemoryChunkEmbeddingStore implements ChunkEmbeddingStore {

    private final Map<String, StoredEmbedding> store = new ConcurrentHashMap<>();

    @Override
    public Optional<StoredEmbedding> find(String chunkId) {
        return Optional.ofNullable(store.get(chunkId));
    }

    @Override
    public void save(String chunkId, String novelId, float[] values, String contentHash) {
        store.put(chunkId, new StoredEmbedding(novelId, values, contentHash));
    }

    @Override
    public void deleteByNovelId(String novelId) {
        store.entrySet().removeIf(entry -> novelId.equals(entry.getValue().novelId()));
    }
}
