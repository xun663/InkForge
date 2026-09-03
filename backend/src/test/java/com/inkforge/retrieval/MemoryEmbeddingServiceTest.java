package com.inkforge.retrieval;

import com.inkforge.provider.EmbeddingProperties;
import com.inkforge.provider.EmbeddingProvider;
import com.inkforge.provider.MockEmbeddingProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryEmbeddingServiceTest {

    private InMemoryMemoryChunkRepository chunkRepository;
    private InMemoryChunkEmbeddingStore embeddingStore;
    private MemoryEmbeddingService service;
    private EmbeddingProperties properties;
    private CountingProvider countingProvider;

    private static final Instant NOW = Instant.parse("2026-08-16T10:00:00Z");

    /** Wraps the deterministic mock so we can count actual provider calls. */
    private static class CountingProvider implements EmbeddingProvider {

        final EmbeddingProvider delegate;
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger batchCalls = new AtomicInteger();

        CountingProvider(EmbeddingProvider delegate) {
            this.delegate = delegate;
        }

        @Override
        public String name() {
            return delegate.name();
        }

        @Override
        public com.inkforge.provider.Embedding embed(String text) {
            calls.incrementAndGet();
            return delegate.embed(text);
        }

        @Override
        public List<com.inkforge.provider.Embedding> embedBatch(List<String> texts) {
            batchCalls.incrementAndGet();
            return delegate.embedBatch(texts);
        }
    }

    @BeforeEach
    void setUp() {
        chunkRepository = new InMemoryMemoryChunkRepository();
        embeddingStore = new InMemoryChunkEmbeddingStore();
        properties = new EmbeddingProperties("mock", "bge-m3", "https://unused", "", 1024, 2, 120);
        countingProvider = new CountingProvider(new MockEmbeddingProvider(properties));
        service = new MemoryEmbeddingService(countingProvider, chunkRepository, embeddingStore, properties);
    }

    private static MemoryChunk chunk(String id, String novelId, int ordinal, String text) {
        return new MemoryChunk(id, novelId, MemoryChunkType.EVENT, "src:" + id, ordinal, text, text, NOW);
    }

    @Test
    void embedsAllChunksViaBatchAndPersists() {
        chunkRepository.replaceForChapter("n1", 1, List.of(
                chunk("a", "n1", 1, "方源与白凝冰在青茅山相遇"),
                chunk("b", "n1", 1, "林默拔剑斩向血魔"),
                chunk("c", "n1", 1, "蛊虫炼化")));

        int embedded = service.embedNovel("n1");

        assertThat(embedded).isEqualTo(3);
        assertThat(countingProvider.batchCalls.get()).isEqualTo(2); // batch-size 2 → 2 次
        assertThat(embeddingStore.find("a")).isPresent();
        assertThat(embeddingStore.find("a").get().contentHash())
                .isEqualTo(MemoryEmbeddingService.sha256("方源与白凝冰在青茅山相遇"));
        assertThat(embeddingStore.find("b").get().values()).hasSize(1024);
    }

    @Test
    void existingValidEmbeddingsAreNotRecomputed() {
        chunkRepository.replaceForChapter("n1", 1, List.of(chunk("a", "n1", 1, "同一文本")));
        service.embedNovel("n1");
        int callsAfterFirst = countingProvider.calls.get();

        int embedded = service.embedNovel("n1");

        assertThat(embedded).isZero();
        assertThat(countingProvider.calls.get()).isEqualTo(callsAfterFirst);
    }

    @Test
    void contentChangeTriggersReEmbedding() {
        chunkRepository.replaceForChapter("n1", 1, List.of(chunk("a", "n1", 1, "旧文本")));
        service.embedNovel("n1");

        // 重新投影：同一 chunk id、新 searchText
        chunkRepository.replaceForChapter("n1", 1, List.of(chunk("a", "n1", 1, "新文本")));
        int embedded = service.embedNovel("n1");

        assertThat(embedded).isEqualTo(1);
        assertThat(embeddingStore.find("a").get().contentHash())
                .isEqualTo(MemoryEmbeddingService.sha256("新文本"));
    }

    @Test
    void novelsAreIsolatedInStore() {
        chunkRepository.replaceForChapter("n1", 1, List.of(chunk("a", "n1", 1, "甲")));
        chunkRepository.replaceForChapter("n2", 1, List.of(chunk("b", "n2", 1, "乙")));
        service.embedNovel("n1");
        service.embedNovel("n2");

        embeddingStore.deleteByNovelId("n1");

        assertThat(embeddingStore.find("a")).isEmpty();
        assertThat(embeddingStore.find("b")).isPresent();
    }
}
