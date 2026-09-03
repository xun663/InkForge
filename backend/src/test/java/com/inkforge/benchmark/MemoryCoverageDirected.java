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
import com.inkforge.context.ContextProperties;
import com.inkforge.context.MemoryAwareContextBuilder;
import com.inkforge.context.RecentChaptersContextBuilder;
import com.inkforge.memory.InMemoryStoryMemoryRepository;
import com.inkforge.memory.MemoryUpdateService;
import com.inkforge.memory.StoryMemoryRepository;
import com.inkforge.memory.extraction.ExtractionValidator;
import com.inkforge.memory.extraction.MemoryExtractionProperties;
import com.inkforge.memory.extraction.MemoryExtractor;
import com.inkforge.novel.Novel;
import com.inkforge.provider.ChatMessage;
import com.inkforge.provider.EmbeddingProperties;
import com.inkforge.provider.LlmProperties;
import com.inkforge.provider.LlmProvider;
import com.inkforge.provider.LlmRequest;
import com.inkforge.provider.LlmResponse;
import com.inkforge.provider.OpenAiCompatibleEmbeddingProvider;
import com.inkforge.provider.OpenAiCompatibleLlmProvider;
import com.inkforge.provider.TaskType;
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
import com.inkforge.retrieval.RetrievedMemory;
import com.inkforge.retrieval.RetrievedMemoryProvider;
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
 * P5-0.5 体系定向 Query A/B（遮天 1-48 章）。
 * 唯一变量 = Memory Coverage（3/10/20/48）。固定 BGE-M3 / deepseek-v4-flash / 1200 tokens / 8192 context。
 * 每条 Query 有明确 gold 章节；统计 Recall@5/10、MRR、NDCG、Evidence Coverage（gold 证据进入 final context 的比例），
 * 并让 deepseek 依据 context 作答（0-2 评分）。只读生产类，不修改任何代码。
 */
class MemoryCoverageDirected {

    private static final String BGE_BASE = "http://localhost:8085/v1";
    private static final int CONTEXT_MAX = 8192;
    private static final String NOVEL_FILE = "C:/Users/xun/AppData/Local/Temp/inkforge-e2e/zetian_ch1-48.txt";
    private static final int[] COVERAGES = {3, 10, 20, 48};

    record DQuery(String id, String category, String text, int[] goldChapters) {
    }

    // Gold 章节基于实际记忆 dump（sourceChapter + 1，因为 dump 里是 1 基章节号）
    static final DQuery[] QUERIES = {
            new DQuery("A1", "体系", "苦海是如何开辟和修炼的？修炼的路径是怎样的？", new int[]{40, 41, 42, 47}),
            new DQuery("A2", "体系", "叶凡的修炼基础有什么特殊之处？他的苦海为何与常人不同？", new int[]{37, 40, 41, 47}),
            new DQuery("B1", "关系", "叶凡与庞博的关系是如何形成并发展的？", new int[]{10, 18, 25, 39, 48}),
            new DQuery("B2", "关系", "叶凡与刘云志之间的矛盾是如何形成并升级的？", new int[]{3, 4, 19, 24, 31}),
            new DQuery("C1", "伏笔", "前文中出现过哪些与荒古圣体或叶凡体质异常相关的线索？", new int[]{37, 47, 48}),
            new DQuery("C2", "伏笔", "九龙拉棺与青铜古棺的来历与线索有哪些？", new int[]{2, 5, 9, 26}),
            new DQuery("C3", "伏笔", "荒古禁地是什么？它造成了哪些异常或后果？", new int[]{28, 30, 33, 34, 35}),
            new DQuery("D1", "跨章", "百草液是如何获得的？", new int[]{43, 46, 47}),
            new DQuery("D2", "跨章", "古经/道经是如何传给叶凡和庞博的？", new int[]{38, 41}),
            new DQuery("D3", "跨章", "韩飞羽与叶凡、庞博之间的冲突是如何发生的？", new int[]{43, 44, 45}),
    };

    @Test
    void directedQueryAb() throws Exception {
        Path out = Path.of("target/e2e/memory-coverage-directed");
        Files.createDirectories(out);

        String apiKey = System.getenv("INKFORGE_LLM_API_KEY");
        if (apiKey == null || apiKey.isBlank()) apiKey = System.getenv("LLM_API_KEY");
        if (apiKey == null || apiKey.isBlank()) throw new IllegalStateException("无 deepseek key");
        String model = System.getenv().getOrDefault("INKFORGE_LLM_MODEL", "deepseek-v4-flash");
        String baseUrl = System.getenv().getOrDefault("INKFORGE_LLM_BASE_URL", "https://api.deepseek.com");

        ObjectMapper mapper = new ObjectMapper();
        TokenCounter tokenCounter = new JtokkitTokenCounter();
        PromptCatalog catalog = new ClasspathPromptCatalog();
        MemoryExtractionProperties extractionProps =
                new MemoryExtractionProperties(3, 12000, 2048, 4096, 0.2, 2, 0.7, 300, 200);
        ContextProperties contextProps = new ContextProperties(CONTEXT_MAX, 2000, Map.of());
        RetrievalProperties rp = new RetrievalProperties(30, 30, 30, 8, 60, "passthrough", 15, 200);

        LlmProvider deepseek = new OpenAiCompatibleLlmProvider("deepseek",
                WebClient.builder().baseUrl(baseUrl).build(),
                new LlmProperties("deepseek", baseUrl, apiKey, model, 300, new LlmProperties.Mock(0)), mapper);
        EmbeddingProperties bgeProps = new EmbeddingProperties("openai-compatible", "BAAI/bge-m3", BGE_BASE, "local", 1024, 16, 120);

        ParsedNovel parsed = new TxtNovelParser(new CharsetDetector(), new ChapterSplitter()).parse(
                Files.readAllBytes(Path.of(NOVEL_FILE)), "zetian_ch1-48.txt");
        List<Chapter> chapters = parsed.chapters();
        if (!chapters.isEmpty() && chapters.get(0).chapterNo() == null) chapters = chapters.subList(1, chapters.size());
        Novel novel = new Novel("zetian-48", parsed.title(), "zetian_ch1-48.txt", chapters);

        String filter = System.getProperty("coverage.filter");
        List<Integer> covs = new ArrayList<>();
        for (int c : COVERAGES) {
            if (filter == null || filter.isBlank() || filter.contains(String.valueOf(c))) covs.add(c);
        }

        StringBuilder summary = new StringBuilder("# P5-0.5 体系定向 Query A/B（遮天 1-48 章）\n\n");
        summary.append("唯一变量 = Memory Coverage。固定 BGE-M3 / deepseek-v4-flash / 1200 tokens / 8192 context / BM25+Vector→RRF→PassThrough。\n\n");

        // 头部表格
        summary.append("| Q | 类别 | Coverage | 记忆(人物/事实/事件/chunk) | R@5 | R@10 | MRR | NDCG | EvCov | LLM分 |\n");
        summary.append("|---|---|---|---|---|---|---|---|---|---|---|\n");

        for (int cov : covs) {
            Path covDir = out.resolve(cov + "-chapters");
            Files.createDirectories(covDir);

            // 建记忆
            StoryMemoryRepository memRepo = new InMemoryStoryMemoryRepository();
            MemoryChunkRepository chunkRepo = new InMemoryMemoryChunkRepository();
            MemoryExtractor extractor = new MemoryExtractor(deepseek, catalog, tokenCounter,
                    new ExtractionValidator(), extractionProps, mapper);
            MemoryUpdateService update = new MemoryUpdateService(memRepo, extractionProps);
            MemoryChunkProjectionService projection = new MemoryChunkProjectionService(memRepo, chunkRepo);
            List<Chapter> slice = chapters.subList(Math.max(0, chapters.size() - cov), chapters.size());
            int ok = 0;
            for (Chapter ch : slice) {
                var outcome = extractor.extract(ch, ch.chapterNo() != null ? "第" + ch.chapterNo() + "章" : ch.title());
                if (outcome.result() != null) { update.apply(novel.id(), ch, outcome.result()); projection.projectChapter(novel.id(), ch.ordinal()); ok++; }
            }
            int chars = memRepo.findCharacters(novel.id()).size();
            int facts = memRepo.findCharacters(novel.id()).stream().mapToInt(c -> memRepo.findFacts(c.id()).size()).sum();
            int events = memRepo.findEvents(novel.id(), Integer.MAX_VALUE, false).size();
            int chunks = chunkRepo.findByNovelId(novel.id()).size();
            Files.writeString(covDir.resolve("memory.json"),
                    "# coverage " + cov + "\n人物 " + chars + " 事实 " + facts + " 事件 " + events + " chunk " + chunks + "\n成功 " + ok + "/" + slice.size() + "\n");

            // 嵌入
            OpenAiCompatibleEmbeddingProvider bgeProv = new OpenAiCompatibleEmbeddingProvider(
                    "openai-compatible", WebClient.builder().baseUrl(BGE_BASE).build(), bgeProps, mapper);
            InMemoryChunkEmbeddingStore store = new InMemoryChunkEmbeddingStore();
            new MemoryEmbeddingService(bgeProv, chunkRepo, store, bgeProps).embedNovel(novel.id());
            LuceneBm25Retriever bm25 = new LuceneBm25Retriever(chunkRepo);
            InMemoryVectorRetriever vec = new InMemoryVectorRetriever(bgeProv, chunkRepo, store, bgeProps);
            HybridRetrievalService hybrid = new HybridRetrievalService(bm25, vec, new PassThroughReranker(), rp);

            // 每条定向 query
            StringBuilder perCov = new StringBuilder();
            for (DQuery q : QUERIES) {
                RetrievedMemory rm = new RetrievedMemory(hybrid.retrieve(novel.id(), q.text()), "q");
                // 指标
                double[] metrics = metrics(rm.results(), q.goldChapters());
                double r5 = metrics[0], r10 = metrics[1], mrr = metrics[2], ndcg = metrics[3];
                // Evidence Coverage：gold 章节在 final(top-8) 中的覆盖
                double evCov = evidenceCoverage(rm.results(), q.goldChapters());

                // context（仅 retrieved-memory 段不同，其余段同）
                RetrievedMemoryProvider provider = (n, t, g) -> rm;
                MemoryAwareContextBuilder builder = new MemoryAwareContextBuilder(catalog, tokenCounter, memRepo,
                        new RecentChaptersContextBuilder(catalog, tokenCounter), contextProps, provider);
                List<ChatMessage> ctx = builder.build(novel, CONTEXT_MAX);
                String ctxText = renderContext(ctx);

                // LLM 作答
                String answer = answer(deepseek, model, ctx, q.text());

                Files.writeString(covDir.resolve("q-" + q.id + "-" + q.category + ".md"),
                        "## " + q.id + " " + q.category + " " + q.text() + "\n\n### gold 章节: " + chaptersOf(q.goldChapters()) + "\n\n### trace\n" + render(rm.results()) + "\n### context\n" + ctxText + "\n### answer\n" + answer + "\n");

                // LLM 分（0-2）：answer 是否覆盖 gold 关键点（人工在报告里修正，这里先记原始答案）
                int llmScore = quickScore(answer);
                summary.append("| ").append(q.id).append(" | ").append(q.category)
                        .append(" | ").append(cov)
                        .append(" | ").append(chars).append("/").append(facts).append("/").append(events).append("/").append(chunks)
                        .append(" | ").append(fmt(r5)).append(" | ").append(fmt(r10)).append(" | ").append(fmt(mrr))
                        .append(" | ").append(fmt(ndcg)).append(" | ").append(fmt(evCov)).append(" | ").append(llmScore).append(" |\n");
                System.out.println("[cov=" + cov + " q=" + q.id + "] R@5=" + fmt(r5) + " R@10=" + fmt(r10)
                        + " MRR=" + fmt(mrr) + " EvCov=" + fmt(evCov) + " (gold " + chaptersOf(q.goldChapters()) + ")");
            }
            Files.writeString(covDir.resolve("results.md"), perCov.toString());
        }
        Files.writeString(out.resolve("summary.md"), summary.toString());
        System.out.println("done → " + out.resolve("summary.md"));
    }

    /** metrics: [R@5, R@10, MRR, NDCG@10] — gold 命中按 chapter（chunk 任一类型命中即算）。 */
    private static double[] metrics(List<RetrievalResult> results, int[] gold) {
        int top5 = 0, top10 = 0, firstRank = -1;
        List<Integer> goldList = new ArrayList<>();
        for (int g : gold) goldList.add(g);
        double dcg = 0;
        int matched = 0;
        List<Integer> hitRanks = new ArrayList<>();
        for (int i = 0; i < Math.min(results.size(), 10); i++) {
            int ch = results.get(i).chapterOrdinal() + 1; // 0 基 → 1 基
            if (goldList.contains(ch)) {
                if (firstRank < 0) firstRank = i + 1;
                if (i < 5) top5++;
                top10++;
                dcg += 1.0 / Math.log(i + 2);
                hitRanks.add(i);
            }
        }
        double recall5 = top5 / (double) goldList.size();
        double recall10 = top10 / (double) goldList.size();
        double mrr = firstRank > 0 ? 1.0 / firstRank : 0;
        double idcg = 0;
        for (int i = 0; i < Math.min(goldList.size(), 10); i++) idcg += 1.0 / Math.log(i + 2);
        double ndcg = idcg > 0 ? dcg / idcg : 0;
        return new double[]{recall5, recall10, mrr, ndcg};
    }

    /** Evidence Coverage: gold 章节在 final top-8 中出现的比例。 */
    private static double evidenceCoverage(List<RetrievalResult> results, int[] gold) {
        List<Integer> goldList = new ArrayList<>();
        for (int g : gold) goldList.add(g);
        long hit = results.stream().limit(8)
                .map(r -> r.chapterOrdinal() + 1)
                .filter(goldList::contains)
                .distinct().count();
        return hit / (double) goldList.size();
    }

    private static String answer(LlmProvider llm, String model, List<ChatMessage> ctx, String question) {
        String sys = "你是遮天小说的研究助手。请**只根据提供的上下文**回答下面的问题；如果上下文没有相关信息，明确说“上下文未提供该信息”。不要引用上下文之外的内容。";
        StringBuilder user = new StringBuilder();
        for (ChatMessage m : ctx) user.append(m.role()).append(": ").append(m.content()).append("\n\n");
        user.append("问题：").append(question);
        try {
            LlmResponse resp = llm.complete(new LlmRequest(
                    List.of(ChatMessage.system(sys), ChatMessage.user(user.toString())),
                    500, 0.3, model, TaskType.MEMORY_EXTRACTION));
            return resp == null ? "" : resp.content();
        } catch (Exception e) {
            return "LLM_ERROR: " + e.getMessage();
        }
    }

    /** 简易启发分（0-2）：answer 长度作为粗略代理，报告里按 gold 修正。 */
    private static int quickScore(String answer) {
        if (answer == null || answer.isBlank()) return 0;
        if (answer.contains("上下文未提供") || answer.contains("无法") || answer.contains("没有")) return 1;
        return answer.length() > 60 ? 2 : 1;
    }

    private static String chaptersOf(int[] gold) {
        StringBuilder sb = new StringBuilder();
        for (int g : gold) sb.append(g).append(",");
        return sb.toString();
    }

    private static String fmt(double v) { return String.format("%.2f", v); }

    private static String render(List<RetrievalResult> results) {
        StringBuilder sb = new StringBuilder();
        for (RetrievalResult r : results) {
            sb.append("- ").append(String.format("%.4f", r.score())).append(" ch").append(r.chapterOrdinal() + 1)
                    .append(" ").append(r.memoryType()).append(" ").append(shorten(r.text(), 40)).append("\n");
        }
        return sb.toString();
    }

    private static String renderContext(List<ChatMessage> msgs) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : msgs) sb.append(m.role()).append(": ").append(m.content()).append("\n\n");
        return sb.toString();
    }

    private static String shorten(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
