package com.inkforge.retrieval;

import com.inkforge.common.EmbeddingException;
import com.inkforge.provider.Embedding;
import com.inkforge.provider.EmbeddingProperties;
import com.inkforge.provider.EmbeddingProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Default (zero-Docker) vector retrieval: brute-force cosine similarity over the
 * in-memory chunk embedding store.
 *
 * <p>Guards: dimension mismatch fails loudly; NaN/Infinity never enters ranking
 * (thrown on sight); zero vectors score 0 (no signal) instead of NaN.
 * Score semantics: HIGHER = MORE relevant (cosine similarity, 1 = identical).
 */
@Component
public class InMemoryVectorRetriever implements MemoryRetriever {

    private final EmbeddingProvider embeddingProvider;
    private final MemoryChunkRepository chunkRepository;
    private final ChunkEmbeddingStore embeddingStore;
    private final EmbeddingProperties properties;

    public InMemoryVectorRetriever(EmbeddingProvider embeddingProvider,
                                   MemoryChunkRepository chunkRepository,
                                   ChunkEmbeddingStore embeddingStore,
                                   EmbeddingProperties properties) {
        this.embeddingProvider = embeddingProvider;
        this.chunkRepository = chunkRepository;
        this.embeddingStore = embeddingStore;
        this.properties = properties;
    }

    @Override
    public List<RetrievalResult> retrieve(String novelId, String query, int topK) {
        if (novelId == null || query == null || query.isBlank() || topK <= 0) {
            return List.of();
        }
        Embedding queryEmbedding = embeddingProvider.embed(query);
        checkDimension(queryEmbedding);

        List<MemoryChunk> chunks = chunkRepository.findByNovelId(novelId);
        List<ScoredChunk> scored = new ArrayList<>();
        for (MemoryChunk chunk : chunks) {
            ChunkEmbeddingStore.StoredEmbedding stored = embeddingStore.find(chunk.id()).orElse(null);
            if (stored == null) {
                continue; // not embedded yet — no vector, no result
            }
            Embedding chunkEmbedding = new Embedding(stored.values());
            checkDimension(chunkEmbedding);
            scored.add(new ScoredChunk(chunk, cosine(queryEmbedding, chunkEmbedding)));
        }

        return scored.stream()
                .sorted(Comparator.comparingDouble(ScoredChunk::similarity).reversed())
                .limit(topK)
                .map(s -> new RetrievalResult(
                        s.chunk().id(), s.chunk().novelId(), s.chunk().chapterOrdinal(),
                        s.chunk().memoryType(), s.chunk().sourceId(), s.chunk().text(), s.similarity()))
                .toList();
    }

    private static double cosine(Embedding a, Embedding b) {
        float[] av = a.values();
        float[] bv = b.values();
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < av.length; i++) {
            dot += (double) av[i] * bv[i];
            normA += (double) av[i] * av[i];
            normB += (double) bv[i] * bv[i];
        }
        if (normA == 0 || normB == 0) {
            return 0; // zero vector = no signal — safe, never NaN
        }
        double similarity = dot / (Math.sqrt(normA) * Math.sqrt(normB));
        if (!Double.isFinite(similarity)) {
            throw new EmbeddingException("embedding 含 NaN/Infinity，拒绝参与排序");
        }
        return similarity;
    }

    private void checkDimension(Embedding embedding) {
        if (embedding.dimension() != properties.dimension()) {
            throw new EmbeddingException(
                    "Embedding 维度不匹配：期望 " + properties.dimension()
                            + "，实际 " + embedding.dimension() + "（不允许截断或填充）");
        }
    }

    private record ScoredChunk(MemoryChunk chunk, double similarity) {
    }
}
