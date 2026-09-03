package com.inkforge.infrastructure.persistence;

import com.inkforge.common.EmbeddingException;
import com.inkforge.provider.Embedding;
import com.inkforge.provider.EmbeddingProperties;
import com.inkforge.provider.EmbeddingProvider;
import com.inkforge.retrieval.MemoryChunkType;
import com.inkforge.retrieval.MemoryRetriever;
import com.inkforge.retrieval.RetrievalResult;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * pgvector retrieval ("postgres" profile) via JdbcTemplate + native SQL.
 * Uses the HNSW cosine index (V3 migration); converts cosine DISTANCE to similarity
 * (1 - distance) so the score semantics match InMemoryVectorRetriever:
 * HIGHER = MORE relevant.
 */
@Repository
@Profile("postgres")
public class PostgresVectorRetriever implements MemoryRetriever {

    private final JdbcTemplate jdbc;
    private final EmbeddingProvider embeddingProvider;
    private final EmbeddingProperties properties;

    public PostgresVectorRetriever(JdbcTemplate jdbc, EmbeddingProvider embeddingProvider,
                                   EmbeddingProperties properties) {
        this.jdbc = jdbc;
        this.embeddingProvider = embeddingProvider;
        this.properties = properties;
    }

    @Override
    public List<RetrievalResult> retrieve(String novelId, String query, int topK) {
        if (novelId == null || query == null || query.isBlank() || topK <= 0) {
            return List.of();
        }
        Embedding queryEmbedding = embeddingProvider.embed(query);
        if (queryEmbedding.dimension() != properties.dimension()) {
            throw new EmbeddingException(
                    "Embedding 维度不匹配：期望 " + properties.dimension()
                            + "，实际 " + queryEmbedding.dimension());
        }
        String vectorText = toVectorText(queryEmbedding.values());

        return jdbc.query("""
                        SELECT id, chapter_ordinal, memory_type, source_id, text,
                               1 - (embedding <=> ?::vector) AS similarity
                        FROM memory_chunk
                        WHERE novel_id = ? AND embedding IS NOT NULL
                        ORDER BY embedding <=> ?::vector
                        LIMIT ?
                        """,
                (rs, rowNum) -> {
                    double similarity = rs.getDouble(6);
                    if (rs.wasNull()) {
                        similarity = 0; // zero vector = no signal, never NaN
                    }
                    return new RetrievalResult(
                            rs.getString(1), novelId, rs.getInt(2),
                            MemoryChunkType.valueOf(rs.getString(3)),
                            rs.getString(4), rs.getString(5), similarity);
                },
                vectorText, novelId, vectorText, topK);
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
