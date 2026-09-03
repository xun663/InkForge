package com.inkforge.infrastructure.persistence;

import com.inkforge.retrieval.ChunkEmbeddingStore;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * PostgreSQL embedding store ("postgres" profile) via JdbcTemplate + native SQL —
 * pgvector types deliberately stay OUT of the ORM layer (Hibernate 7 vector mapping
 * risk is isolated here; the domain/entity layers never see vectors).
 */
@Repository
@Profile("postgres")
public class JpaChunkEmbeddingStore implements ChunkEmbeddingStore {

    private final JdbcTemplate jdbc;

    public JpaChunkEmbeddingStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<StoredEmbedding> find(String chunkId) {
        List<StoredEmbedding> rows = jdbc.query(
                "SELECT embedding_content_hash, embedding::text FROM memory_chunk WHERE id = ?",
                (rs, rowNum) -> {
                    String hash = rs.getString(1);
                    String vectorText = rs.getString(2);
                    return new StoredEmbedding("", parseVector(vectorText), hash);
                },
                chunkId);
        return rows.stream().findFirst();
    }

    @Override
    public void save(String chunkId, String novelId, float[] values, String contentHash) {
        jdbc.update("UPDATE memory_chunk SET embedding = ?::vector, embedding_content_hash = ? WHERE id = ?",
                toVectorText(values), contentHash, chunkId);
    }

    @Override
    public void deleteByNovelId(String novelId) {
        jdbc.update("UPDATE memory_chunk SET embedding = NULL, embedding_content_hash = NULL WHERE novel_id = ?",
                novelId);
    }

    /** "[0.1,0.2,...]" → float[]. Accepts null/blank as empty. */
    private static float[] parseVector(String text) {
        if (text == null || text.isBlank()) {
            return new float[0];
        }
        String inner = text.trim();
        if (inner.startsWith("[")) {
            inner = inner.substring(1);
        }
        if (inner.endsWith("]")) {
            inner = inner.substring(0, inner.length() - 1);
        }
        if (inner.isBlank()) {
            return new float[0];
        }
        String[] parts = inner.split(",");
        float[] values = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            values[i] = Float.parseFloat(parts[i].trim());
        }
        return values;
    }

    private static String toVectorText(float[] values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(values[i]);
        }
        return sb.append(']').toString();
    }
}
