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
import com.inkforge.retrieval.RetrievalSelectionSim;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * P5-B3-0 Context Selection Diagnostic：拆开 Retrieval Error 与 Context Selection Error。
 *
 * <p>固定项同 P5-B2-2.5：coverage=48 Full Memory、10 条定向 Query/gold、BGE-M3、BM25/Vector top-30、
 * RRF k=60、fusion top-30、rerank-top-30、Query/QueryIntent/Reranker=passthrough/Context 8192/Prompt/LLM
 * 全冻结。只比较同一 top-30 检索输出在两种 <b>Context Selection 策略</b>下的 gold 存活：
 * <ul>
 *   <li><b>A = 当前生产</b>：retrieved-memory 全量拼接后 fitTail（裁头保尾）——见
 *       {@link RetrievalSelectionSim#selectTail}</li>
 *   <li><b>B = Rank-Preserving</b>：按 rank 序累加、放不下即停（保序）——见
 *       {@link RetrievalSelectionSim#selectRankPreserving}</li>
 * </ul>
 *
 * <p>预算：复刻生产 {@code MemoryAwareContextBuilder.allocate}（8192 context、真实 system/user 骨架
 * token、真实 section 配置），得到 retrieved-memory 区段实际分配 R；A/B 在同一 R 上比较（真单变量）。
 *
 * <p>指标：Retrieval EvCov（gold 在 top-30 比例）；Context EvCov（gold 出现在 retrieved-memory 最终
 * 文本比例）；Selection Retention = Context gold / Retrieval gold；Context Selection Loss =
 * Retrieval gold − Context gold。输出 target/e2e/context-selection-ablation/。不改生产。
 */
class ContextSelectionAblation {

    private static final String BGE_BASE = "http://localhost:8085/v1";
    private static final String NOVEL_FILE = "C:/Users/xun/AppData/Local/Temp/inkforge-e2e/zetian_ch1-48.txt";
    private static final int CONTEXT_MAX = 8192;

    // ---- section 配置（application.yml / DEFAULT_SECTIONS，逐值一致）----
    private static final int[][] SECTIONS = {
            {1, 2048, 4096, 1},  // breakpoint-text  required
            {2, 128, 1024, 1},   // breakpoint-memory required
            {3, 0, 1024, 0},     // current-facts
            {4, 0, 768, 0},      // recent-events
            {5, 0, 1024, 0},     // retrieved-memory   ← 我们要的
            {6, 0, 1280, 0},     // recent-chapters
            {7, 0, 512, 0},      // fact-history
            {8, 0, 256, 0},      // older-summaries
    };

    @Test
    void contextSelectionAb() throws Exception {
        Path out = Path.of("target/e2e/context-selection-ablation");
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

        // coverage=48 记忆（一次真实 deepseek 提取；本次只读 retrieval 层，A/B 同源）
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
        HybridRetrievalService hy = new HybridRetrievalService(bm25, vec, new PassThroughReranker(),
                new RetrievalProperties(30, 30, 30, 30, 60, "passthrough", 15, 200));

        // retrieved-memory 区段实际预算 R（复刻生产 allocate，固定部分为 system+user 骨架）
        int fixed = fixedSkeletonTokens(novel, catalog, tc);
        int R = allocateRetrievedTokens(fixed, CONTEXT_MAX);
        int bodyBudgetR = Math.max(0, R - tc.count(RetrievalSelectionSim.SECTION_HEADER + "\n"));
        System.out.println("fixedTokens=" + fixed + " retrieved-memory 区段预算 R=" + R + " bodyBudgetR=" + bodyBudgetR);

        StringBuilder sb = new StringBuilder("# P5-B3-0 Context Selection Diagnostic（Retrieval top-30 → retrieved-memory Context）\n\n");
        sb.append("coverage=48 · 10 条定向 Query · rerank-top-30(=fusion 池)。A=当前 fitTail（裁头保尾）；"
                + "B=Rank-Preserving（保序，放不下即停）。两者同一 top-30、同一 retrieved-memory 区段预算 R="
                + R + " tokens（bodyBudget=" + bodyBudgetR + "）。gold=章节级。\n\n");

        sb.append("| Q | gold | Retrieval∋ | R-rank | A:ctx | B:ctx | SelRet A | SelRet B |\n");
        sb.append("|---|---|---|---|---|---|---|---|\n");

        int tGold = 0, tRetr = 0, tA = 0, tB = 0;
        List<Row> rows = new ArrayList<>();

        for (var q : RetrievalTopKAblation.QUERIES) {
            List<Integer> goldList = new ArrayList<>();
            for (int g : q.gold()) goldList.add(g);
            Set<Integer> goldSet = Set.copyOf(goldList);

            List<RetrievalResult> top30 = hy.retrieve(novel.id(), q.text()); // 生产 top-30（高分在前）
            Map<Integer, Integer> firstRank = firstRanks(top30, goldSet);

            // 预算守卫：若 R 极小（< header）或区段为空，仍要输出空
            Set<Integer> retrPresent = chaptersInResults(top30, goldSet);
            String aText = simTail(top30, bodyBudgetR, tc);
            String bText = simRankPreserving(top30, bodyBudgetR, tc);
            Set<Integer> aCtx = chaptersPresentOf(aText, goldSet);
            Set<Integer> bCtx = chaptersPresentOf(bText, goldSet);

            tGold += goldList.size(); tRetr += retrPresent.size();
            tA += aCtx.size(); tB += bCtx.size();
            rows.add(new Row(q.id(), goldList, top30, firstRank, retrPresent, aText, bText, aCtx, bCtx));

            sb.append(String.format("| %s | %d | %d/%d | %s | %d/%d | %d/%d | %.2f | %.2f |%n",
                    q.id(), goldList.size(), retrPresent.size(), goldList.size(), ranksStr(firstRank, goldList),
                    aCtx.size(), goldList.size(), bCtx.size(), goldList.size(),
                    aCtx.size() / (double) Math.max(1, retrPresent.size()),
                    bCtx.size() / (double) Math.max(1, retrPresent.size())));
        }

        sb.append(String.format("\n## 汇总（10 条 Query；gold 总数 %d）\n\n", tGold));
        sb.append("| 指标 | A（当前 fitTail） | B（Rank-Preserving） |\n|---|---|---|\n");
        sb.append(String.format("| Retrieval EvCov（gold 进 top-30） | %d/%d (%.3f) | %d/%d (%.3f) |%n",
                tRetr, tGold, tRetr / (double) tGold, tRetr, tGold, tRetr / (double) tGold));
        sb.append(String.format("| Context EvCov（gold 进最终 retrieved-memory） | %d/%d (%.3f) | %d/%d (%.3f) |%n",
                tA, tGold, tA / (double) tGold, tB, tGold, tB / (double) tGold));
        sb.append(String.format("| Context Selection Retention（ctx/retr） | %d/%d (%.3f) | %d/%d (%.3f) |%n",
                tA, tRetr, tA / (double) Math.max(1, tRetr), tB, tRetr, tB / (double) Math.max(1, tRetr)));
        sb.append(String.format("| Context Selection Loss（retr−ctx gold） | %d | %d |%n", tRetr - tA, tRetr - tB));

        sb.append(lostDetail(rows));
        sb.append(noiseToken(rows, tc));

        Files.writeString(out.resolve("context-selection-ab.md"), sb.toString());
        System.out.println(sb);
        System.out.println("done → " + out.resolve("context-selection-ab.md"));
    }

    // ===== helpers =====

    private static String simTail(List<RetrievalResult> rs, int bodyBudget, TokenCounter tc) {
        String header = RetrievalSelectionSim.SECTION_HEADER + "\n";
        return header + RetrievalSelectionSim.selectTail(rs, bodyBudget, tc);
    }

    private static String simRankPreserving(List<RetrievalResult> rs, int bodyBudget, TokenCounter tc) {
        String header = RetrievalSelectionSim.SECTION_HEADER + "\n";
        return header + RetrievalSelectionSim.selectRankPreserving(rs, bodyBudget, tc);
    }

    private static Set<Integer> chaptersInResults(List<RetrievalResult> results, Set<Integer> gold) {
        Set<Integer> s = new LinkedHashSet<>();
        for (RetrievalResult r : results) if (gold.contains(r.chapterOrdinal() + 1)) s.add(r.chapterOrdinal() + 1);
        return s;
    }

    private static Set<Integer> chaptersPresentOf(String text, Set<Integer> gold) {
        Set<Integer> s = RetrievalSelectionSim.chaptersPresent(text);
        s.retainAll(gold);
        return s;
    }

    private static Map<Integer, Integer> firstRanks(List<RetrievalResult> results, Set<Integer> gold) {
        Map<Integer, Integer> m = new java.util.LinkedHashMap<>();
        for (int g : gold) m.put(g, -1);
        for (int i = 0; i < results.size(); i++) {
            int ch = results.get(i).chapterOrdinal() + 1;
            if (m.containsKey(ch) && m.get(ch) < 0) m.put(ch, i + 1);
        }
        return m;
    }

    private static String ranksStr(Map<Integer, Integer> firstRank, List<Integer> gold) {
        StringBuilder sb = new StringBuilder();
        for (int g : gold) {
            if (sb.length() > 0) sb.append(",");
            int r = firstRank.getOrDefault(g, -1);
            sb.append("ch").append(g).append("=").append(r < 0 ? "∅" : r);
        }
        return sb.toString();
    }

    /** gold 在 top-30 却未进最终 Context 的逐条明细。 */
    private static String lostDetail(List<Row> rows) {
        StringBuilder sb = new StringBuilder("\n## Gold 丢失明细（A 逐条；B 只列仍丢的）\n\n");
        for (Row r : rows) {
            List<String> lostA = new ArrayList<>(), lostB = new ArrayList<>();
            for (int g : r.gold) {
                if (r.retrPresent.contains(g) && !r.aCtx.contains(g)) {
                    int rank = r.firstRank.getOrDefault(g, -1);
                    lostA.add("ch" + g + "@top" + rank + "(A)");
                }
                if (r.retrPresent.contains(g) && !r.bCtx.contains(g)) {
                    int rank = r.firstRank.getOrDefault(g, -1);
                    lostB.add("ch" + g + "@top" + rank + "(B)");
                }
            }
            if (lostA.isEmpty() && lostB.isEmpty()) continue;
            sb.append("- ").append(r.id).append("：A 丢 [").append(String.join(", ", lostA))
                    .append("]；B 丢 [").append(String.join(", ", lostB)).append("]\n");
        }
        return sb.toString();
    }

    private static String noiseToken(List<Row> rows, TokenCounter tc) {
        StringBuilder sb = new StringBuilder("\n## Context Noise / Token（retrieved-memory 区段最终文本）\n\n");
        sb.append("| Q | A 保留units | B 保留units | A tokens | B tokens |\n");
        sb.append("|---|---|---|---|---|\n");
        int aU = 0, bU = 0;
        long aT = 0, bT = 0;
        for (Row r : rows) {
            int au = RetrievalSelectionSim.chaptersPresent(r.aText).size();
            int bu = RetrievalSelectionSim.chaptersPresent(r.bText).size();
            int at = tc.count(r.aText), bt = tc.count(r.bText);
            aU += au; bU += bu; aT += at; bT += bt;
            sb.append(String.format("| %s | %d | %d | %d | %d |%n", r.id, au, bu, at, bt));
        }
        sb.append(String.format("\n**合计**：A 保留 distinct 章节 %d、tokens %d；B 保留 distinct 章节 %d、tokens %d。%n",
                aU, aT, bU, bT));
        return sb.toString();
    }

    /** system prompt + user 骨架 tokens（与 buildWithMemory 的 fixedTokens 一致）。 */
    private static int fixedSkeletonTokens(Novel novel, PromptCatalog catalog, TokenCounter tc) {
        Chapter last = novel.lastChapter();
        String chapterNo = last.chapterNo() != null ? "第" + last.chapterNo() + "章" : last.title();
        String system = catalog.render("continuation.system.txt", Map.of(
                "novelTitle", novel.title(), "chapterNo", chapterNo, "chapterTitle", last.title()));
        String skeleton = catalog.render("continuation.memory.user.txt", Map.of("sections", ""));
        return tc.count(system) + tc.count(skeleton);
    }

    /** 复刻 MemoryAwareContextBuilder.allocate：返回 retrieved-memory 区段分到的 token 预算。 */
    private static int allocateRetrievedTokens(int fixedTokens, int contextMax) {
        int remaining = contextMax - fixedTokens;
        if (remaining <= 0) return 0;
        Map<Integer, int[]> alloc = new java.util.HashMap<>(); // priority -> [allocated]
        // pass1 required mins in priority order
        for (int[] s : SECTIONS) {
            int prio = s[0], min = s[1];
            if (s[3] == 1) {
                int reserve = Math.min(min, remaining);
                alloc.put(prio, new int[]{reserve});
                remaining -= reserve;
            } else {
                alloc.put(prio, new int[]{0});
            }
        }
        // pass2 top-up in priority order to maxTokens
        for (int[] s : SECTIONS) {
            int prio = s[0], max = s[2];
            int cur = alloc.get(prio)[0];
            int topUp = Math.min(Math.max(0, max - cur), remaining);
            if (topUp > 0) {
                alloc.get(prio)[0] = cur + topUp;
                remaining -= topUp;
            }
        }
        return alloc.get(5)[0];
    }

    private record Row(String id, List<Integer> gold, List<RetrievalResult> top30,
                       Map<Integer, Integer> firstRank, Set<Integer> retrPresent,
                       String aText, String bText, Set<Integer> aCtx, Set<Integer> bCtx) {
    }
}
