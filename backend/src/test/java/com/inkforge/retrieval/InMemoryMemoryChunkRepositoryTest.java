package com.inkforge.retrieval;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryMemoryChunkRepositoryTest {

    private InMemoryMemoryChunkRepository repository;

    @BeforeEach
    void setUp() {
        repository = new InMemoryMemoryChunkRepository();
    }

    private static MemoryChunk chunk(String id, String novelId, int ordinal, MemoryChunkType type) {
        return new MemoryChunk(id, novelId, type, "src-" + id, ordinal, "文本", "文本", Instant.now());
    }

    @Test
    void replaceForChapterIsIdempotentAndBumpsRevision() {
        long revisionBefore = repository.revision("n1");

        repository.replaceForChapter("n1", 3, List.of(
                chunk("c1", "n1", 3, MemoryChunkType.EVENT),
                chunk("c2", "n1", 3, MemoryChunkType.SUMMARY)));
        assertThat(repository.revision("n1")).isGreaterThan(revisionBefore);
        assertThat(repository.findByNovelIdAndChapter("n1", 3)).hasSize(2);

        // re-projection replaces, never duplicates
        repository.replaceForChapter("n1", 3, List.of(chunk("c1", "n1", 3, MemoryChunkType.EVENT)));
        assertThat(repository.findByNovelIdAndChapter("n1", 3)).hasSize(1);
        assertThat(repository.findByNovelIdAndChapter("n1", 3).getFirst().id()).isEqualTo("c1");
    }

    @Test
    void novelsAreIsolated() {
        repository.replaceForChapter("n1", 1, List.of(chunk("a1", "n1", 1, MemoryChunkType.EVENT)));
        repository.replaceForChapter("n2", 1, List.of(chunk("b1", "n2", 1, MemoryChunkType.EVENT)));

        assertThat(repository.findByNovelId("n1")).extracting(MemoryChunk::id).containsExactly("a1");
        assertThat(repository.findByNovelId("n2")).extracting(MemoryChunk::id).containsExactly("b1");
        assertThat(repository.findByNovelIdAndChapter("n1", 1)).extracting(MemoryChunk::novelId)
                .containsOnly("n1");
    }

    @Test
    void deleteByNovelIdRemovesAllChunksAndBumpsRevision() {
        repository.replaceForChapter("n1", 1, List.of(chunk("a1", "n1", 1, MemoryChunkType.EVENT)));
        long revisionBefore = repository.revision("n1");

        repository.deleteByNovelId("n1");

        assertThat(repository.findByNovelId("n1")).isEmpty();
        assertThat(repository.revision("n1")).isGreaterThan(revisionBefore);
    }
}
