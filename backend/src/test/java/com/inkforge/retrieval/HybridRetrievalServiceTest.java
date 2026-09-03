package com.inkforge.retrieval;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Hybrid pipeline degradation rules 1-8: any retrieval failure degrades, never throws,
 * never fabricates results.
 */
class HybridRetrievalServiceTest {

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

    private static RetrievalResult result(String chunkId) {
        return new RetrievalResult(chunkId, "n1", 1, MemoryChunkType.EVENT, "src:" + chunkId, "文本", 1.0);
    }

    private void rerankerReturnsFusionOrder() {
        when(reranker.rerank(anyString(), anyList(), eq(8))).thenAnswer(inv -> inv.getArgument(1));
    }

    @Test
    void bothRetrieversFuseAndRerank() {
        when(bm25.retrieve("n1", "q", 30)).thenReturn(List.of(result("b"), result("a")));
        when(vector.retrieve("n1", "q", 30)).thenReturn(List.of(result("a"), result("c")));
        when(reranker.rerank(anyString(), anyList(), eq(8))).thenReturn(List.of(result("a"), result("c")));

        List<RetrievalResult> out = service.retrieve("n1", "q");

        assertThat(out).extracting(RetrievalResult::chunkId).containsExactly("a", "c");
        verify(reranker).rerank(anyString(), anyList(), eq(8));
    }

    @Test
    void bm25FailsFallsBackToVectorOnly() {
        when(bm25.retrieve("n1", "q", 30)).thenThrow(new IllegalStateException("BM25 挂了"));
        when(vector.retrieve("n1", "q", 30)).thenReturn(List.of(result("v1"), result("v2")));
        rerankerReturnsFusionOrder();

        List<RetrievalResult> out = service.retrieve("n1", "q");

        assertThat(out).extracting(RetrievalResult::chunkId).containsExactly("v1", "v2");
    }

    @Test
    void vectorFailsFallsBackToBm25Only() {
        when(bm25.retrieve("n1", "q", 30)).thenReturn(List.of(result("b1"), result("b2")));
        when(vector.retrieve("n1", "q", 30)).thenThrow(new IllegalStateException("Vector 挂了"));
        rerankerReturnsFusionOrder();

        List<RetrievalResult> out = service.retrieve("n1", "q");

        assertThat(out).extracting(RetrievalResult::chunkId).containsExactly("b1", "b2");
    }

    @Test
    void bothFailReturnsEmptyWithoutThrowing() {
        when(bm25.retrieve("n1", "q", 30)).thenThrow(new IllegalStateException("x"));
        when(vector.retrieve("n1", "q", 30)).thenThrow(new IllegalStateException("y"));

        assertThat(service.retrieve("n1", "q")).isEmpty();
        verify(reranker, never()).rerank(anyString(), anyList(), eq(8));
    }

    @Test
    void rerankerFailureFallsBackToFusionRanking() {
        when(bm25.retrieve("n1", "q", 30)).thenReturn(List.of(result("a"), result("b")));
        when(vector.retrieve("n1", "q", 30)).thenReturn(List.of());
        when(reranker.rerank(anyString(), anyList(), eq(8)))
                .thenThrow(new RerankException("LLM 重排失败"));

        List<RetrievalResult> out = service.retrieve("n1", "q");

        // 回退 = fusion 顺序 + PassThrough topK
        assertThat(out).extracting(RetrievalResult::chunkId).containsExactly("a", "b");
    }

    @Test
    void emptyCandidatesNeverCallReranker() {
        when(bm25.retrieve("n1", "q", 30)).thenReturn(List.of());
        when(vector.retrieve("n1", "q", 30)).thenReturn(List.of());

        assertThat(service.retrieve("n1", "q")).isEmpty();
        verify(reranker, never()).rerank(anyString(), anyList(), eq(8));
    }

    @Test
    void rerankTopKIsEnforced() {
        when(bm25.retrieve("n1", "q", 30)).thenReturn(List.of(result("a"), result("b"), result("c")));
        when(vector.retrieve("n1", "q", 30)).thenReturn(List.of());
        rerankerReturnsFusionOrder();

        List<RetrievalResult> out = service.retrieve("n1", "q");

        assertThat(out).hasSizeLessThanOrEqualTo(8);
    }

    @Test
    void nullQueryReturnsEmpty() {
        assertThat(service.retrieve("n1", null)).isEmpty();
        assertThat(service.retrieve("n1", "  ")).isEmpty();
    }
}
