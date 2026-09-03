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
import com.inkforge.retrieval.InMemoryChunkEmbeddingStore;
import com.inkforge.retrieval.InMemoryMemoryChunkRepository;
import com.inkforge.retrieval.InMemoryVectorRetriever;
import com.inkforge.retrieval.LuceneBm25Retriever;
import com.inkforge.retrieval.MemoryChunkProjectionService;
import com.inkforge.retrieval.MemoryChunkRepository;
import com.inkforge.retrieval.MemoryEmbeddingService;
import com.inkforge.retrieval.RetrievalResult;
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

/**
 * P5-B2-0/1 Retrieval Top-K Ablation：对 10 条 P5-0.5 定向 Query（coverage=48），
 * 追踪 Gold Evidence 在 BM25(top-30) / Vector(top-30) / RRF Fusion(top-30)
 * 以及 Fusion 前缀 top-8/15/30 各层是否存在，判断是"没进候选池"还是"被 Top-K 截断"。
 *
 * <p>不改任何 Retrieval（RRF k=60、fusion-top-30、rerank top-8 全保持）；K 用 fusion 前缀评估。
 */
class RetrievalTopKAblation {

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

    private static final int[] KS = {8, 15, 30};

    @Test
    void topKAblation() throws Exception {
        Path out = Path.of("target/e2e/retrieval-topk-ablation");
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
        EmbeddingProperties bgeProps = new EmbeddingProperties("openai-compatible", "BAAI/bge-m3", BGE_BASE, "local", 1024, 16, 120);
        LlmProvider deepseek = new OpenAiCompatibleLlmProvider("deepseek",
                WebClient.builder().baseUrl(baseUrl).build(),
                new LlmProperties("deepseek", baseUrl, apiKey, model, 300, new LlmProperties.Mock(0)), om);

        // coverage=48 记忆（一次真实 deepseek 提取）
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
        System.out.println("coverage=48 记忆: " + ok + "/" + chapters.size());

        OpenAiCompatibleEmbeddingProvider bge = new OpenAiCompatibleEmbeddingProvider("openai-compatible",
                WebClient.builder().baseUrl(BGE_BASE).build(), bgeProps, om);
        InMemoryChunkEmbeddingStore store = new InMemoryChunkEmbeddingStore();
        new MemoryEmbeddingService(bge, chunkRepo, store, bgeProps).embedNovel(novel.id());
        LuceneBm25Retriever bm25 = new LuceneBm25Retriever(chunkRepo);
        InMemoryVectorRetriever vec = new InMemoryVectorRetriever(bge, chunkRepo, store, bgeProps);

        StringBuilder sb = new StringBuilder("# P5-B2 Top-K Ablation（coverage=48，10 条定向 Query）\n\n");
        sb.append("固定 BM25/Vector top-30 → RRF(k=60, fusion-top-30)。K 用 fusion 前缀评估（不改 RRF/reranker）。\n\n");
        sb.append("| Q | gold | BM25∋ | Vec∋ | Fusion∋ | Fusion@8 | @15 | @30 | Gold在Fusion最前rank |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|\n");

        int[] aggBM25 = new int[QUERIES.length], aggVec = new int[QUERIES.length], aggFus = new int[QUERIES.length];
        StringBuilder detail = new StringBuilder();

        for (int qi = 0; qi < QUERIES.length; qi++) {
            DQ q = QUERIES[qi];
            List<Integer> goldList = new ArrayList<>();
            for (int g : q.gold()) goldList.add(g);

            List<RetrievalResult> bm25Res = bm25.retrieve(novel.id(), q.text(), 30);
            List<RetrievalResult> vecRes = vec.retrieve(novel.id(), q.text(), 30);
            List<RetrievalResult> fusion = RrfFusion.fuse(List.of(bm25Res, vecRes), 60, 30);

            // 每个 gold 章节在 bm25/vector/fusion 的最前 rank（无则 -1）
            Map<Integer, Integer> bmRank = minRank(bm25Res, goldList);
            Map<Integer, Integer> vecRank = minRank(vecRes, goldList);
            Map<Integer, Integer> fusRank = minRank(fusion, goldList);

            int inBM = (int) bmRank.values().stream().filter(r -> r >= 0).count();
            int inVec = (int) vecRank.values().stream().filter(r -> r >= 0).count();
            int inFus = (int) fusRank.values().stream().filter(r -> r >= 0).count();
            int inFus8 = (int) fusRank.values().stream().filter(r -> r >= 0 && r < 8).count();
            int inFus15 = (int) fusRank.values().stream().filter(r -> r >= 0 && r < 15).count();
            int inFus30 = inFus;
            aggBM25[qi] = inBM; aggVec[qi] = inVec; aggFus[qi] = inFus;

            int bestFusRank = fusRank.values().stream().filter(r -> r >= 0).min(Integer::compareTo).orElse(-1);
            sb.append(String.format("| %s | %d | %d/%d | %d/%d | %d/%d | %d/%d | %d/%d | %d/%d | %s |%n",
                    q.id, q.gold().length, inBM, q.gold().length, inVec, q.gold().length, inFus, q.gold().length,
                    inFus8, q.gold().length, inFus15, q.gold().length, inFus30, q.gold().length,
                    bestFusRank < 0 ? "∅" : String.valueOf(bestFusRank + 1)));

            detail.append("## ").append(q.id).append(" ").append(q.category).append(" — ").append(q.text()).append("\n");
            for (int g : q.gold()) {
                detail.append(String.format("- gold ch%d: BM25 rank=%s, Vector rank=%s, Fusion rank=%s%n",
                        g, fmtRank(bmRank.getOrDefault(g, -1)), fmtRank(vecRank.getOrDefault(g, -1)),
                        fmtRank(fusRank.getOrDefault(g, -1))));
            }
            System.out.printf("[%s] BM25=%d/%d Vec=%d/%d Fus=%d/%d Fus@8=%d/%d bestFusionRank=%s%n",
                    q.id, inBM, q.gold().length, inVec, q.gold().length, inFus, q.gold().length, inFus8, q.gold().length,
                    bestFusRank < 0 ? "∅" : bestFusRank + 1);
        }
        sb.append("\n" + detail);
        Files.writeString(out.resolve("topk-ab.md"), sb.toString());
        System.out.println("done → " + out.resolve("topk-ab.md"));
    }

    private static Map<Integer, Integer> minRank(List<RetrievalResult> results, List<Integer> gold) {
        Map<Integer, Integer> map = new LinkedHashMap<>();
        for (int g : gold) map.put(g, -1);
        for (int i = 0; i < results.size(); i++) {
            int ch = results.get(i).chapterOrdinal() + 1;
            if (map.containsKey(ch) && map.get(ch) < 0) map.put(ch, i);
        }
        return map;
    }

    private static String fmtRank(int rank) {
        return rank < 0 ? "∅" : String.valueOf(rank + 1);
    }
}
