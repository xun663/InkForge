package com.inkforge.benchmark;

import com.inkforge.retrieval.MemoryChunkType;
import com.inkforge.retrieval.QueryIntent;
import com.inkforge.retrieval.RetrievalResult;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P5-B3-1：纯诊断工具的离线单测（§十八）——rank delta、CANDIDATE_MISS / HELP / HURT 分类、
 * per-intent 聚合、gold 分层 trace、metrics 确定性。零 LLM。
 */
class RerankDiagnosticsTest {

    private static RetrievalResult res(int chapter) {
        return new RetrievalResult("c" + chapter, "n1", chapter - 1, MemoryChunkType.EVENT,
                "src:" + chapter, "第" + chapter + "章 片段。", 1.0 - chapter / 100.0);
    }

    private static List<RetrievalResult> ranked(int... chapters) {
        java.util.ArrayList<RetrievalResult> l = new java.util.ArrayList<>();
        for (int c : chapters) l.add(res(c));
        return l;
    }

    // ---- 1. rank delta 计算：正 = 输入更靠后、输出更靠前 = 改善 ----
    @Test
    void rankDeltaPositiveWhenRerankerMovesUp() {
        assertThat(RerankDiagnostics.rankDelta(20, 5)).isEqualTo(15);
        assertThat(RerankDiagnostics.rankDelta(4, 4)).isZero();
        assertThat(RerankDiagnostics.rankDelta(3, 7)).isEqualTo(-4);
        assertThat(RerankDiagnostics.rankDelta(-1, 5)).isZero(); // 输入未命中
    }

    // ---- 2. Candidate Miss 分类：没进 fusion 不怪 reranker ----
    @Test
    void candidateMissClassifiedWhenNotInFusion() {
        RerankDiagnostics.GoldTrace t = new RerankDiagnostics.GoldTrace(
                "B1", QueryIntent.RELATIONSHIP, 25,
                7, -1, -1, -1, -1, false);
        assertThat(t.classify()).isEqualTo(RerankDiagnostics.LayerResult.CANDIDATE_MISS);
    }

    // ---- 3. Reranker Help / Hurt 分类 ----
    @Test
    void helpHurtClassification() {
        RerankDiagnostics.GoldTrace helped = new RerankDiagnostics.GoldTrace(
                "C1", QueryIntent.FORESHADOWING, 47, 3, -1, 12, 12, 3, false);
        assertThat(helped.classify()).isEqualTo(RerankDiagnostics.LayerResult.RERANKER_HELPED);
        assertThat(helped.rankDelta()).isEqualTo(9);

        RerankDiagnostics.GoldTrace hurt = new RerankDiagnostics.GoldTrace(
                "B1", QueryIntent.RELATIONSHIP, 10, 1, 20, 4, 4, 9, true);
        assertThat(hurt.classify()).isEqualTo(RerankDiagnostics.LayerResult.RERANKER_HURT);

        RerankDiagnostics.GoldTrace neutral = new RerankDiagnostics.GoldTrace(
                "B1", QueryIntent.RELATIONSHIP, 48, -1, 2, 6, 6, 6, true);
        assertThat(neutral.classify()).isEqualTo(RerankDiagnostics.LayerResult.RERANKER_NEUTRAL);
    }

    @Test
    void verdictFromTracesMajority() {
        RerankDiagnostics.GoldTrace h1 = new RerankDiagnostics.GoldTrace("q", QueryIntent.RELATIONSHIP, 1, -1, -1, 5, 5, 2, false);
        RerankDiagnostics.GoldTrace h2 = new RerankDiagnostics.GoldTrace("q", QueryIntent.RELATIONSHIP, 2, -1, -1, 8, 8, 4, false);
        RerankDiagnostics.GoldTrace ht = new RerankDiagnostics.GoldTrace("q", QueryIntent.RELATIONSHIP, 3, -1, -1, 4, 4, 9, false);
        assertThat(RerankDiagnostics.verdict(List.of(h1, h2, ht))).isEqualTo(RerankDiagnostics.RerankerVerdict.HELP);
    }

    // ---- 4. metrics：recall/mrr/ndcg 与 Gold@5/10/15 ----
    @Test
    void metricsOnOrderedList() {
        // gold 章节 {3,7,15}；列表前 3 是 1,2,3（第3章在第3位命中）
        RerankDiagnostics.Metrics m = RerankDiagnostics.metrics(ranked(1, 2, 3, 4, 7, 5, 6, 15, 8), List.of(3, 7, 15));
        assertThat(m.goldCovered()).isEqualTo(3);
        assertThat(m.goldTotal()).isEqualTo(3);
        assertThat(m.recall()).isEqualTo(1.0);
        assertThat(m.mrr()).isEqualTo(1.0 / 3);
        assertThat(m.top5()).isEqualTo(2);   // 第3章@3、第7章@5 都在 top5
        assertThat(m.top10()).isEqualTo(3);  // 第15章@8 也在 top10
        assertThat(m.top15()).isEqualTo(3);
    }

    @Test
    void missingGoldYieldsZeroRecall() {
        RerankDiagnostics.Metrics m = RerankDiagnostics.metrics(ranked(1, 2, 3), List.of(40, 41));
        assertThat(m.goldCovered()).isZero();
        assertThat(m.recall()).isZero();
        assertThat(m.mrr()).isZero();
    }

    // ---- 5. gold 分层 trace 字段完整、可读 ----
    @Test
    void goldTraceFields() {
        RerankDiagnostics.GoldTrace t = new RerankDiagnostics.GoldTrace(
                "B1", QueryIntent.RELATIONSHIP, 25, 7, -1, 8, 8, 3, true);
        assertThat(t.query()).isEqualTo("B1");
        assertThat(t.intent()).isEqualTo(QueryIntent.RELATIONSHIP);
        assertThat(t.bm25Rank()).isEqualTo(7);
        assertThat(t.vectorRank()).isEqualTo(RerankDiagnostics.MISS);
        assertThat(t.fusionRank()).isEqualTo(8);
        assertThat(t.rerankOutputRank()).isEqualTo(3);
        assertThat(t.contextHit()).isTrue();
    }

    // ---- 6. per-intent 聚合 ----
    @Test
    void perIntentAggregationGroupsMeans() {
        Map<String, RerankDiagnostics.Metrics> qm = new LinkedHashMap<>();
        qm.put("B1", RerankDiagnostics.metrics(ranked(3, 25), List.of(25))); // recall 1, mrr 1/2
        qm.put("B2", RerankDiagnostics.metrics(ranked(3, 4, 19), List.of(19))); // recall 1, mrr 1/3
        Map<String, QueryIntent> qi = new LinkedHashMap<>();
        qi.put("B1", QueryIntent.RELATIONSHIP);
        qi.put("B2", QueryIntent.RELATIONSHIP);
        Map<QueryIntent, double[]> out = RerankDiagnostics.perIntent(List.of(), qm, qi);
        double[] rel = out.get(QueryIntent.RELATIONSHIP);
        assertThat(rel[3]).isEqualTo(2.0);                    // count
        assertThat(rel[0]).isEqualTo(1.0);                    // mean recall
        assertThat(rel[1]).isEqualTo((0.5 + 1.0 / 3) / 2);    // mean mrr
    }

    // ---- 7. 确定性：同一输入两次结果一致 ----
    @Test
    void deterministic() {
        RerankDiagnostics.Metrics a = RerankDiagnostics.metrics(ranked(1, 2, 3, 4, 5, 6), List.of(6, 2));
        RerankDiagnostics.Metrics b = RerankDiagnostics.metrics(ranked(1, 2, 3, 4, 5, 6), List.of(6, 2));
        assertThat(a).isEqualTo(b);
    }
}
