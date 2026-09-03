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
import com.inkforge.retrieval.QueryConstructionService;
import com.inkforge.retrieval.QueryIntent;
import com.inkforge.retrieval.QueryIntentClassifier;
import com.inkforge.retrieval.RetrievalProperties;
import com.inkforge.retrieval.RetrievalQuery;
import com.inkforge.retrieval.RetrievalResult;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P5-B1 A/B：coverage=48 记忆上，10 条 P5-0.5 定向 Query 对比
 * A = 原查询文本直接检索；B = Query-aware 构造（意图倾向表达）后检索。
 * 同一 gold / BGE-M3 / BM25+Vector→RRF→PassThrough(top-8)。不改任何 Retrieval。
 */
class QueryAwareRetrievalAblation {

    private static final String BGE_BASE = "http://localhost:8085/v1";
    private static final String NOVEL_FILE = "C:/Users/xun/AppData/Local/Temp/inkforge-e2e/zetian_ch1-48.txt";

    record DQ(String id, String category, String text, int[] gold) {
    }

    static final DQ[] QUERIES = {
            new DQ("A1", "体系", "苦海是如何开辟和修炼的？修炼的路径是怎样的？", new int[]{40, 41, 42, 47}),
            new DQ("A2", "体系", "叶凡的修炼基础有什么特殊之处？他的苦海为何与常人不同？", new int[]{37, 40, 41, 47}),
            new DQ("B1", "关系", "叶凡与庞博的关系是如何形成并发展的？", new int[]{10, 18, 25, 39, 48}),
            new DQ("B2", "关系", "叶凡与刘云志之间的矛盾是如何形成并升级的？", new int[]{3, 4, 19, 24, 31}),
            new DQ("C1", "伏笔", "前文中出现过哪些与荒古圣体或叶凡体质异常相关的线索？", new int[]{37, 47, 48}),
            new DQ("C2", "伏笔", "九龙拉棺与青铜古棺的来历与线索有哪些？", new int[]{2, 5, 9, 26}),
            new DQ("C3", "伏笔", "荒古禁地是什么？它造成了哪些异常或后果？", new int[]{28, 30, 33, 34, 35}),
            new DQ("D1", "跨章", "百草液是如何获得的？", new int[]{43, 46, 47}),
            new DQ("D2", "跨章", "古经/道经是如何传给叶凡和庞博的？", new int[]{38, 41}),
            new DQ("D3", "跨章", "韩飞羽与叶凡、庞博之间的冲突是如何发生的？", new int[]{43, 44, 45}),
    };

    @Test
    void queryAwareVsBaseline() throws Exception {
        Path out = Path.of("target/e2e/query-aware-retrieval");
        Files.createDirectories(out);

        String apiKey = System.getenv("INKFORGE_LLM_API_KEY");
        if (apiKey == null || apiKey.isBlank()) apiKey = System.getenv("LLM_API_KEY");
        if (apiKey == null || apiKey.isBlank()) throw new IllegalStateException("无 deepseek key");
        String model = System.getenv().getOrDefault("INKFORGE_LLM_MODEL", "deepseek-v4-flash");
        String baseUrl = System.getenv().getOrDefault("INKFORGE_LLM_BASE_URL", "https://api.deepseek.com");

        ObjectMapper om = new ObjectMapper();
        TokenCounter tc = new JtokkitTokenCounter();
        PromptCatalog catalog = new ClasspathPromptCatalog();
        MemoryExtractionProperties props = new MemoryExtractionProperties(3, 12000, 2048, 4096, 0.2, 2, 0.7, 300, 200);
        RetrievalProperties rp = new RetrievalProperties(30, 30, 30, 8, 60, "passthrough", 15, 200);
        LlmProvider deepseek = new OpenAiCompatibleLlmProvider("deepseek",
                WebClient.builder().baseUrl(baseUrl).build(),
                new LlmProperties("deepseek", baseUrl, apiKey, model, 300, new LlmProperties.Mock(0)), om);
        EmbeddingProperties bgeProps = new EmbeddingProperties("openai-compatible", "BAAI/bge-m3", BGE_BASE, "local", 1024, 16, 120);

        // 加载 1-48 章 + 建 coverage=48 记忆（真实 deepseek 提取一次）
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
            if (outcome.result() != null) {
                update.apply(novel.id(), ch, outcome.result());
                projection.projectChapter(novel.id(), ch.ordinal());
                ok++;
            }
        }
        System.out.println("coverage=48 记忆: " + ok + "/" + chapters.size());

        OpenAiCompatibleEmbeddingProvider bge = new OpenAiCompatibleEmbeddingProvider("openai-compatible",
                WebClient.builder().baseUrl(BGE_BASE).build(), bgeProps, om);
        InMemoryChunkEmbeddingStore store = new InMemoryChunkEmbeddingStore();
        new MemoryEmbeddingService(bge, chunkRepo, store, bgeProps).embedNovel(novel.id());
        LuceneBm25Retriever bm25 = new LuceneBm25Retriever(chunkRepo);
        InMemoryVectorRetriever vec = new InMemoryVectorRetriever(bge, chunkRepo, store, bgeProps);
        HybridRetrievalService hybrid = new HybridRetrievalService(bm25, vec, new PassThroughReranker(), rp);
        QueryConstructionService constructor = new QueryConstructionService(new QueryIntentClassifier());

        StringBuilder sb = new StringBuilder("# Query-aware Query Construction A/B（coverage=48）\n\n");
        sb.append("A = 原查询文本；B = Query-aware 构造。同一 gold/BGE-M3/BM25+Vector→RRF→PassThrough(top-8)。\n\n");
        sb.append("| Q | 类别 | intent | A R@5 | A R@8 | A EvCov | B R@5 | B R@8 | B EvCov |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|\n");

        double[] sum = new double[6];
        for (DQ q : QUERIES) {
            List<RetrievalResult> resA = hybrid.retrieve(novel.id(), q.text());
            // B：构造查询集，逐条 retrieve + merge（保留最高分）→ top-8
            List<RetrievalQuery> constructed = constructor.construct("directed", q.text());
            Map<String, RetrievalResult> merged = new LinkedHashMap<>();
            for (RetrievalQuery rq : constructed) {
                for (RetrievalResult r : hybrid.retrieve(novel.id(), rq.text())) {
                    merged.merge(r.chunkId(), r, (a, b) -> a.score() >= b.score() ? a : b);
                }
            }
            List<RetrievalResult> resB = merged.values().stream()
                    .sorted(Comparator.comparingDouble(RetrievalResult::score).reversed())
                    .limit(8).toList();

            double[] mA = metrics(resA, q.gold());
            double[] mB = metrics(resB, q.gold());
            for (int i = 0; i < 6; i++) sum[i] += (i < 3 ? mA[i % 3] : mB[i % 3]);
            String intent = constructed.isEmpty() ? "?" : String.valueOf(constructed.get(0).intent());
            sb.append(String.format("| %s | %s | %s | %.2f | %.2f | %.2f | %.2f | %.2f | %.2f |%n",
                    q.id, q.category, intent, mA[0], mA[1], mA[2], mB[0], mB[1], mB[2]));
            System.out.printf("[%s %s] intent=%s A: R5=%.2f EvCov=%.2f | B: R5=%.2f EvCov=%.2f%n",
                    q.id, q.category, intent, mA[0], mA[2], mB[0], mB[2]);
        }
        sb.append(String.format("%n| 平均 | | | %.2f | %.2f | %.2f | %.2f | %.2f | %.2f |%n",
                sum[0] / QUERIES.length, sum[1] / QUERIES.length, sum[2] / QUERIES.length,
                sum[3] / QUERIES.length, sum[4] / QUERIES.length, sum[5] / QUERIES.length));
        Files.writeString(out.resolve("query-aware-ab.md"), sb.toString());
        System.out.println("done → " + out.resolve("query-aware-ab.md"));
    }

    /** [R@5, R@8, EvCov] — gold 章节去重命中（top-8 内）。 */
    private static double[] metrics(List<RetrievalResult> results, int[] gold) {
        List<Integer> goldList = new ArrayList<>();
        for (int g : gold) goldList.add(g);
        int top5 = 0, top8 = 0;
        var seen = new java.util.HashSet<Integer>();
        for (int i = 0; i < Math.min(results.size(), 8); i++) {
            int ch = results.get(i).chapterOrdinal() + 1;
            if (goldList.contains(ch) && seen.add(ch)) {
                if (i < 5) top5++;
                top8++;
            }
        }
        return new double[]{top5 / (double) goldList.size(), top8 / (double) goldList.size(),
                seen.size() / (double) goldList.size()};
    }
}
