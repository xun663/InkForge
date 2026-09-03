package com.inkforge.infrastructure.persistence;

import com.inkforge.retrieval.MemoryChunkType;
import com.inkforge.retrieval.RetrievalResult;
import com.inkforge.retrieval.RetrievalTrace;
import com.inkforge.retrieval.RetrievalTraceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P3-G 补齐的 Trace 持久化 IT（postgres profile）：queries/pipeline JSONB 往返、
 * generationId 关联、按 novel 查询。Docker 不可用时自动 skipped。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("postgres")
@Testcontainers(disabledWithoutDocker = true)
class PostgresTraceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    @Autowired
    private RetrievalTraceRepository traceRepository;

    private static final Instant NOW = Instant.parse("2026-08-17T00:00:00Z");

    @Test
    void traceRoundTripsThroughPostgres() {
        RetrievalTrace trace = new RetrievalTrace("t1", "n1", "g1",
                List.of("方源与白凝冰", "方源 白凝冰"),
                Map.of("bm25", List.of(new RetrievalResult("c1", "n1", 3, MemoryChunkType.EVENT,
                                "src:c1", "第4章事件：结盟。", 5.66)),
                        "final", List.of(new RetrievalResult("c1", "n1", 3, MemoryChunkType.EVENT,
                                "src:c1", "第4章事件：结盟。", 0.032))),
                NOW);
        traceRepository.save(trace);

        RetrievalTrace back = traceRepository.findById("t1").orElseThrow();

        assertThat(back.novelId()).isEqualTo("n1");
        assertThat(back.generationId()).isEqualTo("g1");
        assertThat(back.queries()).containsExactly("方源与白凝冰", "方源 白凝冰");
        assertThat(back.pipeline().get("bm25").getFirst().score()).isEqualTo(5.66);
        assertThat(back.pipeline().get("final").getFirst().memoryType()).isEqualTo(MemoryChunkType.EVENT);
        assertThat(traceRepository.findByNovelId("n1", 10)).hasSize(1);
        assertThat(traceRepository.findByNovelId("other", 10)).isEmpty();
    }
}
