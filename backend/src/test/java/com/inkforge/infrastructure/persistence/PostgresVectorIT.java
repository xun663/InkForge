package com.inkforge.infrastructure.persistence;

import com.inkforge.retrieval.ChunkEmbeddingStore;
import com.inkforge.retrieval.MemoryChunk;
import com.inkforge.retrieval.MemoryChunkType;
import com.inkforge.retrieval.MemoryChunkRepository;
import com.inkforge.retrieval.MemoryEmbeddingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * pgvector integration tests (Testcontainers, auto-skipped without Docker):
 * vector persistence, cosine query semantics matching InMemory, HNSW index presence.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("postgres")
@Testcontainers(disabledWithoutDocker = true)
class PostgresVectorIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    @Autowired
    private MemoryChunkRepository chunkRepository;

    @Autowired
    private ChunkEmbeddingStore embeddingStore;

    @Autowired
    private MemoryEmbeddingService embeddingService;

    @Autowired
    private com.inkforge.retrieval.MemoryRetriever postgresVectorRetriever;

    @Autowired
    private DataSource dataSource;

    private static final Instant NOW = Instant.parse("2026-08-16T10:00:00Z");

    @Test
    void vectorsPersistAndRoundTrip() {
        chunkRepository.replaceForChapter("n1", 1, List.of(
                new MemoryChunk("c1", "n1", MemoryChunkType.EVENT, "e1", 1,
                        "方源与白凝冰在青茅山相遇", "方源与白凝冰在青茅山相遇", NOW)));

        int embedded = embeddingService.embedNovel("n1");
        assertThat(embedded).isEqualTo(1);

        ChunkEmbeddingStore.StoredEmbedding stored = embeddingStore.find("c1").orElseThrow();
        assertThat(stored.values()).hasSize(1024);
        assertThat(stored.contentHash()).isEqualTo(MemoryEmbeddingService.sha256("方源与白凝冰在青茅山相遇"));
    }

    @Test
    void cosineQueryRanksIdenticalHighestAndMatchesInMemorySemantics() {
        chunkRepository.replaceForChapter("n1", 1, List.of(
                new MemoryChunk("c1", "n1", MemoryChunkType.EVENT, "e1", 1,
                        "方源与白凝冰在青茅山相遇，炼制蛊虫", "方源与白凝冰在青茅山相遇，炼制蛊虫", NOW),
                new MemoryChunk("c2", "n1", MemoryChunkType.EVENT, "e2", 1,
                        "林默拔剑斩向血魔，后山激战", "林默拔剑斩向血魔，后山激战", NOW)));
        embeddingService.embedNovel("n1");

        List<com.inkforge.retrieval.RetrievalResult> results =
                postgresVectorRetriever.retrieve("n1", "方源与白凝冰在青茅山相遇，炼制蛊虫", 2);

        assertThat(results).hasSize(2);
        assertThat(results.getFirst().chunkId()).isEqualTo("c1");
        assertThat(results.getFirst().score()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
        // score 越高越相关（distance 已转换为 similarity）
        assertThat(results.get(0).score()).isGreaterThan(results.get(1).score());
        // 与 InMemory 检索语义一致：同文本 → 最高分
    }

    @Test
    void hnswIndexExists() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT indexdef FROM pg_indexes WHERE tablename = 'memory_chunk' AND indexname = 'idx_memory_chunk_embedding_hnsw'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).contains("hnsw").contains("vector_cosine_ops");
        }
    }
}
