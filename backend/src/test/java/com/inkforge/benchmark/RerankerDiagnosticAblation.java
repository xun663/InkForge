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
import com.inkforge.retrieval.LlmListwiseReranker;
import com.inkforge.retrieval.LuceneBm25Retriever;
import com.inkforge.retrieval.MemoryChunkProjectionService;
import com.inkforge.retrieval.MemoryChunkRepository;
import com.inkforge.retrieval.MemoryEmbeddingService;
import com.inkforge.retrieval.PassThroughReranker;
import com.inkforge.retrieval.QueryIntent;
import com.inkforge.retrieval.QueryIntentClassifier;
import com.inkforge.retrieval.RetrievalProperties;
import com.inkforge.retrieval.RetrievalResult;
import com.inkforge.retrieval.RetrievalSelectionSim;
import com.inkforge.retrieval.RrfFusion;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * P5-B3-1 Ranking / Reranker Diagnostic：对每条 Directed Query 的每个 gold，跨层追踪
 * BM25 → Vector → RRF/Fusion(top-30) → Reranker 输入 → Reranker 输出 → Context(rank-preserving)。
 *
 * <p>生产默认 Reranker = PassThrough（identity，final=RRF fusion top-30）；代码内唯一真实 reranker 为
 * LlmListwiseReranker（只对 fusion 前 rerankMaxCandidates=15 条重排，输出 ≤15）。本阶段对同一候选池做
 * PassThrough vs 项目自有的 LLM reranker A/B，判断 rerank 是否有价值。全部冻结：memory/extraction 共享一次、
 * BM25/Vector top-30、RRF k=60、fusion top-30、Top-K=30、QueryBuilder/QueryIntent/Memory/Embedding/Context/Prompt。
 *
 * <p>纯诊断：不改生产、不切新模型。存档 target/e2e/reranker-diagnostic/reranker-diag.md。
 */
class RerankerDiagnosticAblation {

    private static final String BGE_BASE = "http://localhost:8085/v1";
    private static final String NOVEL_FILE = "C:/Users/xun/AppData/Local/Temp/inkforge-e2e/zetian_ch1-48.txt";
    private static final int CONTEXT_MAX = 8192;
    private static final int[][] SECTIONS = {
            {1, 2048, 4096, 1}, {2, 128, 1024, 1}, {3, 0, 1024, 0}, {4, 0, 768, 0},
            {5, 0, 1024, 0}, {6, 0, 1280, 0}, {7, 0, 512, 0}, {8, 0, 256, 0},
    };

    @Test
    void rerankerDiagnostic() throws Exception {
        Path out = Path.of("target/e2e/reranker-diagnostic");
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

        // coverage=48 记忆（一次提取，A/B 同源）
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
        RetrievalProperties base = new RetrievalProperties(30, 30, 30, 30, 60, "passthrough", 15, 200);
        HybridRetrievalService hy = new HybridRetrievalService(bm25, vec, new PassThroughReranker(), base);
        LlmListwiseReranker llmReranker = new LlmListwiseReranker(deepseek, catalog, base, om);
        QueryIntentClassifier classifier = new QueryIntentClassifier();

        int fixed = fixedSkeletonTokens(novel, catalog, tc);
        int R = allocateRetrievedTokens(fixed, CONTEXT_MAX);
        int bodyBudget = Math.max(0, R - tc.count(RetrievalSelectionSim.SECTION_HEADER + "\n"));

        StringBuilder sb = new StringBuilder("# P5-B3-1 Ranking / Reranker Diagnostic（gold 分层追踪）\n\n");
        sb.append("coverage=48 · 10 条定向 Query · Top-K=30 · 生产默认 Reranker=PassThrough。"
                + "同一候选池上额外跑项目自有 LlmListwiseReranker（输入 fusion 前 15、输出 ≤15）判断 rerank 价值。\n");
        sb.append("R=").append(R).append(" bodyBudget=").append(bodyBudget).append("（Context rank-preserving 预算）。\n\n");

        List<RerankDiagnostics.GoldTrace> traces = new ArrayList<>();
        Map<String, RerankDiagnostics.Metrics> ptMetrics = new LinkedHashMap<>();
        Map<String, RerankDiagnostics.Metrics> llmMetrics = new LinkedHashMap<>();
        Map<String, QueryIntent> queryIntent = new LinkedHashMap<>();
        int llmDegraded = 0;

        for (var q : RetrievalTopKAblation.QUERIES) {
            List<Integer> goldList = new ArrayList<>();
            for (int g : q.gold()) goldList.add(g);
            Set<Integer> goldSet = Set.copyOf(goldList);
            QueryIntent intent = classifier.classify(q.text());
            queryIntent.put(q.id(), intent);

            List<RetrievalResult> bm25Res = bm25.retrieve(novel.id(), q.text(), 30);
            List<RetrievalResult> vecRes = vec.retrieve(novel.id(), q.text(), 30);
            List<RetrievalResult> fusion = RrfFusion.fuse(List.of(bm25Res, vecRes), 60, 30); // = PassThrough final

            List<RetrievalResult> llmOut;
            boolean degraded = false;
            try {
                llmOut = llmReranker.rerank(q.text(), fusion, 30);
            } catch (Exception e) {
                degraded = true; llmOut = fusion; // 生产同款降级
            }
            if (degraded) llmDegraded++;
            String ptCtx = RetrievalSelectionSim.SECTION_HEADER + "\n"
                    + RetrievalSelectionSim.selectRankPreserving(fusion, bodyBudget, tc);

            for (int g : goldList) {
                int b = firstRank(bm25Res, g);
                int v = firstRank(vecRes, g);
                int f = firstRank(fusion, g);
                int in = f >= 0 && f <= base.rerankMaxCandidates() ? f : RerankDiagnostics.MISS;
                int o = firstRank(llmOut, g);
                boolean ctx = RetrievalSelectionSim.chapterPresent(ptCtx, g);
                traces.add(new RerankDiagnostics.GoldTrace(q.id(), intent, g, b, v, f, in, o, ctx));
            }
            ptMetrics.put(q.id(), RerankDiagnostics.metrics(fusion, goldList));
            llmMetrics.put(q.id(), RerankDiagnostics.metrics(llmOut, goldList));
        }

        writeGoldTable(sb, traces);
        writeSummary(sb, traces, ptMetrics, llmMetrics, queryIntent, llmDegraded);

        Files.writeString(out.resolve("reranker-diag.md"), sb.toString());
        System.out.println(sb);
        System.out.println("done → " + out.resolve("reranker-diag.md"));
    }

    private static void writeGoldTable(StringBuilder sb, List<RerankDiagnostics.GoldTrace> traces) {
        sb.append("## Gold 分层追踪\n\n");
        sb.append("| Q | intent | gold | BM25 | Vec | Fusion | Rerank输入 | Rerank输出 | Δ | 判定 | Context |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (RerankDiagnostics.GoldTrace t : traces) {
            sb.append(String.format("| %s | %s | ch%d | %s | %s | %s | %s | %s | %d | %s | %s |%n",
                    t.query(), t.intent(), t.goldChapter(),
                    fmt(t.bm25Rank()), fmt(t.vectorRank()), fmt(t.fusionRank()), fmt(t.rerankInputRank()), fmt(t.rerankOutputRank()),
                    t.rankDelta(), t.classify(), t.contextHit() ? "YES" : "NO"));
        }
    }

    private static void writeSummary(StringBuilder sb, List<RerankDiagnostics.GoldTrace> traces,
                                     Map<String, RerankDiagnostics.Metrics> pt,
                                     Map<String, RerankDiagnostics.Metrics> llm,
                                     Map<String, QueryIntent> qi, int llmDegraded) {
        RerankDiagnostics.Metrics sumPt = sum(pt), sumLlm = sum(llm);
        int goldTotal = sumPt.goldTotal();

        sb.append("\n## 汇总（gold 口径：recall/mrr/ndcg 取逐 query 平均；Gold@K 为逐 query 求和）\n\n");
        sb.append("| 指标 | PassThrough(生产默认) | LlmListwiseReranker |\n|---|---|---|\n");
        sb.append(String.format("| 平均 Recall | %.3f | %.3f |%n", sumPt.recall() / pt.size(), sumLlm.recall() / llm.size()));
        sb.append(String.format("| 平均 MRR | %.3f | %.3f |%n", sumPt.mrr() / pt.size(), sumLlm.mrr() / llm.size()));
        sb.append(String.format("| 平均 NDCG | %.3f | %.3f |%n", sumPt.ndcg() / pt.size(), sumLlm.ndcg() / llm.size()));
        sb.append(String.format("| Gold 覆盖（入最终列表） | %d/%d | %d/%d |%n", sumPt.goldCovered(), goldTotal, sumLlm.goldCovered(), goldTotal));
        sb.append(String.format("| Gold@Top5 合计 | %d | %d |%n", sumPt.top5(), sumLlm.top5()));
        sb.append(String.format("| Gold@Top10 合计 | %d | %d |%n", sumPt.top10(), sumLlm.top10()));
        sb.append(String.format("| Gold@Top15 合计 | %d | %d |%n", sumPt.top15(), sumLlm.top15()));

        // 分层统计
        int candMiss = 0, rankingMiss = 0, helped = 0, hurt = 0, neutral = 0, ctxHit = 0, ctxMiss = 0;
        for (RerankDiagnostics.GoldTrace t : traces) {
            switch (t.classify()) {
                case CANDIDATE_MISS -> candMiss++;
                case RANKING_MISS -> rankingMiss++;
                case RERANKER_HELPED -> helped++;
                case RERANKER_HURT -> hurt++;
                case RERANKER_NEUTRAL -> neutral++;
            }
            if (t.contextHit()) ctxHit++; else if (t.inFusion()) ctxMiss++;
        }
        sb.append(String.format("\n## 分层（%d 条 gold）\n\n", traces.size()));
        sb.append(String.format("- CANDIDATE_MISS（未进 fusion top-30，与 reranker 无关）= %d%n", candMiss));
        sb.append(String.format("- 进 fusion 但未进 rerank 输入(fusion前15) 或 rerank 后丢（RANKING_MISS）= %d%n", rankingMiss));
        sb.append(String.format("- RERANKER_HELPED = %d / RERANKER_HURT = %d / RERANKER_NEUTRAL = %d%n", helped, hurt, neutral));
        sb.append(String.format("- 进 fusion 且最终进 Context（rank-preserving，retrieved 区段预算）YES=%d / NO=%d%n", ctxHit, ctxMiss));
        sb.append(String.format("- Llm reranker 降级（fallback fusion）次数 = %d%n", llmDegraded));
        sb.append(String.format("\n**Reranker verdict** = %s（仅对进入 rerank 输入的 gold 计）。%n", RerankDiagnostics.verdict(traces)));
    }

    private static RerankDiagnostics.Metrics sum(Map<String, RerankDiagnostics.Metrics> m) {
        RerankDiagnostics.Metrics seed = new RerankDiagnostics.Metrics(0, 0, 0, 0, 0, 0, 0, 0);
        for (RerankDiagnostics.Metrics mm : m.values()) {
            seed = new RerankDiagnostics.Metrics(seed.recall() + mm.recall(), seed.mrr() + mm.mrr(), seed.ndcg() + mm.ndcg(),
                    seed.goldCovered() + mm.goldCovered(), seed.goldTotal() + mm.goldTotal(), seed.top5() + mm.top5(),
                    seed.top10() + mm.top10(), seed.top15() + mm.top15());
        }
        return seed;
    }

    private static int firstRank(List<RetrievalResult> results, int chapter) {
        for (int i = 0; i < results.size(); i++) {
            if (results.get(i).chapterOrdinal() + 1 == chapter) return i + 1;
        }
        return RerankDiagnostics.MISS;
    }

    private static String fmt(int rank) {
        return rank < 0 ? "∅" : String.valueOf(rank);
    }

    private static int fixedSkeletonTokens(Novel novel, PromptCatalog catalog, TokenCounter tc) {
        Chapter last = novel.lastChapter();
        String chapterNo = last.chapterNo() != null ? "第" + last.chapterNo() + "章" : last.title();
        String system = catalog.render("continuation.system.txt", Map.of(
                "novelTitle", novel.title(), "chapterNo", chapterNo, "chapterTitle", last.title()));
        String skeleton = catalog.render("continuation.memory.user.txt", Map.of("sections", ""));
        return tc.count(system) + tc.count(skeleton);
    }

    private static int allocateRetrievedTokens(int fixedTokens, int contextMax) {
        int remaining = contextMax - fixedTokens;
        if (remaining <= 0) return 0;
        Map<Integer, int[]> alloc = new LinkedHashMap<>();
        for (int[] s : SECTIONS) alloc.put(s[0], new int[]{0});
        for (int[] s : SECTIONS) if (s[3] == 1) {
            int reserve = Math.min(s[1], remaining);
            alloc.get(s[0])[0] = reserve;
            remaining -= reserve;
        }
        for (int[] s : SECTIONS) {
            int cur = alloc.get(s[0])[0];
            int topUp = Math.min(Math.max(0, s[2] - cur), remaining);
            if (topUp > 0) { alloc.get(s[0])[0] = cur + topUp; remaining -= topUp; }
        }
        return alloc.get(5)[0];
    }
}
