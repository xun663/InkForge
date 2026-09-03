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
import com.inkforge.retrieval.RetrievalQuery;
import com.inkforge.retrieval.RetrievalQueryBuilder;
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
import java.util.UUID;

/**
 * P5-0 Memory Coverage A/B：遮天 1-48 章，唯一自变量 = Memory Coverage（3/10/20/48）。
 * 每 Coverage 独立构建自己的 Story Memory（只提取最后 N 章），固定 query(断点48章)/
 * BGE-M3/deepseek-v4-flash/1200 tokens/8192 context。Hidden Gold（真实 49 章后）绝不进入
 * 记忆/检索/prompt。
 *
 * 前置：bge-m3 服务 localhost:8085；deepseek key 从环境变量读取（INKFORGE_LLM_API_KEY 回退 LLM_API_KEY）。
 */
class MemoryCoverageAblation {

    private static final String BGE_BASE = "http://localhost:8085/v1";
    private static final int CONTEXT_MAX = 8192;
    private static final int GEN_TOKENS = 1200;
    private static final String NOVEL_FILE = "C:/Users/xun/AppData/Local/Temp/inkforge-e2e/zetian_ch1-48.txt";
    private static final int[] COVERAGES = {3, 10, 20, 48};

    /** 用 -Dcoverage.filter=48 只跑指定 coverage（支持逗号分隔），便于断点补跑。 */
    private static int[] selectedCoverages() {
        String filter = System.getProperty("coverage.filter");
        if (filter == null || filter.isBlank()) {
            return COVERAGES;
        }
        return java.util.Arrays.stream(filter.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .mapToInt(Integer::parseInt).toArray();
    }

    @Test
    void memoryCoverageAb() throws Exception {
        Path out = Path.of("target/e2e/memory-coverage");
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

        LlmProperties llmProps = new LlmProperties("deepseek", baseUrl, apiKey, model, 300, new LlmProperties.Mock(0));
        LlmProvider deepseek = new OpenAiCompatibleLlmProvider(
                "deepseek", WebClient.builder().baseUrl(baseUrl).build(), llmProps, mapper);
        EmbeddingProperties bgeProps = new EmbeddingProperties("openai-compatible", "BAAI/bge-m3", BGE_BASE, "local", 1024, 16, 120);

        // 加载 1-48 章
        ParsedNovel parsed = new TxtNovelParser(new CharsetDetector(), new ChapterSplitter()).parse(
                Files.readAllBytes(Path.of(NOVEL_FILE)), "zetian_ch1-48.txt");
        List<Chapter> realChapters = dropLeadingPrologue(parsed.chapters());
        Novel novel = new Novel("zetian-48", parsed.title(), "zetian_ch1-48.txt", realChapters);
        System.out.println("=== 遮天 1-48 章，真实章节数=" + realChapters.size() + "，断点=" + display(realChapters.get(realChapters.size()-1)) + " ===");

        // 固定 query（基于断点章，所有 coverage 一致）
        List<RetrievalQuery> queries = new RetrievalQueryBuilder(seedEmptyMemory()).build(novel);
        System.out.println("固定 query 数: " + queries.size());

        StringBuilder summary = new StringBuilder("# Memory Coverage A/B（遮天 1-48 章）\n\n");
        summary.append("唯一变量 = Memory Coverage（最后 N 章进入记忆构建）。固定：query(断点48章)/BGE-M3/deepseek-v4-flash/1200 tokens/8192 context。\n\n");

        for (int cov : selectedCoverages()) {
            Path covDir = out.resolve(cov + "-chapters");
            Files.createDirectories(covDir);

            // 每 coverage 独立建记忆：只提取最后 cov 章
            StoryMemoryRepository memRepo = new InMemoryStoryMemoryRepository();
            MemoryChunkRepository chunkRepo = new InMemoryMemoryChunkRepository();
            MemoryExtractor extractor = new MemoryExtractor(deepseek, catalog, tokenCounter,
                    new ExtractionValidator(), extractionProps, mapper);
            MemoryUpdateService update = new MemoryUpdateService(memRepo, extractionProps);
            MemoryChunkProjectionService projection = new MemoryChunkProjectionService(memRepo, chunkRepo);

            List<Chapter> slice = realChapters.subList(Math.max(0, realChapters.size() - cov), realChapters.size());
            int ok = 0, fail = 0;
            for (Chapter ch : slice) {
                MemoryExtractor.ExtractionOutcome outcome = extractor.extract(ch, display(ch));
                if (outcome.result() != null) {
                    update.apply(novel.id(), ch, outcome.result());
                    projection.projectChapter(novel.id(), ch.ordinal());
                    ok++;
                } else {
                    fail++;
                    System.out.println("  [cov=" + cov + " FAILED] " + display(ch) + " " + outcome.errorMessage());
                }
            }

            // 嵌入（BGE-M3，固定）
            OpenAiCompatibleEmbeddingProvider bgeProv = new OpenAiCompatibleEmbeddingProvider(
                    "openai-compatible", WebClient.builder().baseUrl(BGE_BASE).build(), bgeProps, mapper);
            InMemoryChunkEmbeddingStore store = new InMemoryChunkEmbeddingStore();
            new MemoryEmbeddingService(bgeProv, chunkRepo, store, bgeProps).embedNovel(novel.id());

            // 检索（固定 query）
            LuceneBm25Retriever bm25 = new LuceneBm25Retriever(chunkRepo);
            InMemoryVectorRetriever vec = new InMemoryVectorRetriever(bgeProv, chunkRepo, store, bgeProps);
            HybridRetrievalService hybrid = new HybridRetrievalService(bm25, vec, new PassThroughReranker(), rp);
            RetrievedMemory rm = retrieveAll(novel.id(), queries, hybrid);

            // 保存 memory/trace
            int chars = memRepo.findCharacters(novel.id()).size();
            int events = memRepo.findEvents(novel.id(), Integer.MAX_VALUE, false).size();
            int facts = memRepo.findCharacters(novel.id()).stream()
                    .mapToInt(c -> memRepo.findFacts(c.id()).size()).sum();
            int chunks = chunkRepo.findByNovelId(novel.id()).size();
            StringBuilder mem = new StringBuilder();
            mem.append("# coverage ").append(cov).append("\n\n章节处理: 成功").append(ok).append(" 失败").append(fail)
                    .append("/").append(slice.size()).append("\n人物 ").append(chars).append(" 事实 ").append(facts)
                    .append(" 事件 ").append(events).append(" chunk ").append(chunks).append("\n");
            Files.writeString(covDir.resolve("memory.json"), mem.toString());
            Files.writeString(covDir.resolve("trace.json"), "# Final Retrieval\n" + render(rm.results()));

            // 上下文
            RetrievedMemoryProvider provider = (n, t, g) -> rm;
            MemoryAwareContextBuilder builder = new MemoryAwareContextBuilder(catalog, tokenCounter, memRepo,
                    new RecentChaptersContextBuilder(catalog, tokenCounter), contextProps, provider);
            List<ChatMessage> ctx = builder.build(novel, CONTEXT_MAX);
            Files.writeString(covDir.resolve("context.txt"), renderContext(ctx));

            // 生成
            String gen = deepseek.complete(new LlmRequest(ctx, GEN_TOKENS, 0.8, model, TaskType.CONTINUATION)).content();
            Files.writeString(covDir.resolve("generation.txt"), gen == null ? "" : gen);

            summary.append("## coverage ").append(cov).append("\n")
                    .append("- 章节成功/失败: ").append(ok).append("/").append(fail)
                    .append("；人物 ").append(chars).append(" 事实 ").append(facts).append(" 事件 ").append(events)
                    .append(" chunk ").append(chunks).append("\n")
                    .append("- final 检索条数: ").append(rm.results().size())
                    .append("；检索命中最早章节: ").append(rm.results().stream().mapToInt(RetrievalResult::chapterOrdinal).min().orElse(-1))
                    .append("\n\n");
            System.out.println("  [cov=" + cov + "] 人物=" + chars + " 事实=" + facts + " 事件=" + events
                    + " chunk=" + chunks + " final=" + rm.results().size()
                    + " 最早检索章节=" + rm.results().stream().mapToInt(RetrievalResult::chapterOrdinal).min().orElse(-1));
        }
        Files.writeString(out.resolve("summary.md"), summary.toString());
    }

    private static List<Chapter> dropLeadingPrologue(List<Chapter> chapters) {
        if (!chapters.isEmpty() && chapters.get(0).chapterNo() == null) {
            return chapters.subList(1, chapters.size());
        }
        return chapters;
    }

    private static StoryMemoryRepository seedEmptyMemory() {
        return new InMemoryStoryMemoryRepository();
    }

    private static RetrievedMemory retrieveAll(String novelId, List<RetrievalQuery> queries, HybridRetrievalService hybrid) {
        Map<String, RetrievalResult> best = new LinkedHashMap<>();
        for (RetrievalQuery q : queries) {
            if (q.text() == null || q.text().isBlank()) continue;
            for (RetrievalResult r : hybrid.retrieve(novelId, q.text())) {
                best.merge(r.chunkId(), r, (a, b) -> a.score() >= b.score() ? a : b);
            }
        }
        List<RetrievalResult> merged = best.values().stream()
                .sorted(Comparator.comparingDouble(RetrievalResult::score).reversed())
                .toList();
        return new RetrievedMemory(merged, UUID.randomUUID().toString());
    }

    private static String render(List<RetrievalResult> results) {
        StringBuilder sb = new StringBuilder();
        for (RetrievalResult r : results) {
            sb.append("- ").append(String.format("%.4f", r.score())).append(" ch").append(r.chapterOrdinal())
                    .append(" ").append(r.memoryType()).append(" ").append(shorten(r.text(), 50)).append("\n");
        }
        return sb.toString();
    }

    private static String renderContext(List<ChatMessage> msgs) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : msgs) {
            sb.append("【").append(m.role()).append("】\n").append(m.content()).append("\n\n");
        }
        return sb.toString();
    }

    private static String display(Chapter chapter) {
        return chapter.chapterNo() != null ? "第" + chapter.chapterNo() + "章" : chapter.title();
    }

    private static String shorten(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
