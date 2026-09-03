package com.inkforge.retrieval;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** MultiQuery merging: dedupe by chunkId, keep highest score, descending, topK. */
class MultiQueryRetrievalTest {

    private MemoryRetriever bm25;
    private MemoryRetriever vector;
    private Reranker reranker;
    private HybridRetrievalService service;

    private final RetrievalProperties props = new RetrievalProperties(30, 30, 30, 8, 60, "passthrough", 15, 200);

    @BeforeEach
    void setUp() {
        bm25 = mock(MemoryRetriever.class);
        vector = mock(MemoryRetriever.class);
        reranker = mock(Reranker.class);
        service = new HybridRetrievalService(bm25, vector, reranker, props);
    }

    private static RetrievalResult result(String chunkId, double score) {
        return new RetrievalResult(chunkId, "n1", 1, MemoryChunkType.EVENT, "src:" + chunkId, "文本", score);
    }

    private void seed(RetrievalResult bm25Result, RetrievalResult vectorResult) {
        when(bm25.retrieve(anyString(), anyString(), eq(30))).thenReturn(
                bm25Result == null ? List.of() : List.of(bm25Result));
        when(vector.retrieve(anyString(), anyString(), eq(30))).thenReturn(
                vectorResult == null ? List.of() : List.of(vectorResult));
        when(reranker.rerank(anyString(), anyList(), eq(8))).thenAnswer(inv -> inv.getArgument(1));
    }

    @Test
    void mergesQueriesDeduplicatingChunkIdsKeepingHighestScore() {
        // query1 命中 a(0.9) b(0.5)；query2 命中 a(0.3) c(0.8)
        when(bm25.retrieve(anyString(), eq("q1"), eq(30))).thenReturn(List.of(result("a", 0.9), result("b", 0.5)));
        when(vector.retrieve(anyString(), eq("q1"), eq(30))).thenReturn(List.of());
        when(bm25.retrieve(anyString(), eq("q2"), eq(30))).thenReturn(List.of(result("a", 0.3), result("c", 0.8)));
        when(vector.retrieve(anyString(), eq("q2"), eq(30))).thenReturn(List.of());
        when(reranker.rerank(anyString(), anyList(), eq(8))).thenAnswer(inv -> inv.getArgument(1));

        List<RetrievalResult> out = service.retrieveMulti("n1", List.of("q1", "q2"));

        // 每 query 独立跑 Hybrid（RRF 融合 BM25+Vector 在单 query 内），跨 query 取最高分：
        // a 在两个 query 中各得 1/61（保留最高分仍为 1/61）> b(1/62) = c(1/62)，
        // 同分时保持首次出现顺序 → a, b, c
        assertThat(out).extracting(RetrievalResult::chunkId).containsExactly("a", "b", "c");
        assertThat(out.getFirst().score()).isCloseTo(1.0 / 61, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void blankAndNullQueriesAreSkipped() {
        when(bm25.retrieve(anyString(), anyString(), eq(30))).thenReturn(List.of(result("a", 1.0)));
        when(vector.retrieve(anyString(), anyString(), eq(30))).thenReturn(List.of());
        when(reranker.rerank(anyString(), anyList(), eq(8))).thenAnswer(inv -> inv.getArgument(1));

        assertThat(service.retrieveMulti("n1", java.util.Arrays.asList("", null, "q"))).isNotEmpty();
        assertThat(service.retrieveMulti("n1", List.of())).isEmpty();
        assertThat(service.retrieveMulti("n1", null)).isEmpty();
    }

    @Test
    void oneQueryFailsOthersStillWork() {
        when(bm25.retrieve(anyString(), eq("bad"), eq(30))).thenThrow(new IllegalStateException("检索挂了"));
        when(vector.retrieve(anyString(), eq("bad"), eq(30))).thenThrow(new IllegalStateException("检索挂了"));
        when(bm25.retrieve(anyString(), eq("good"), eq(30))).thenReturn(List.of(result("g", 1.0)));
        when(vector.retrieve(anyString(), eq("good"), eq(30))).thenReturn(List.of());
        when(reranker.rerank(anyString(), anyList(), eq(8))).thenAnswer(inv -> inv.getArgument(1));

        List<RetrievalResult> out = service.retrieveMulti("n1", List.of("bad", "good"));

        assertThat(out).extracting(RetrievalResult::chunkId).containsExactly("g");
    }

    @Test
    void allQueriesEmptyReturnsEmpty() {
        when(bm25.retrieve(anyString(), anyString(), eq(30))).thenReturn(List.of());
        when(vector.retrieve(anyString(), anyString(), eq(30))).thenReturn(List.of());

        assertThat(service.retrieveMulti("n1", List.of("q1", "q2"))).isEmpty();
    }

    @Test
    void respectsFusionTopK() {
        seed(result("a", 1.0), null);
        service = new HybridRetrievalService(bm25, vector, reranker,
                new RetrievalProperties(30, 30, 2, 8, 60, "passthrough", 15, 200));
        when(bm25.retrieve(anyString(), anyString(), eq(30)))
                .thenReturn(List.of(result("a", 1.0), result("b", 0.9), result("c", 0.8)));
        when(reranker.rerank(anyString(), anyList(), eq(8))).thenAnswer(inv -> inv.getArgument(1));

        List<RetrievalResult> out = service.retrieveMulti("n1", List.of("q1", "q2", "q3"));

        assertThat(out).hasSizeLessThanOrEqualTo(2);
    }
}
