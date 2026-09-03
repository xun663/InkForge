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
import com.inkforge.provider.MockEmbeddingProvider;
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
 * Continuation A/B（严格控制变量）：3 篇原创短篇，每篇 Visible Prefix 建记忆一次（deepseek）
 * → 投影 chunks 一次 → 同一批 chunk 分别 Mock/BGE-M3 嵌入 → 同一 query 检索 → 同一
 * MemoryAwareContextBuilder（仅 retrieved-memory 段不同）→ 同一 deepseek 生成两版续写。
 * Hidden gold 绝不进入记忆/检索/prompt。
 *
 * 前置：本地 bge-m3 服务 localhost:8085；deepseek key 从环境变量读取
 * （INKFORGE_LLM_API_KEY，回退 LLM_API_KEY）。key 不写任何文件/日志。
 */
class E2eContinuationAblation {

    private static final String BGE_BASE = "http://localhost:8085/v1";
    private static final int CONTEXT_MAX = 8192;
    private static final int GEN_TOKENS = 1200;
    private static final String DIR = "C:/Users/xun/AppData/Local/Temp/inkforge-e2e";

    @Test
    void continuationAbMockVsBge() throws Exception {
        Path out = Path.of("target/e2e/embedding-ablation/continuation");
        Files.createDirectories(out);

        String apiKey = System.getenv("INKFORGE_LLM_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getenv("LLM_API_KEY");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("未找到 deepseek API key（设置 INKFORGE_LLM_API_KEY 或 LLM_API_KEY）");
        }
        String model = System.getenv().getOrDefault("INKFORGE_LLM_MODEL", "deepseek-v4-flash");
        String baseUrl = System.getenv().getOrDefault("INKFORGE_LLM_BASE_URL", "https://api.deepseek.com");

        ObjectMapper mapper = new ObjectMapper();
        TokenCounter tokenCounter = new JtokkitTokenCounter();
        PromptCatalog catalog = new ClasspathPromptCatalog();
        MemoryExtractionProperties extractionProps =
                new MemoryExtractionProperties(3, 12000, 2048, 4096, 0.2, 2, 0.7, 300, 200);
        ContextProperties contextProps = new ContextProperties(CONTEXT_MAX, 2000, Map.of());
        RetrievalProperties rp = new RetrievalProperties(30, 30, 30, 8, 60, "passthrough", 15, 200);

        // deepseek LLM（供提取 + 生成，key 只从环境变量读）
        LlmProperties llmProps = new LlmProperties("deepseek", baseUrl, apiKey, model, 300, new LlmProperties.Mock(0));
        LlmProvider deepseek = new OpenAiCompatibleLlmProvider(
                "deepseek", WebClient.builder().baseUrl(baseUrl).build(), llmProps, mapper);

        // 三个故事：可见前缀（截断版）→ 建记忆一次 → A/B
        List<String[]> stories = List.of(
                new String[]{"story1_剑断长夜", "story1_truncated.txt", "story1_full.txt"},
                new String[]{"story2_雾港迷案", "story2_truncated.txt", "story2_full.txt"},
                new String[]{"story3_古卷残页", "story3_truncated.txt", "story3_full.txt"});

        StringBuilder summary = new StringBuilder("# Continuation A/B（Mock vs BGE-M3）\n\n");
        summary.append("3 篇原创短篇 · Visible Prefix 建记忆一次 · 同一 chunk/query/LLM(deepseek-v4-flash)/context-budget(8192) · 唯一变量 Embedding。\n\n");

        for (String[] s : stories) {
            String name = s[0];
            String truncatedFile = s[1];
            String fullFile = s[2];
            Path storyDir = out.resolve(name);
            Files.createDirectories(storyDir);

            // 1. 加载可见前缀
            ParsedNovel parsed = new TxtNovelParser(new CharsetDetector(), new ChapterSplitter()).parse(
                    Files.readAllBytes(Path.of(DIR, truncatedFile)), truncatedFile);
            Novel novel = new Novel("story-" + name, parsed.title(), truncatedFile, parsed.chapters());
            System.out.println("\n===== " + name + " 章节数=" + novel.chapterCount() + " =====");

            // 2. 建记忆一次（deepseek 提取 + apply + 投影）
            StoryMemoryRepository memoryRepo = new InMemoryStoryMemoryRepository();
            MemoryChunkRepository chunkRepo = new InMemoryMemoryChunkRepository();
            MemoryExtractor extractor = new MemoryExtractor(deepseek, catalog, tokenCounter,
                    new ExtractionValidator(), extractionProps, mapper);
            MemoryUpdateService update = new MemoryUpdateService(memoryRepo, extractionProps);
            MemoryChunkProjectionService projection = new MemoryChunkProjectionService(memoryRepo, chunkRepo);

            int okChapters = 0;
            for (Chapter ch : novel.chapters()) {
                MemoryExtractor.ExtractionOutcome outcome = extractor.extract(ch, display(ch));
                if (outcome.result() != null) {
                    update.apply(novel.id(), ch, outcome.result());
                    projection.projectChapter(novel.id(), ch.ordinal());
                    okChapters++;
                } else {
                    System.out.println("  [extract FAILED] " + display(ch) + " " + outcome.errorMessage());
                }
            }
            System.out.println("  建记忆成功章节: " + okChapters + "/" + novel.chapterCount());
            StringBuilder mem = new StringBuilder();
            mem.append("# ").append(name).append(" 记忆\n\n章节成功: ").append(okChapters)
                    .append("/").append(novel.chapterCount()).append("\n人物: ")
                    .append(memoryRepo.findCharacters(novel.id()).stream().map(c -> c.name()).toList())
                    .append("\n事件数: ").append(memoryRepo.findEvents(novel.id(), 100, true).size()).append("\n");
            Files.writeString(storyDir.resolve("memory.md"), mem);

            // 3. 两个 embedding（同一批 chunk）
            EmbeddingProperties mockProps = new EmbeddingProperties("mock", "bge-m3", "https://unused", "", 1024, 16, 120);
            MockEmbeddingProvider mockProv = new MockEmbeddingProvider(mockProps);
            InMemoryChunkEmbeddingStore storeMock = new InMemoryChunkEmbeddingStore();
            new MemoryEmbeddingService(mockProv, chunkRepo, storeMock, mockProps).embedNovel(novel.id());
            InMemoryVectorRetriever vecMock = new InMemoryVectorRetriever(mockProv, chunkRepo, storeMock, mockProps);

            EmbeddingProperties bgeProps = new EmbeddingProperties("openai-compatible", "BAAI/bge-m3", BGE_BASE, "local", 1024, 16, 120);
            OpenAiCompatibleEmbeddingProvider bgeProv = new OpenAiCompatibleEmbeddingProvider(
                    "openai-compatible", WebClient.builder().baseUrl(BGE_BASE).build(), bgeProps, mapper);
            InMemoryChunkEmbeddingStore storeBge = new InMemoryChunkEmbeddingStore();
            new MemoryEmbeddingService(bgeProv, chunkRepo, storeBge, bgeProps).embedNovel(novel.id());
            InMemoryVectorRetriever vecBge = new InMemoryVectorRetriever(bgeProv, chunkRepo, storeBge, bgeProps);

            LuceneBm25Retriever bm25 = new LuceneBm25Retriever(chunkRepo);
            HybridRetrievalService hMock = new HybridRetrievalService(bm25, vecMock, new PassThroughReranker(), rp);
            HybridRetrievalService hBge = new HybridRetrievalService(bm25, vecBge, new PassThroughReranker(), rp);
            RetrievalQueryBuilder qb = new RetrievalQueryBuilder(memoryRepo);
            List<RetrievalQuery> queries = qb.build(novel);

            // 4. 同一 query 两条件检索（合并 final，模拟 DefaultRetrievedMemoryProvider）
            RetrievedMemory rmMock = retrieveAll(novel.id(), queries, hMock);
            RetrievedMemory rmBge = retrieveAll(novel.id(), queries, hBge);
            Files.writeString(storyDir.resolve("trace-mock.md"), "# Mock Final Retrieval\n" + render(rmMock.results()));
            Files.writeString(storyDir.resolve("trace-bge.md"), "# BGE Final Retrieval\n" + render(rmBge.results()));

            // 5. 同一 context builder，仅 retrieved-memory 段不同
            List<ChatMessage> ctxMock = buildContext(novel, catalog, tokenCounter, memoryRepo, contextProps, rmMock);
            List<ChatMessage> ctxBge = buildContext(novel, catalog, tokenCounter, memoryRepo, contextProps, rmBge);
            Files.writeString(storyDir.resolve("context-mock.md"), renderContext(ctxMock));
            Files.writeString(storyDir.resolve("context-bge.md"), renderContext(ctxBge));

            // 6. 同一 deepseek 生成两版
            String genMock = generate(deepseek, ctxMock, model);
            String genBge = generate(deepseek, ctxBge, model);
            Files.writeString(storyDir.resolve("generation-mock.txt"), genMock);
            Files.writeString(storyDir.resolve("generation-bge.txt"), genBge);

            summary.append("## ").append(name).append("\n");
            summary.append("- 记忆章节: ").append(okChapters).append("/").append(novel.chapterCount()).append("\n");
            summary.append("- Mock final 检索条数: ").append(rmMock.results().size())
                    .append("；BGE final 检索条数: ").append(rmBge.results().size()).append("\n");
            summary.append("- 两条件 final 集合相同? ").append(sameSet(rmMock.results(), rmBge.results())).append("\n");
            System.out.println("  Mock final=" + rmMock.results().size() + " BGE final=" + rmBge.results().size()
                    + " 集合相同=" + sameSet(rmMock.results(), rmBge.results()));
        }
        Files.writeString(out.resolve("summary.md"), summary.toString());
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

    private static List<ChatMessage> buildContext(Novel novel, PromptCatalog catalog, TokenCounter tc,
                                                  StoryMemoryRepository memRepo, ContextProperties props,
                                                  RetrievedMemory rm) {
        RetrievedMemoryProvider provider = (n, tokens, g) -> rm;
        MemoryAwareContextBuilder builder = new MemoryAwareContextBuilder(
                catalog, tc, memRepo, new RecentChaptersContextBuilder(catalog, tc), props, provider);
        return builder.build(novel, CONTEXT_MAX);
    }

    private static String generate(LlmProvider llm, List<ChatMessage> ctx, String model) {
        LlmResponse resp = llm.complete(new LlmRequest(ctx, GEN_TOKENS, 0.8, model, TaskType.CONTINUATION));
        return resp == null || resp.content() == null ? "" : resp.content();
    }

    private static String render(List<RetrievalResult> results) {
        StringBuilder sb = new StringBuilder();
        for (RetrievalResult r : results) {
            sb.append("- ").append(String.format("%.4f", r.score())).append(" ch").append(r.chapterOrdinal())
                    .append(" ").append(r.memoryType()).append(" ").append(shorten(r.text(), 60)).append("\n");
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

    private static boolean sameSet(List<RetrievalResult> a, List<RetrievalResult> b) {
        if (a.size() != b.size()) return false;
        return a.stream().map(RetrievalResult::chunkId).collect(java.util.stream.Collectors.toSet())
                .equals(b.stream().map(RetrievalResult::chunkId).collect(java.util.stream.Collectors.toSet()));
    }

    private static String display(Chapter chapter) {
        return chapter.chapterNo() != null ? "第" + chapter.chapterNo() + "章" : chapter.title();
    }

    private static String shorten(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
