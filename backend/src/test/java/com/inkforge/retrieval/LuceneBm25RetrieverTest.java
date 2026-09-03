package com.inkforge.retrieval;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BM25 behavior tests. Absolute scores are intentionally NOT asserted — Lucene internals
 * and corpus size change them. What matters: recall, relative ranking, topK, isolation,
 * trace fields, deterministic rebuilds, and staleness detection.
 */
class LuceneBm25RetrieverTest {

    private InMemoryMemoryChunkRepository chunkRepository;
    private LuceneBm25Retriever retriever;

    @BeforeEach
    void setUp() {
        chunkRepository = new InMemoryMemoryChunkRepository();
        retriever = new LuceneBm25Retriever(chunkRepository);
    }

    private static MemoryChunk chunk(String id, String novelId, int ordinal,
                                     MemoryChunkType type, String text) {
        return new MemoryChunk(id, novelId, type, "src:" + id, ordinal, text, text, Instant.now());
    }

    private void seedNovel1() {
        chunkRepository.replaceForChapter("n1", 10, List.of(
                chunk("c1", "n1", 10, MemoryChunkType.EVENT, "第11章事件「后山对峙」：林默与血魔在后山对峙，血魔逃离。"),
                chunk("c2", "n1", 9, MemoryChunkType.SUMMARY, "第10章摘要：方源在青茅山炼制蛊虫，遇到白凝冰。"),
                chunk("c3", "n1", 8, MemoryChunkType.FACT, "「林默」历史状态：境界=金丹（第9章）")));
    }

    @Test
    void chineseQueryRecallsRelevantChunks() {
        seedNovel1();

        List<RetrievalResult> results = retriever.retrieve("n1", "玄霜剑 血魔 后山", 10);

        assertThat(results).isNotEmpty();
        // 与查询最相关的应是最匹配的事件 chunk
        assertThat(results.getFirst().chunkId()).isEqualTo("c1");
        assertThat(results).extracting(RetrievalResult::chunkId).contains("c1");
    }

    @Test
    void irrelevantContentRanksLower() {
        seedNovel1();

        // query spans both chunks: c1 matches 3 terms, c2 matches only 1 (Lucene drops 0-score
        // docs from TopDocs, so both must score to compare relative ranking)
        List<RetrievalResult> results = retriever.retrieve("n1", "血魔 后山 对峙 蛊虫", 10);

        int relevantRank = -1;
        int irrelevantRank = -1;
        for (int i = 0; i < results.size(); i++) {
            if (results.get(i).chunkId().equals("c1")) {
                relevantRank = i;
            }
            if (results.get(i).chunkId().equals("c2")) {
                irrelevantRank = i;
            }
        }
        assertThat(relevantRank).isNotNegative();
        assertThat(irrelevantRank).isNotNegative();
        assertThat(relevantRank).isLessThan(irrelevantRank);
    }

    @Test
    void topKIsRespected() {
        seedNovel1();

        assertThat(retriever.retrieve("n1", "蛊虫 青茅山 白凝冰 林默 金丹", 2)).hasSize(2);
        assertThat(retriever.retrieve("n1", "蛊虫 青茅山 白凝冰 林默 金丹", 10)).hasSize(3);
    }

    @Test
    void blankQueryReturnsEmpty() {
        seedNovel1();

        assertThat(retriever.retrieve("n1", "", 10)).isEmpty();
        assertThat(retriever.retrieve("n1", "   ", 10)).isEmpty();
    }

    @Test
    void emptyIndexReturnsEmpty() {
        assertThat(retriever.retrieve("n1", "林默", 10)).isEmpty();
    }

    @Test
    void novelsAreStrictlyIsolated() {
        seedNovel1();
        chunkRepository.replaceForChapter("n2", 10, List.of(
                chunk("d1", "n2", 10, MemoryChunkType.EVENT, "第11章事件：林默与血魔对峙，血魔逃离。")));

        List<RetrievalResult> resultsForN2 = retriever.retrieve("n2", "林默 血魔", 10);

        assertThat(resultsForN2).isNotEmpty();
        assertThat(resultsForN2).allMatch(r -> r.novelId().equals("n2"));
        assertThat(resultsForN2).extracting(RetrievalResult::chunkId).containsExactly("d1");
    }

    @Test
    void resultsCarryTraceFields() {
        seedNovel1();

        RetrievalResult first = retriever.retrieve("n1", "后山 对峙", 1).getFirst();

        assertThat(first.chunkId()).isEqualTo("c1");
        assertThat(first.novelId()).isEqualTo("n1");
        assertThat(first.chapterOrdinal()).isEqualTo(10);
        assertThat(first.memoryType()).isEqualTo(MemoryChunkType.EVENT);
        assertThat(first.sourceId()).isEqualTo("src:c1");
        assertThat(first.score()).isPositive();
        assertThat(first.text()).contains("后山对峙");
    }

    @Test
    void rebuildIsDeterministicAcrossRevisions() {
        seedNovel1();
        List<RetrievalResult> first = retriever.retrieve("n1", "后山 对峙 血魔", 10);

        // bump the revision by re-projecting identical content → rebuild → same ranking
        chunkRepository.replaceForChapter("n1", 10, List.of(
                chunk("c1", "n1", 10, MemoryChunkType.EVENT, "第11章事件「后山对峙」：林默与血魔在后山对峙，血魔逃离。")));
        List<RetrievalResult> second = retriever.retrieve("n1", "后山 对峙 血魔", 10);

        assertThat(second).extracting(RetrievalResult::chunkId)
                .containsExactlyElementsOf(first.stream().map(RetrievalResult::chunkId).toList());
    }

    @Test
    void staleIndexIsNotReturnedAfterProjectionUpdate() {
        seedNovel1();
        assertThat(retriever.retrieve("n1", "元婴", 10)).isEmpty();

        // projection update removes old chunks of chapter 10 and adds new content
        chunkRepository.replaceForChapter("n1", 10, List.of(
                chunk("c9", "n1", 10, MemoryChunkType.EVENT, "第11章事件：林默突破元婴境界，剑斩强敌。")));

        List<RetrievalResult> after = retriever.retrieve("n1", "元婴", 10);
        assertThat(after).isNotEmpty();
        assertThat(after).extracting(RetrievalResult::chunkId).contains("c9");
        // 旧 chunk 已随 replace 消失，绝不会被旧索引返回
        assertThat(retriever.retrieve("n1", "后山对峙", 10))
                .extracting(RetrievalResult::chunkId)
                .doesNotContain("c1");
    }
}
