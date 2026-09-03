package com.inkforge.retrieval;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalTraceRepositoryTest {

    private InMemoryRetrievalTraceRepository repository;

    @BeforeEach
    void setUp() {
        repository = new InMemoryRetrievalTraceRepository();
    }

    private static RetrievalTrace trace(String id, String novelId, String generationId) {
        return new RetrievalTrace(id, novelId, generationId, List.of("q1"),
                Map.of("bm25", List.of(new RetrievalResult("c1", novelId, 3, MemoryChunkType.EVENT,
                        "src:c1", "文本", 0.9))), Instant.now());
    }

    @Test
    void saveAndFindById() {
        repository.save(trace("t1", "n1", "g1"));

        assertThat(repository.findById("t1")).isPresent().get()
                .satisfies(t -> {
                    assertThat(t.novelId()).isEqualTo("n1");
                    assertThat(t.generationId()).isEqualTo("g1");
                    assertThat(t.queries()).containsExactly("q1");
                    assertThat(t.pipeline().get("bm25").getFirst().chunkId()).isEqualTo("c1");
                });
        assertThat(repository.findById("missing")).isEmpty();
    }

    @Test
    void findByNovelIdReturnsRecentFirstWithLimit() {
        repository.save(trace("t1", "n1", "g1"));
        repository.save(trace("t2", "n1", "g2"));
        repository.save(trace("t3", "n2", "g3"));

        assertThat(repository.findByNovelId("n1", 10)).hasSize(2);
        assertThat(repository.findByNovelId("n1", 1)).hasSize(1);
        assertThat(repository.findByNovelId("n2", 10)).extracting(RetrievalTrace::id)
                .containsExactly("t3");
    }
}
