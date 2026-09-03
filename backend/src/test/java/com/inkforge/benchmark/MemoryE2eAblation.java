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
import com.inkforge.retrieval.QueryIntentClassifier;
import com.inkforge.retrieval.RetrievalProperties;
import com.inkforge.retrieval.RetrievalQuery;
import com.inkforge.retrieval.RetrievalQueryBuilder;
import com.inkforge.retrieval.RetrievalResult;
import com.inkforge.retrieval.RetrievalSelectionSim;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * P5-C End-to-End Memory ON/OFF A/B（原创故事，可控：模型/Prompt/可见前缀/生成参数 全同，唯一变量 = Story Memory）。
 *
 * <p>3 篇原创短篇（剑断长夜 / 雾港迷案 / 古卷残页，均已存在为原创 fixture），每篇 ≥2 个断点 → ≥6 cases。
 * 每个 case：可见前缀 = 前 cutoff 章；Memory 只由 ≤cutoff 的章节构建（绝无未来信息泄漏）。
 *
 * <ul>
 *   <li>OFF = 同一 continuation.memory.user 模板，sections 只含最近两章原文（可见近期）——不调用任何检索/记忆。</li>
 *   <li>ON  = 同一模板 + 【检索到的相关记忆】：生产冻结管线（QueryBuilder/QueryIntent → BM25(30)+BGE Vector(30)
 *        → RRF(k=60) → Fusion(30) → PassThrough → top-30），合并后按 Rank-Preserving 写入 ~989-token 区段预算。</li>
 * </ul>
 * 两者 system prompt、user 模板、温度/最大输出/模型完全一致。生成仅一次（temp 0.8 无 seed，样本小如实说明）。
 *
 * <p>产物写 target/e2e/memory-e2e-ablation/…/memory-off|on/{context.txt,generation.txt,metadata.json}，on/{trace.json}。
 * 纯实验：不改任何生产代码。
 */
class MemoryE2eAblation {

    private static final String BGE_BASE = "http://localhost:8085/v1";
    private static final String DIR = "C:/Users/xun/AppData/Local/Temp/inkforge-e2e";
    private static final int CONTEXT_MAX = 8192;
    private static final int GEN_TOKENS = 1000;
    private static final double TEMP = 0.8;

    private static final int[][] SECTIONS = {
            {1, 2048, 4096, 1}, {2, 128, 1024, 1}, {3, 0, 1024, 0}, {4, 0, 768, 0},
            {5, 0, 1024, 0}, {6, 0, 1280, 0}, {7, 0, 512, 0}, {8, 0, 256, 0},
    };

    @Test
    void memoryOnOffE2e() throws Exception {
        Path root = Path.of("target/e2e/memory-e2e-ablation");
        Files.createDirectories(root);

        String apiKey = System.getenv("INKFORGE_LLM_API_KEY");
        if (apiKey == null || apiKey.isBlank()) apiKey = System.getenv("LLM_API_KEY");
        if (apiKey == null || apiKey.isBlank()) throw new IllegalStateException("no deepseek key");
        String model = System.getenv().getOrDefault("INKFORGE_LLM_MODEL", "deepseek-v4-flash");
        String baseUrl = System.getenv().getOrDefault("INKFORGE_LLM_BASE_URL", "https://api.deepseek.com");

        ObjectMapper om = new ObjectMapper();
        TokenCounter tc = new JtokkitTokenCounter();
        PromptCatalog catalog = new ClasspathPromptCatalog();
        MemoryExtractionProperties ep = new MemoryExtractionProperties(3, 12000, 2048, 4096, 0.2, 2, 0.7, 300, 200);
        ContextProperties cp = new ContextProperties(CONTEXT_MAX, 2000, Map.of());
        EmbeddingProperties bgeProps = new EmbeddingProperties("openai-compatible", "BAAI/bge-m3", BGE_BASE, "local", 1024, 16, 120);
        LlmProvider deepseek = new OpenAiCompatibleLlmProvider("deepseek",
                WebClient.builder().baseUrl(baseUrl).build(),
                new LlmProperties("deepseek", baseUrl, apiKey, model, 300, new LlmProperties.Mock(0)), om);
        OpenAiCompatibleEmbeddingProvider bge = new OpenAiCompatibleEmbeddingProvider(
                "openai-compatible", WebClient.builder().baseUrl(BGE_BASE).build(), bgeProps, om);

        // story-id -> {full file, [cutoffs]}
        List<Object[]> cases = List.of(
                new Object[]{"story1_剑断长夜", "story1_full.txt", new int[]{5, 7}},
                new Object[]{"story2_雾港迷案", "story2_full.txt", new int[]{4, 6}},
                new Object[]{"story3_古卷残页", "story3_full.txt", new int[]{5, 7}});

        int nCase = 0;
        for (Object[] s : cases) {
            String name = (String) s[0];
            String file = (String) s[1];
            int[] cutoffs = (int[]) s[2];
            ParsedNovel parsed = new TxtNovelParser(new CharsetDetector(), new ChapterSplitter()).parse(
                    Files.readAllBytes(Path.of(DIR, file)), file);
            List<Chapter> all = new ArrayList<>(parsed.chapters());
            if (!all.isEmpty() && all.get(0).chapterNo() == null) all = all.subList(1, all.size());

            for (int cutoff : cutoffs) {
                nCase++;
                List<Chapter> prefix = all.subList(0, cutoff);
                Novel novel = new Novel(name, parsed.title(), file, prefix);
                System.out.println("\n== case " + nCase + " " + name + " cutoff=" + cutoff
                        + " (prefix chapters " + prefix.size() + ") ==");

                // 只构建 ≤cutoff 的 Memory
                StoryMemoryRepository memRepo = new InMemoryStoryMemoryRepository();
                MemoryChunkRepository chunkRepo = new InMemoryMemoryChunkRepository();
                MemoryExtractor ex = new MemoryExtractor(deepseek, catalog, tc, new ExtractionValidator(), ep, om);
                MemoryUpdateService up = new MemoryUpdateService(memRepo, ep);
                MemoryChunkProjectionService proj = new MemoryChunkProjectionService(memRepo, chunkRepo);
                int ok = 0;
                for (Chapter ch : prefix) {
                    var o = ex.extract(ch, display(ch));
                    if (o.result() != null) { up.apply(novel.id(), ch, o.result()); proj.projectChapter(novel.id(), ch.ordinal()); ok++; }
                }
                System.out.println("  memory chapters ok=" + ok + "/" + prefix.size());

                InMemoryChunkEmbeddingStore store = new InMemoryChunkEmbeddingStore();
                new MemoryEmbeddingService(bge, chunkRepo, store, bgeProps).embedNovel(novel.id());
                LuceneBm25Retriever bm25 = new LuceneBm25Retriever(chunkRepo);
                InMemoryVectorRetriever vec = new InMemoryVectorRetriever(bge, chunkRepo, store, bgeProps);
                RetrievalProperties rp = new RetrievalProperties(30, 30, 30, 30, 60, "passthrough", 15, 200);
                HybridRetrievalService hy = new HybridRetrievalService(bm25, vec, new PassThroughReranker(), rp);
                List<RetrievalQuery> queries = new RetrievalQueryBuilder(memRepo).build(novel);
                List<RetrievalResult> finals = mergeFinals(novel.id(), queries, hy);

                int fixed = fixedTokens(novel, catalog, tc);
                int R = allocateRetrieved(fixed, CONTEXT_MAX);
                int bodyBudget = Math.max(0, R - tc.count(RetrievalSelectionSim.SECTION_HEADER + "\n"));

                // 可见近期 = 最近两章原文（两 arm 相同）
                String recent = recentBlock(prefix);
                String retrievedBlock = finals.isEmpty() ? ""
                        : RetrievalSelectionSim.SECTION_HEADER + "\n" + RetrievalSelectionSim.selectRankPreserving(finals, bodyBudget, tc);

                String system = catalog.render("continuation.system.txt", Map.of(
                        "novelTitle", novel.title(), "chapterNo", String.valueOf(cutoff), "chapterTitle", prefix.get(cutoff - 1).title()));
                String offUser = catalog.render("continuation.memory.user.txt", Map.of("sections", recent));
                String onUser = catalog.render("continuation.memory.user.txt", Map.of(
                        "sections", retrievedBlock.isBlank() ? recent : recent + "\n\n" + retrievedBlock));

                Path caseDir = root.resolve(name + "/cutoff-" + cutoff);
                writeArm(caseDir.resolve("memory-off"), system, offUser, novel, tc, om, model, deepseek);
                writeArm(caseDir.resolve("memory-on"), system, onUser, novel, tc, om, model, deepseek);
                Files.writeString(caseDir.resolve("memory-on/trace.txt"),
                        "retrieved finals=" + finals.size() + " (bodyBudget=" + bodyBudget + ")\n" + render(finals));
            }
        }
        System.out.println("\ndone " + nCase + " cases → " + root);
    }

    private static void writeArm(Path arm, String system, String user, Novel novel, TokenCounter tc,
                                 ObjectMapper om, String model, LlmProvider llm) throws Exception {
        Files.createDirectories(arm);
        List<ChatMessage> msgs = List.of(ChatMessage.system(system), ChatMessage.user(user));
        Files.writeString(arm.resolve("context.txt"), "【system】\n" + system + "\n\n【user】\n" + user);

        LlmResponse resp = llm.complete(new LlmRequest(msgs, GEN_TOKENS, TEMP, model, TaskType.CONTINUATION));
        String gen = resp == null || resp.content() == null ? "" : resp.content();
        Files.writeString(arm.resolve("generation.txt"), gen);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("model", model);
        meta.put("temperature", TEMP);
        meta.put("maxTokens", GEN_TOKENS);
        meta.put("seed", null);
        meta.put("systemPromptHash", sha256(system));
        meta.put("userContextHash", sha256(user));
        Files.writeString(arm.resolve("metadata.json"), om.writerWithDefaultPrettyPrinter().writeValueAsString(meta));
        System.out.println("  wrote " + arm.getFileName() + " genTokens=" + tc.count(gen));
    }

    private static List<RetrievalResult> mergeFinals(String novelId, List<RetrievalQuery> queries, HybridRetrievalService hy) {
        Map<String, RetrievalResult> best = new LinkedHashMap<>();
        for (RetrievalQuery q : queries) {
            if (q.text() == null || q.text().isBlank()) continue;
            for (RetrievalResult r : hy.retrieve(novelId, q.text())) best.merge(r.chunkId(), r, (a, b) -> a.score() >= b.score() ? a : b);
        }
        return best.values().stream().sorted(Comparator.comparingDouble(RetrievalResult::score).reversed()).toList();
    }

    private static String recentBlock(List<Chapter> prefix) {
        StringBuilder sb = new StringBuilder();
        int from = Math.max(0, prefix.size() - 2);
        for (int i = from; i < prefix.size(); i++) {
            Chapter ch = prefix.get(i);
            String no = ch.chapterNo() != null ? "第" + ch.chapterNo() + "章" : ch.title();
            String t = ch.title() == null ? "" : ch.title();
            sb.append("【").append(no).append(t.isBlank() ? "" : " " + t).append("】\n").append(ch.content()).append("\n");
        }
        return sb.toString().trim();
    }

    private static int fixedTokens(Novel novel, PromptCatalog catalog, TokenCounter tc) {
        Chapter last = novel.lastChapter();
        String system = catalog.render("continuation.system.txt", Map.of(
                "novelTitle", novel.title(), "chapterNo", String.valueOf(last.chapterNo()),
                "chapterTitle", last.title() == null ? "" : last.title()));
        String skeleton = catalog.render("continuation.memory.user.txt", Map.of("sections", ""));
        return tc.count(system) + tc.count(skeleton);
    }

    private static int allocateRetrieved(int fixedTokens, int contextMax) {
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

    private static String render(List<RetrievalResult> results) {
        StringBuilder sb = new StringBuilder();
        for (RetrievalResult r : results) {
            sb.append(String.format("%.4f ch%s %s ", r.score(), r.chapterOrdinal() + 1, r.memoryType()))
                    .append(r.text() == null ? "" : r.text().length() > 60 ? r.text().substring(0, 60) + "…" : r.text()).append("\n");
        }
        return sb.toString();
    }

    private static String display(Chapter chapter) {
        return chapter.chapterNo() != null ? "第" + chapter.chapterNo() + "章" : chapter.title();
    }

    private static String sha256(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
