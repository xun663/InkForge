package com.inkforge.benchmark;

import com.inkforge.benchmark.BenchmarkQueries.Gold;
import com.inkforge.retrieval.RetrievalResult;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 检索评估指标（P3-G）。口径：
 * <ul>
 *   <li><b>chunk 命中</b>：result.memoryType == gold.memoryType 且 chapterOrdinal 匹配</li>
 *   <li><b>chapter 命中</b>：chapterOrdinal 匹配（不看类型）</li>
 * </ul>
 * 每条 query 的 gold 是集合；命中计数按"去重 gold 数"计（一个结果最多命中一个 gold）。
 */
public final class Metrics {

    private Metrics() {
    }

    public record Result(double recall5, double recall10, double mrr10, double ndcg10, double useful8) {

        public static Result empty() {
            return new Result(0, 0, 0, 0, 0);
        }
    }

    /** 计算单条 query 的指标（chapter 口径；chunk 口径调用同法、传入对应命中判定）。 */
    public static Result compute(List<RetrievalResult> results, List<Gold> golds, int helpfulness,
                                 boolean chunkStrict) {
        if (golds.isEmpty()) {
            return Result.empty();
        }
        // 去重 gold（同一 (chapter, type) 只计一次）
        List<Gold> uniqueGolds = golds.stream()
                .distinct()
                .toList();

        // 命中按 gold 去重计数（同一 gold 被多个结果命中只计一次）；统计 top-5/top-8/top-10
        int firstHitRank = -1;
        int hits5 = 0;
        int hits8 = 0;
        int hits10 = 0;
        double dcg = 0;
        Set<Gold> matched = new HashSet<>();
        for (int i = 0; i < Math.min(results.size(), 10); i++) {
            RetrievalResult r = results.get(i);
            Gold hit = uniqueGolds.stream()
                    .filter(gold -> matches(r, gold, chunkStrict))
                    .findFirst()
                    .orElse(null);
            if (hit != null && matched.add(hit)) {
                if (firstHitRank < 0) {
                    firstHitRank = i + 1;
                }
                if (i < 5) {
                    hits5++;
                }
                if (i < 8) {
                    hits8++;
                }
                hits10++;
                dcg += 1.0 / Math.log(i + 2);
            }
        }
        double idcg = 0;
        for (int i = 0; i < Math.min(uniqueGolds.size(), 10); i++) {
            idcg += 1.0 / Math.log(i + 2);
        }

        int uniqueCount = uniqueGolds.size();
        double recall5 = (double) hits5 / uniqueCount;
        double recall10 = (double) hits10 / uniqueCount;
        double mrr10 = firstHitRank > 0 ? 1.0 / firstHitRank : 0;
        double ndcg10 = idcg > 0 ? dcg / idcg : 0;
        // Useful@8：top-8 内命中 gold 数 / min(gold 数, 8)——"有用记忆的即时覆盖率"
        double useful8 = (double) hits8 / Math.max(1, Math.min(uniqueCount, 8));
        return new Result(recall5, recall10, mrr10, ndcg10, useful8);
    }

    private static boolean matches(RetrievalResult result, Gold gold, boolean chunkStrict) {
        if (result.chapterOrdinal() != gold.chapterOrdinal()) {
            return false;
        }
        return !chunkStrict || result.memoryType() == gold.memoryType();
    }

    /** 汇总：N 条 query 指标平均。 */
    public static Result average(List<Result> results) {
        if (results.isEmpty()) {
            return Result.empty();
        }
        double r5 = results.stream().mapToDouble(Result::recall5).average().orElse(0);
        double r10 = results.stream().mapToDouble(Result::recall10).average().orElse(0);
        double mrr = results.stream().mapToDouble(Result::mrr10).average().orElse(0);
        double ndcg = results.stream().mapToDouble(Result::ndcg10).average().orElse(0);
        double useful = results.stream().mapToDouble(Result::useful8).average().orElse(0);
        return new Result(r5, r10, mrr, ndcg, useful);
    }
}
