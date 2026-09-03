package com.inkforge.retrieval;

import com.inkforge.common.EmbeddingException;
import com.inkforge.provider.EmbeddingProperties;
import com.inkforge.provider.MockEmbeddingProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryVectorRetrieverTest {

    private static final Instant NOW = Instant.parse("2026-08-16T10:00:00Z");

    private InMemoryMemoryChunkRepository chunkRepository;
    private InMemoryChunkEmbeddingStore embeddingStore;
    private MemoryEmbeddingService embeddingService;
    private InMemoryVectorRetriever retriever;
    private EmbeddingProperties properties;

    @BeforeEach
    void setUp() {
        properties = new EmbeddingProperties("mock", "bge-m3", "https://unused", "", 1024, 16, 120);
        chunkRepository = new InMemoryMemoryChunkRepository();
        embeddingStore = new InMemoryChunkEmbeddingStore();
        MockEmbeddingProvider mock = new MockEmbeddingProvider(properties);
        embeddingService = new MemoryEmbeddingService(mock, chunkRepository, embeddingStore, properties);
        retriever = new InMemoryVectorRetriever(mock, chunkRepository, embeddingStore, properties);
    }

    private static MemoryChunk chunk(String id, String novelId, int ordinal, String text) {
        return new MemoryChunk(id, novelId, MemoryChunkType.EVENT, "src:" + id, ordinal, text, text, NOW);
    }

    private void seed(String novelId) {
        chunkRepository.replaceForChapter(novelId, 1, List.of(
                chunk("c1", novelId, 1, "方源与白凝冰在青茅山相遇，炼制蛊虫"),
                chunk("c2", novelId, 1, "林默拔剑斩向血魔，后山激战"),
                chunk("c3", novelId, 1, "苏清雪在宗门闭关修炼剑法")));
        embeddingService.embedNovel(novelId);
    }

    @Test
    void identicalTextRanksHighest() {
        seed("n1");

        List<RetrievalResult> results = retriever.retrieve("n1", "方源与白凝冰在青茅山相遇，炼制蛊虫", 3);

        assertThat(results).isNotEmpty();
        assertThat(results.getFirst().chunkId()).isEqualTo("c1");
        assertThat(results.getFirst().score()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void relatedRanksAboveUnrelated() {
        seed("n1");

        List<RetrievalResult> results = retriever.retrieve("n1", "方源 白凝冰 青茅山 蛊虫", 3);

        assertThat(results).extracting(RetrievalResult::chunkId).contains("c1");
        double c1 = scoreOf(results, "c1");
        double c3 = scoreOf(results, "c3");
        assertThat(c1).isGreaterThan(c3);
    }

    @Test
    void topKIsRespected() {
        seed("n1");

        assertThat(retriever.retrieve("n1", "方源 蛊虫 林默 血魔 苏清雪 宗门", 2)).hasSize(2);
        assertThat(retriever.retrieve("n1", "方源 蛊虫 林默 血魔 苏清雪 宗门", 10)).hasSize(3);
    }

    @Test
    void blankQueryReturnsEmpty() {
        seed("n1");

        assertThat(retriever.retrieve("n1", "", 3)).isEmpty();
        assertThat(retriever.retrieve("n1", "   ", 3)).isEmpty();
    }

    @Test
    void emptyIndexReturnsEmpty() {
        assertThat(retriever.retrieve("n1", "方源", 3)).isEmpty();
    }

    @Test
    void novelsAreStrictlyIsolated() {
        seed("n1");
        seed("n2");

        List<RetrievalResult> results = retriever.retrieve("n2", "方源 蛊虫", 10);

        assertThat(results).allMatch(r -> r.novelId().equals("n2"));
    }

    @Test
    void scoreIsHigherForMoreRelevant() {
        seed("n1");

        List<RetrievalResult> results = retriever.retrieve("n1", "方源 白凝冰 青茅山 蛊虫", 3);

        assertThat(results).extracting(RetrievalResult::score)
                .isSortedAccordingTo((a, b) -> Double.compare(b, a)); // descending
    }

    @Test
    void dimensionMismatchFailsLoudly() {
        // 篡改存储：chunk 存在但向量维度不匹配
        chunkRepository.replaceForChapter("n1", 1, List.of(chunk("c1", "n1", 1, "文本")));
        embeddingStore.save("c1", "n1", new float[128], "hash");

        assertThatThrownBy(() -> retriever.retrieve("n1", "方源", 3))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("维度不匹配");
    }

    @Test
    void nanEmbeddingIsRejectedNotSilentlyRanked() {
        float[] nanVector = new float[1024];
        nanVector[0] = Float.NaN;
        embeddingStore.save("c1", "n1", nanVector, "hash");
        chunkRepository.replaceForChapter("n1", 1, List.of(chunk("c1", "n1", 1, "文本")));

        assertThatThrownBy(() -> retriever.retrieve("n1", "方源", 3))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("NaN");
    }

    @Test
    void resultsCarryTraceFields() {
        seed("n1");

        RetrievalResult first = retriever.retrieve("n1", "方源 蛊虫", 1).getFirst();

        assertThat(first.chunkId()).isEqualTo("c1");
        assertThat(first.novelId()).isEqualTo("n1");
        assertThat(first.memoryType()).isEqualTo(MemoryChunkType.EVENT);
        assertThat(first.sourceId()).isEqualTo("src:c1");
        assertThat(first.chapterOrdinal()).isEqualTo(1);
    }

    private static double scoreOf(List<RetrievalResult> results, String chunkId) {
        return results.stream()
                .filter(r -> r.chunkId().equals(chunkId))
                .findFirst()
                .orElseThrow()
                .score();
    }
}
