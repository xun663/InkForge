package com.inkforge.benchmark;

import com.inkforge.retrieval.QueryIntent;
import com.inkforge.retrieval.RetrievalResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * P5-B3-1 纯诊断工具（test-only）：Gold 分层追踪的分类/统计与 MRR/NDCG/Gold@K。
 * 只做标量与归类，不涉及任何 LLM/生产代码——保证可单测、可复现。
 */
public final class RerankDiagnostics {

    private RerankDiagnostics() {
    }

    /** Gold 在该层未命中。 */
    public static final int MISS = -1;

    public enum LayerResult {
        CANDIDATE_MISS,     // 未进 Fusion top-30（BM25/Vector/RRF 层漏）
        RANKING_MISS,       // 进 Fusion，但 rerank 后仍靠后/未进有效 Top
        RERANKER_HELPED,    // rerank 把它排得比输入更靠前
        RERANKER_HURT,      // rerank 把它排得比输入更靠后
        RERANKER_NEUTRAL    // rerank 未改变它的名次
    }

    public enum RerankerVerdict { HELP, NEUTRAL, HURT }

    /** 单条 gold 的分层记录（rank 均 1-based；MISS=-1）。 */
    public record GoldTrace(String query, QueryIntent intent, int goldChapter,
                            int bm25Rank, int vectorRank, int fusionRank,
                            int rerankInputRank, int rerankOutputRank, boolean contextHit) {

        public boolean inFusion() {
            return fusionRank >= 0;
        }

        public boolean inRerankInput() {
            return rerankInputRank >= 0;
        }

        public int rankDelta() {
            // 正 = 排名改善（输入更靠后，输出更靠前）
            if (rerankInputRank < 0 || rerankOutputRank < 0) {
                return 0;
            }
            return rerankInputRank - rerankOutputRank;
        }

        public LayerResult classify() {
            if (!inFusion()) {
                return LayerResult.CANDIDATE_MISS;
            }
            if (!inRerankInput()) {
                return LayerResult.RANKING_MISS; // 进了 fusion 但没进 reranker 输入
            }
            if (rerankOutputRank < 0) {
                return LayerResult.RANKING_MISS; // 输入了但输出丢了
            }
            int d = rankDelta();
            if (d > 0) return LayerResult.RERANKER_HELPED;
            if (d < 0) return LayerResult.RERANKER_HURT;
            return LayerResult.RERANKER_NEUTRAL;
        }
    }

    /** 两条策略（Passthrough vs Reranker）某条 gold 的名次与排名差。 */
    public static int rankDelta(int passthroughRank, int rerankerRank) {
        if (passthroughRank < 0 || rerankerRank < 0) return 0;
        return passthroughRank - rerankerRank;
    }

    /** 对一组 gold 的分层结果归总成 PassThrough-vs-Reranker verdict（按各 gold 排名差符号多数）。 */
    public static RerankerVerdict verdict(List<GoldTrace> traces) {
        int help = 0, hurt = 0;
        for (GoldTrace t : traces) {
            if (t.inRerankInput() && t.rerankOutputRank >= 0) {
                int d = t.rankDelta();
                if (d > 0) help++;
                else if (d < 0) hurt++;
            }
        }
        if (help > hurt) return RerankerVerdict.HELP;
        if (hurt > help) return RerankerVerdict.HURT;
        return RerankerVerdict.NEUTRAL;
    }

    /** MRR / NDCG / Gold@top 在一串有序结果上（章节口径、按出现顺序去重）。 */
    public record Metrics(double recall, double mrr, double ndcg, int goldCovered, int goldTotal,
                          int top5, int top10, int top15) {
    }

    public static Metrics metrics(List<RetrievalResult> ranked, List<Integer> goldChapters) {
        Set<Integer> gold = Set.copyOf(goldChapters);
        int n = ranked.size();
        int covered = 0, first = -1, hit5 = 0, hit10 = 0, hit15 = 0;
        double dcg = 0;
        Set<Integer> seen = new java.util.HashSet<>();
        for (int i = 0; i < n; i++) {
            int ch = ranked.get(i).chapterOrdinal() + 1;
            if (gold.contains(ch) && seen.add(ch)) {
                covered++;
                if (first < 0) first = i + 1;
                if (i < 5) hit5++;
                if (i < 10) hit10++;
                if (i < 15) hit15++;
                dcg += 1.0 / Math.log(i + 2);
            }
        }
        double idcg = 0;
        for (int j = 0; j < Math.min(gold.size(), n); j++) idcg += 1.0 / Math.log(j + 2);
        return new Metrics(
                gold.isEmpty() ? 0 : (double) covered / gold.size(),
                first > 0 ? 1.0 / first : 0,
                idcg > 0 ? dcg / idcg : 0,
                covered, gold.size(), hit5, hit10, hit15);
    }

    /** 按 QueryIntent 分组平均 MRR/NDCG/recall。 */
    public static Map<QueryIntent, double[]> perIntent(List<GoldTrace> traces,
                                                       Map<String, Metrics> queryMetrics,
                                                       Map<String, QueryIntent> queryIntent) {
        Map<QueryIntent, Accum> acc = new LinkedHashMap<>();
        for (Map.Entry<String, QueryIntent> e : queryIntent.entrySet()) {
            Metrics m = queryMetrics.get(e.getKey());
            if (m == null) continue;
            acc.computeIfAbsent(e.getValue(), k -> new Accum()).add(m);
        }
        Map<QueryIntent, double[]> out = new LinkedHashMap<>();
        acc.forEach((k, a) -> out.put(k, new double[]{a.recall(), a.mrr(), a.ndcg(), a.count()}));
        return out;
    }

    private static final class Accum {
        private double recall, mrr, ndcg;
        private int count;

        void add(Metrics m) {
            recall += m.recall();
            mrr += m.mrr();
            ndcg += m.ndcg();
            count++;
        }

        double recall() { return count == 0 ? 0 : recall / count; }
        double mrr() { return count == 0 ? 0 : mrr / count; }
        double ndcg() { return count == 0 ? 0 : ndcg / count; }
        double count() { return count; }
    }
}
