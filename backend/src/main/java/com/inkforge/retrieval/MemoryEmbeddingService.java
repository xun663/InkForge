package com.inkforge.retrieval;

import com.inkforge.common.EmbeddingException;
import com.inkforge.provider.Embedding;
import com.inkforge.provider.EmbeddingProperties;
import com.inkforge.provider.EmbeddingProvider;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Embedding lifecycle for MemoryChunks — separate responsibility from projection:
 * <ul>
 *   <li>MemoryChunkProjectionService: P2 Memory → MemoryChunk (deterministic, local)</li>
 *   <li>this service: MemoryChunk → embedding (external model call, batchable, retryable)</li>
 * </ul>
 *
 * <p>Idempotency: a chunk is re-embedded only when it has NO stored embedding or its
 * content hash changed (chunk id is stable but searchText may have been re-projected).
 * Batch embedding is preferred; the provider splits internally when it must.
 */
@Service
public class MemoryEmbeddingService {

    private final EmbeddingProvider embeddingProvider;
    private final MemoryChunkRepository chunkRepository;
    private final ChunkEmbeddingStore embeddingStore;
    private final EmbeddingProperties properties;

    public MemoryEmbeddingService(EmbeddingProvider embeddingProvider,
                                  MemoryChunkRepository chunkRepository,
                                  ChunkEmbeddingStore embeddingStore,
                                  EmbeddingProperties properties) {
        this.embeddingProvider = embeddingProvider;
        this.chunkRepository = chunkRepository;
        this.embeddingStore = embeddingStore;
        this.properties = properties;
    }

    /**
     * Embeds every chunk of the novel that lacks a valid embedding.
     *
     * @return number of chunks embedded (0 = everything already valid)
     */
    public int embedNovel(String novelId) {
        List<MemoryChunk> chunks = chunkRepository.findByNovelId(novelId);
        List<MemoryChunk> pending = chunks.stream()
                .filter(this::needsEmbedding)
                .toList();
        if (pending.isEmpty()) {
            return 0;
        }
        for (int start = 0; start < pending.size(); start += properties.batchSize()) {
            List<MemoryChunk> batch = pending.subList(start,
                    Math.min(start + properties.batchSize(), pending.size()));
            List<String> texts = batch.stream().map(MemoryChunk::searchText).toList();
            List<Embedding> embeddings = embeddingProvider.embedBatch(texts);
            if (embeddings.size() != texts.size()) {
                throw new EmbeddingException(
                        "batch embedding 数量不匹配：请求 " + texts.size() + " 返回 " + embeddings.size());
            }
            for (int i = 0; i < batch.size(); i++) {
                Embedding embedding = embeddings.get(i);
                checkDimension(embedding);
                embeddingStore.save(batch.get(i).id(), batch.get(i).novelId(),
                        embedding.values(), sha256(texts.get(i)));
            }
        }
        return pending.size();
    }

    /** Valid = stored AND content hash matches the chunk's current searchText. */
    private boolean needsEmbedding(MemoryChunk chunk) {
        return embeddingStore.find(chunk.id())
                .map(stored -> !stored.contentHash().equals(sha256(chunk.searchText())))
                .orElse(true);
    }

    private void checkDimension(Embedding embedding) {
        if (embedding.dimension() != properties.dimension()) {
            throw new EmbeddingException(
                    "Embedding 维度不匹配：provider 返回 " + embedding.dimension()
                            + "，配置 dimension=" + properties.dimension() + "（不允许截断或填充）");
        }
    }

    /** Deterministic content hash of the embedded text. */
    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
