package com.inkforge.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PassThroughRerankerTest {

    private final PassThroughReranker reranker = new PassThroughReranker();

    private static RetrievalResult result(String chunkId) {
        return new RetrievalResult(chunkId, "n1", 1, MemoryChunkType.EVENT, "src:" + chunkId,
                "原文文本-" + chunkId, 1.0);
    }

    @Test
    void keepsFusionOrder() {
        List<RetrievalResult> out = reranker.rerank("查询", List.of(
                result("b"), result("a"), result("c")), 10);

        assertThat(out).extracting(RetrievalResult::chunkId).containsExactly("b", "a", "c");
    }

    @Test
    void capsAtTopK() {
        List<RetrievalResult> out = reranker.rerank("查询",
                List.of(result("a"), result("b"), result("c")), 2);

        assertThat(out).hasSize(2);
    }

    @Test
    void emptyInputReturnsEmpty() {
        assertThat(reranker.rerank("查询", List.of(), 10)).isEmpty();
        assertThat(reranker.rerank("查询", null, 10)).isEmpty();
        assertThat(reranker.rerank("查询", List.of(result("a")), 0)).isEmpty();
    }

    @Test
    void doesNotModifyOriginalResults() {
        List<RetrievalResult> candidates = List.of(result("a"), result("b"));
        reranker.rerank("查询", candidates, 1);

        assertThat(candidates).extracting(RetrievalResult::text)
                .containsExactly("原文文本-a", "原文文本-b");
    }
}
