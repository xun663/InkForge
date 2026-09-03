package com.inkforge.benchmark;

import com.inkforge.chapter.Chapter;
import com.inkforge.chapter.ChapterSplitter;
import com.inkforge.chapter.CharsetDetector;
import com.inkforge.chapter.ParsedNovel;
import com.inkforge.chapter.TxtNovelParser;
import com.inkforge.common.JtokkitTokenCounter;
import com.inkforge.common.TokenCounter;
import com.inkforge.common.prompt.ClasspathPromptCatalog;
import com.inkforge.common.prompt.PromptCatalog;
import com.inkforge.memory.InMemoryStoryMemoryRepository;
import com.inkforge.memory.MemoryUpdateService;
import com.inkforge.memory.StoryMemoryRepository;
import com.inkforge.memory.extraction.ExtractionValidator;
import com.inkforge.memory.extraction.MemoryExtractionProperties;
import com.inkforge.memory.extraction.MemoryExtractor;
import com.inkforge.novel.Novel;
import com.inkforge.provider.EmbeddingProperties;
import com.inkforge.provider.LlmProperties;
import com.inkforge.provider.LlmProvider;
import com.inkforge.provider.OpenAiCompatibleEmbeddingProvider;
import com.inkforge.provider.OpenAiCompatibleLlmProvider;
import com.inkforge.retrieval.HybridRetrievalService;
import com.inkforge.retrieval.InMemoryChunkEmbeddingStore;
import com.inkforge.retrieval.InMemoryMemoryChunkRepository;
import com.inkforge.retrieval.InMemoryVectorRetriever;
import com.inkforge.retrieval.LuceneBm25Retriever;
import com.inkforge.retrieval.MemoryChunkProjectionService;
import com.inkforge.retrieval.MemoryChunkRepository;
import com.inkforge.retrieval.MemoryEmbeddingService;
import com.inkforge.retrieval.PassThroughReranker;
import com.inkforge.retrieval.RetrievalProperties;
import com.inkforge.retrieval.RetrievalResult;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * P5-B2-2.5 Final Top-K 终选 A/B：8 / 15 / 30。
 *
 * <p>单变量实验，固定项与 P5-B2-0/1、P5-B2-2 完全一致：遮天 ch1-48（coverage=48 真实 deepseek
 * 提取）、同一 10 条定向 Query 与 gold、BGE-M3、BM25/Vector top-30、RRF k=60、fusion top-30、
 * QueryBuilder/QueryIntent/Memory/Context/Prompt/LLM 全冻结。唯一变量 = Final/Rerank Top-K。
 *
 * <p><b>可靠性关键</b>：8/15/30 三种 K 全部在<b>同一次 Memory Extraction</b>上跑（一次 48 章提取，
 * 三个 rerankTopK 实例共用同一 chunk/embedding 池），绝不为切换 K 重跑提取；因此跨 K 的 Δ 无
 * deepseek 非确定性干扰。
 *
 * <p>指标口径：gold=章节级。Recall@K = Evidence Coverage@K = 最终 Context（top-K chunk）中命中的
 * <b>distinct gold 章节</b>数 / gold 总数（两者同值）；Gold Chapter Hit = distinct 命中数；MRR@K /
 * NDCG@K = 对最终 top-K 列表按章节去重的排名指标（DCG 用 1/log(i+2)，与 Metrics 一致）；
 * Context Noise = 最终 Context 中 chapter ∉ gold 的 chunk 数（chunk 级占位）；
 * Gold Ratio = gold chunk 数 / 最终槽位数（chunk 级证据密度）。
 *
 * <p>输出 target/e2e/retrieval-topk-final/final-topk-ab.md（B2-2.5 结果，覆盖 B2-2 该目录旧值；
 * 文档级报告见 docs/p5-b2-final-topk.md）。不改任何冻结生产代码。
 */
class RetrievalTopKFinalAblation {

    private static final String BGE_BASE = "http://localhost:8085/v1";
    private static final String NOVEL_FILE = "C:/Users/xun/AppData/Local/Temp/inkforge-e2e/zetian_ch1-48.txt";

    private static final int[] KS = {8, 15, 30};

    @Test
    void finalTopKAb() throws Exception {
        Path out = Path.of("target/e2e/retrieval-topk-final");
        Files.createDirectories(out);

        String apiKey = System.getenv("INKFORGE_LLM_API_KEY");
        if (apiKey == null || apiKey.isBlank()) apiKey = System.getenv("LLM_API_KEY");
        if (apiKey == null || apiKey.isBlank()) throw new IllegalStateException("no deepseek key");
        String model = System.getenv().getOrDefault("INKFORGE_LLM_MODEL", "deepseek-v4-flash");
        String baseUrl = System.getenv().getOrDefault("INKFORGE_LLM_BASE_URL", "https://api.deepseek.com");

        ObjectMapper om = new ObjectMapper();
        TokenCounter tc = new JtokkitTokenCounter();
        PromptCatalog catalog = new ClasspathPromptCatalog();
        MemoryExtractionProperties props = new MemoryExtractionProperties(3, 12000, 2048, 4096, 0.2, 2, 0.7, 300, 200);
        EmbeddingProperties bgeProps = new EmbeddingProperties("openai-compatible", "BAAI/bge-m3", BGE_BASE, "local", 1024, 16, 120);
        LlmProvider deepseek = new OpenAiCompatibleLlmProvider("deepseek",
                WebClient.builder().baseUrl(baseUrl).build(),
                new LlmProperties("deepseek", baseUrl, apiKey, model, 300, new LlmProperties.Mock(0)), om);

        // coverage=48 记忆：一次真实 deepseek 提取，三个 K 共用（可靠性关键）
        ParsedNovel parsed = new TxtNovelParser(new CharsetDetector(), new ChapterSplitter()).parse(
                Files.readAllBytes(Path.of(NOVEL_FILE)), "zetian_ch1-48.txt");
        List<Chapter> chapters = parsed.chapters();
        if (!chapters.isEmpty() && chapters.get(0).chapterNo() == null) chapters = chapters.subList(1, chapters.size());
        Novel novel = new Novel("zetian-48", parsed.title(), "zetian_ch1-48.txt", chapters);

        StoryMemoryRepository memRepo = new InMemoryStoryMemoryRepository();
        MemoryChunkRepository chunkRepo = new InMemoryMemoryChunkRepository();
        MemoryExtractor extractor = new MemoryExtractor(deepseek, catalog, tc, new ExtractionValidator(), props, om);
        MemoryUpdateService update = new MemoryUpdateService(memRepo, props);
        MemoryChunkProjectionService projection = new MemoryChunkProjectionService(memRepo, chunkRepo);
        int ok = 0;
        for (Chapter ch : chapters) {
            var outcome = extractor.extract(ch, ch.chapterNo() != null ? "第" + ch.chapterNo() + "章" : ch.title());
            if (outcome.result() != null) { update.apply(novel.id(), ch, outcome.result()); projection.projectChapter(novel.id(), ch.ordinal()); ok++; }
        }
        System.out.println("coverage=48 memory: " + ok + "/" + chapters.size());

        OpenAiCompatibleEmbeddingProvider bge = new OpenAiCompatibleEmbeddingProvider("openai-compatible",
                WebClient.builder().baseUrl(BGE_BASE).build(), bgeProps, om);
        InMemoryChunkEmbeddingStore store = new InMemoryChunkEmbeddingStore();
        new MemoryEmbeddingService(bge, chunkRepo, store, bgeProps).embedNovel(novel.id());

        LuceneBm25Retriever bm25 = new LuceneBm25Retriever(chunkRepo);
        InMemoryVectorRetriever vec = new InMemoryVectorRetriever(bge, chunkRepo, store, bgeProps);

        // 三个 K 的生产路径实例（仅 rerankTopK 不同）
        Map<Integer, HybridRetrievalService> hy = new HashMap<>();
        for (int k : KS) {
            hy.put(k, new HybridRetrievalService(bm25, vec, new PassThroughReranker(),
                    new RetrievalProperties(30, 30, 30, k, 60, "passthrough", 15, 200)));
        }

        StringBuilder sb = new StringBuilder("# P5-B2-2.5 Final Top-K 终选 A/B（8 / 15 / 30，coverage=48，10 条定向 Query）\n\n");
        sb.append("生产路径：BM25(top-30) + Vector(top-30) → RRF(k=60, fusion top-30) → PassThrough(rerankTopK)。\n");
        sb.append("三 K 共用同一次 Memory Extraction（无 deepseek 非确定性干扰）。gold=章节级；Recall=EvCov=distinct "
                + "gold 章节命中/gold；MRR/NDCG 按最终 top-K 列表章节去重；Noise=非 gold 章节的 chunk 占位。\n\n");

        // 主表：每 query 每 K 的 EvCov + Noise
        sb.append("| Q | gold | fusion∋(ceiling) | EvCov@8 | EvCov@15 | EvCov@30 | 噪声@8 | 噪声@15 | 噪声@30 |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|\n");

        List<Row> rows = new ArrayList<>();
        int[] tGoldHit = new int[KS.length], tNoise = new int[KS.length], tSlots = new int[KS.length];
        double[] tRecall = new double[KS.length], tMrr = new double[KS.length], tNdcg = new double[KS.length];
        int qCount = 0;

        for (var q : RetrievalTopKAblation.QUERIES) {
            List<Integer> goldList = gold(q);
            int g = goldList.size();

            List<RetrievalResult> fusion = hy.get(30).retrieveTraced(novel.id(), q.text()).stages().get("fusion");
            KStat[] st = new KStat[KS.length];
            for (int i = 0; i < KS.length; i++) {
                List<RetrievalResult> fin = hy.get(KS[i]).retrieveTraced(novel.id(), q.text()).finalResults();
                st[i] = stat(fin, goldList, KS[i]);
                tGoldHit[i] += st[i].cov; tNoise[i] += st[i].noise; tSlots[i] += st[i].size;
                tRecall[i] += st[i].recall; tMrr[i] += st[i].mrr; tNdcg[i] += st[i].ndcg;
            }
            int inFusion = stat(fusion, goldList, 30).cov;
            qCount++;
            rows.add(new Row(q.id(), q.category(), goldList, fusion, st, inFusion));

            sb.append(String.format("| %s | %d | %d/%d | %d/%d | %d/%d | %d/%d | %d | %d | %d |%n",
                    q.id(), g, inFusion, g, st[0].cov, g, st[1].cov, g, st[2].cov, g, st[0].noise, st[1].noise, st[2].noise));
        }

        sb.append("\n## 汇总（10 条 Query；gold 总数 38，按 query 去重）\n\n");
        sb.append("| 指标 | @8 | @15 | @30 |\n|---|---|---|---|\n");
        sb.append(String.format("| 平均 Recall / Evidence Coverage（章节口径） | %.3f | %.3f | %.3f |%n",
                tRecall[0] / qCount, tRecall[1] / qCount, tRecall[2] / qCount));
        sb.append(String.format("| Gold Chapter Hit 合计（distinct，跨 query） | %d/38 (%.3f) | %d/38 (%.3f) | %d/38 (%.3f) |%n",
                tGoldHit[0], tGoldHit[0] / 38.0, tGoldHit[1], tGoldHit[1] / 38.0, tGoldHit[2], tGoldHit[2] / 38.0));
        sb.append(String.format("| 平均 MRR@K | %.3f | %.3f | %.3f |%n", tMrr[0] / qCount, tMrr[1] / qCount, tMrr[2] / qCount));
        sb.append(String.format("| 平均 NDCG@K | %.3f | %.3f | %.3f |%n", tNdcg[0] / qCount, tNdcg[1] / qCount, tNdcg[2] / qCount));
        sb.append(String.format("| Noise chunk 合计 | %d | %d | %d |%n", tNoise[0], tNoise[1], tNoise[2]));
        sb.append(String.format("| Context 槽位合计（=Σmin(K, fusion 长)） | %d | %d | %d |%n", tSlots[0], tSlots[1], tSlots[2]));
        sb.append(String.format("| Gold chunk 合计（chunk 级，含同章多 chunk） | %d | %d | %d |%n",
                tSlots[0] - tNoise[0], tSlots[1] - tNoise[1], tSlots[2] - tNoise[2]));
        sb.append(String.format("| Gold Ratio（gold chunk/槽位，chunk 级证据密度） | %.1f%% | %.1f%% | %.1f%% |%n",
                (tSlots[0] - tNoise[0]) / (double) tSlots[0] * 100,
                (tSlots[1] - tNoise[1]) / (double) tSlots[1] * 100,
                (tSlots[2] - tNoise[2]) / (double) tSlots[2] * 100));

        sb.append(b1c1c2(rows));
        sb.append(perQueryRank(rows));
        sb.append(tradeoff(rows));

        Files.writeString(out.resolve("final-topk-ab.md"), sb.toString());
        System.out.println(sb);
        System.out.println("done → " + out.resolve("final-topk-ab.md"));
    }

    private static List<Integer> gold(RetrievalTopKAblation.DQ q) {
        List<Integer> g = new ArrayList<>();
        for (int x : q.gold()) g.add(x);
        return g;
    }

    /** 对最终 top-K 列表做章节级统计：EvCov/Recall、Gold Chapter Hit、MRR、NDCG、Noise。 */
    private static KStat stat(List<RetrievalResult> finalResults, List<Integer> goldList, int k) {
        Set<Integer> goldSet = Set.copyOf(goldList);
        int size = Math.min(k, finalResults.size());
        int cov = 0, goldChunks = 0, first = -1;
        double dcg = 0;
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < size; i++) {
            int ch = finalResults.get(i).chapterOrdinal() + 1;
            if (goldSet.contains(ch)) {
                goldChunks++;
                if (seen.add(ch)) {
                    cov++;
                    if (first < 0) first = i + 1;
                    dcg += 1.0 / Math.log(i + 2);   // 与 Metrics 一致
                }
            }
        }
        int noise = size - goldChunks;
        int g = goldList.size();
        double recall = g == 0 ? 0 : (double) cov / g;
        double mrr = first > 0 ? 1.0 / first : 0;
        double idcg = 0;
        for (int j = 0; j < Math.min(g, k); j++) idcg += 1.0 / Math.log(j + 2);
        double ndcg = idcg > 0 ? dcg / idcg : 0;
        return new KStat(cov, recall, noise, size - noise, size, mrr, ndcg);
    }

    private static String b1c1c2(List<Row> rows) {
        StringBuilder sb = new StringBuilder("\n## B1 / C1 / C2 明细（关系 / 伏笔定向）\n\n");
        for (Row r : rows) {
            if (!Set.of("B1", "C1", "C2").contains(r.id)) continue;
            sb.append("### ").append(r.id).append(" ").append(r.category).append(" — ").append(r.gold.size())
                    .append(" gold；fusion∋=").append(r.inFusion).append("/").append(r.gold.size())
                    .append("；EvCov@8/15/30 = ").append(r.st[0].cov).append("/").append(r.st[1].cov)
                    .append("/").append(r.st[2].cov).append("\n");
            Map<Integer, Integer> fr = fusionRanks(r.fusion, r.gold);
            for (int g : r.gold) {
                int rank = fr.getOrDefault(g, -1);   // gold 章节首次出现的 chunk rank（1-based）
                sb.append(String.format("- gold ch%d: fusion rank=%s → @8=%s / @15=%s / @30=%s%n",
                        g, rank < 0 ? "∅" : String.valueOf(rank),
                        rank >= 0 && rank <= 8 ? "✓" : "✗",
                        rank >= 0 && rank <= 15 ? "✓" : "✗",
                        rank >= 0 && rank <= 30 ? "✓" : "✗"));
            }
        }
        return sb.toString();
    }

    private static String perQueryRank(List<Row> rows) {
        StringBuilder sb = new StringBuilder("\n## 逐 Query MRR / NDCG（按各 K 最终列表）\n\n");
        sb.append("| Q | MRR@8 | MRR@15 | MRR@30 | NDCG@8 | NDCG@15 | NDCG@30 |\n");
        sb.append("|---|---|---|---|---|---|---|\n");
        for (Row r : rows) {
            sb.append(String.format("| %s | %.3f | %.3f | %.3f | %.3f | %.3f | %.3f |%n",
                    r.id, r.st[0].mrr, r.st[1].mrr, r.st[2].mrr, r.st[0].ndcg, r.st[1].ndcg, r.st[2].ndcg));
        }
        return sb.toString();
    }

    private static String tradeoff(List<Row> rows) {
        StringBuilder sb = new StringBuilder("\n## 扩窗代价（8→15、15→30 新增槽位中 gold 占比）\n\n");
        sb.append("注：最终@K = fusion 前 K 个 chunk（PassThrough 语义）；区间按 fusion 秩切片。\n\n");
        for (int seg = 1; seg < KS.length; seg++) {
            int lo = KS[seg - 1], hi = KS[seg];
            int newGoldChunk = 0, newNoise = 0, newChapter = 0;
            for (Row r : rows) {
                Set<Integer> goldSet = Set.copyOf(r.gold);
                List<RetrievalResult> tail = new ArrayList<>();
                for (int i = lo; i < Math.min(hi, r.fusion.size()); i++) tail.add(r.fusion.get(i));
                int tailGold = 0;
                for (RetrievalResult x : tail) {
                    if (goldSet.contains(x.chapterOrdinal() + 1)) tailGold++;
                }
                newGoldChunk += tailGold;
                newNoise += tail.size() - tailGold;
                newChapter += r.st[seg].cov - r.st[seg - 1].cov;
            }
            int addedSlots = rows.size() * (hi - lo);
            sb.append(String.format("- **%d→%d**：新增 %d 槽 —— gold chunk=%d（%.0f%%）、噪声=%d（%.0f%%）；"
                            + "新增命中章节=%d（每条 Query 平均 %.2f 个新命中）。%n",
                    lo, hi, addedSlots, newGoldChunk, newGoldChunk / (double) addedSlots * 100,
                    newNoise, newNoise / (double) addedSlots * 100, newChapter,
                    newChapter / (double) rows.size()));
        }
        return sb.toString();
    }

    /** gold 章节在 fusion（final-30 候选）中的最前 chunk rank（1-based；不在 = -1）。 */
    private static Map<Integer, Integer> fusionRanks(List<RetrievalResult> fusion, List<Integer> gold) {
        Map<Integer, Integer> m = new LinkedHashMap<>();
        for (int g : gold) m.put(g, -1);
        if (fusion == null) return m;
        for (int i = 0; i < fusion.size(); i++) {
            int ch = fusion.get(i).chapterOrdinal() + 1;
            if (m.containsKey(ch) && m.get(ch) < 0) m.put(ch, i + 1);
        }
        return m;
    }

    private record KStat(int cov, double recall, int noise, int goldChunks, int size, double mrr, double ndcg) {
    }

    private record Row(String id, String category, List<Integer> gold, List<RetrievalResult> fusion,
                       KStat[] st, int inFusion) {
    }
}
