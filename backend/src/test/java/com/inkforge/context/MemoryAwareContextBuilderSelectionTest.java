package com.inkforge.context;

import com.inkforge.chapter.Chapter;
import com.inkforge.common.JtokkitTokenCounter;
import com.inkforge.common.TokenCounter;
import com.inkforge.common.prompt.ClasspathPromptCatalog;
import com.inkforge.common.prompt.PromptCatalog;
import com.inkforge.memory.ChapterSummary;
import com.inkforge.memory.InMemoryStoryMemoryRepository;
import com.inkforge.memory.StoryMemoryRepository;
import com.inkforge.novel.Novel;
import com.inkforge.retrieval.MemoryChunkType;
import com.inkforge.retrieval.RetrievedMemory;
import com.inkforge.retrieval.RetrievalResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P5-B3-0：rank-preserving retrieved-memory 选择的回归测试（高排名证据必须优先保留）。
 * 只影响 retrieved-memory 区段；recent-chapters / breakpoint 等时间序区段语义不变（仍由原 fitTail 处理）。
 */
class MemoryAwareContextBuilderSelectionTest {

    private static final String HEADER = "【检索到的相关记忆】";

    private final PromptCatalog catalog = new ClasspathPromptCatalog();
    private final TokenCounter tc = new JtokkitTokenCounter();

    private StoryMemoryRepository memoryRepository;
    private RecentChaptersContextBuilder fallback;
    private ContextProperties contextProperties;
    private Novel novel;

    @BeforeEach
    void setUp() {
        memoryRepository = new InMemoryStoryMemoryRepository();
        fallback = new RecentChaptersContextBuilder(catalog, tc);
        contextProperties = new ContextProperties(8192, 2000, Map.of());
        novel = new Novel("n1", "测试小说", "t.txt", List.of(
                new Chapter(0, 1, "拜入山门", "第一章正文。".repeat(20)),
                new Chapter(1, 2, "血魔现世", "第二章正文。".repeat(20))));
        memoryRepository.saveSummary(new ChapterSummary("n1", 1,
                "林默与血魔对峙。", List.of(), List.of(new com.inkforge.memory.SummaryCharacter("林默", "主角")),
                List.of(), List.of(), List.of("血魔的行踪"), Instant.now()));
    }

    private static RetrievalResult res(String id, int chapter, String text) {
        return new RetrievalResult(id, "n1", chapter - 1, MemoryChunkType.EVENT, "src:" + id, text, 1.0 - chapter / 100.0);
    }

    private MemoryAwareContextBuilder builderWith(List<RetrievalResult> results) {
        return new MemoryAwareContextBuilder(catalog, tc, memoryRepository, fallback, contextProperties,
                (novel, tokens, genId) -> new RetrievedMemory(results, "trace-rp"));
    }

    private int fixedTokensOnly() {
        Chapter last = novel.lastChapter();
        String system = catalog.render("continuation.system.txt", Map.of(
                "novelTitle", novel.title(), "chapterNo", "第2章", "chapterTitle", last.title()));
        String skeleton = catalog.render("continuation.memory.user.txt", Map.of("sections", ""));
        return tc.count(system) + tc.count(skeleton);
    }

    private String retrievedSectionText(MemoryAwareContextBuilder builder, int contextMax) {
        String user = builder.build(novel, contextMax).get(1).content();
        int i = user.indexOf(HEADER);
        assertThat(i).as("retrieved-memory 区段应存在").isGreaterThanOrEqualTo(0);
        int end = user.indexOf("【", i + HEADER.length());
        return end > i ? user.substring(i, end) : user.substring(i);
    }

    private static boolean containsChapter(String text, int chapter) {
        return text.contains("· 第" + chapter + "章 · [");
    }

    // 积分级：给 retrieved-memory 一个 300-token 预算，高排名(rank1/2 gold)必须保留，靠后的不能进来
    @Test
    void retrievedSectionKeepsHighRankGoldNotLowRankTailUnderBudget() {
        List<RetrievalResult> results = new ArrayList<>();
        results.add(res("r1", 1, "第1章 关系证据：林默与庞博结成生死之交，曾共历危难。"));
        results.add(res("r2", 2, "第2章 关系证据：二人于矿洞中互相照应。"));
        for (int c = 3; c <= 10; c++) {
            results.add(res("r" + c, c, "低排名填充噪声，与查询无关，正文较长占预算。".repeat(20)));
        }
        // 复刻 allocate：retrieved-memory 在其前更高优先级 section 吃满后分到 ~300 token
        int context = fixedTokensOnly() + 6912 + 300;
        String text = retrievedSectionText(builderWith(results), context);

        assertThat(containsChapter(text, 1)).as("rank1 gold 必须保留").isTrue();
        assertThat(containsChapter(text, 2)).as("rank2 gold 必须保留").isTrue();
        assertThat(containsChapter(text, 10)).as("靠后低排名噪声不得挤占").isFalse();
    }

    // 静态逻辑：预算内只保序、不重排、放不下即停
    @Test
    void rankPreservingBodyKeepsPrefixOrderUntilBudget() {
        String text = "片段正文。".repeat(8);
        List<RetrievalResult> results = new ArrayList<>();
        for (int c = 1; c <= 8; c++) results.add(res("c" + c, c, text));
        // 预算取"恰好容得下前 3 条 + 省略标记预留"，第 4 条起放不下
        String prefix3 = "· 第1章 · [EVENT] " + text + "\n· 第2章 · [EVENT] " + text + "\n· 第3章 · [EVENT] " + text;
        int budget = tc.count(prefix3) + tc.count("（内容过长，已按上下文预算省略）") + 10;
        String body = MemoryAwareContextBuilder.rankPreservingRetrievedBody(results, budget, tc);
        // 保序前缀：1/2/3 全进（含非首条 marker）
        assertThat(body).contains("· 第1章 · [");
        assertThat(body).contains("· 第2章 · [");
        assertThat(body).contains("· 第3章 · [");
        // 放不下 → 停（第8 不会进）
        assertThat(body).doesNotContain("· 第8章 · [");
    }

    // 超长单条 rank1：兜底截断而非整条丢弃
    @Test
    void oversizedRank1IsTruncatedNotDropped() {
        List<RetrievalResult> results = List.of(res("big", 1, "超长早期关系证据。".repeat(400)));
        int budget = 40;   // 远小于超长 rank1 → 兜底截断被触发
        String body = MemoryAwareContextBuilder.rankPreservingRetrievedBody(results, budget, tc);
        assertThat(body).isNotBlank();                       // 未整体丢弃
        assertThat(body).endsWith("证据。");                   // 其尾部内容仍在
    }

    // 确定性 + 只影响 retrieved-memory（recent-chapters 原文区段仍保最近章节尾部）
    @Test
    void retrievedSelectionIsDeterministic() {
        List<RetrievalResult> results = new ArrayList<>();
        for (int c = 1; c <= 12; c++) results.add(res("c" + c, c, "片段。".repeat(5)));
        MemoryAwareContextBuilder b1 = builderWith(results);
        MemoryAwareContextBuilder b2 = builderWith(results);
        assertThat(retrievedSectionText(b1, 8192)).isEqualTo(retrievedSectionText(b2, 8192));
        assertThat(retrievedSectionText(b1, 8192)).contains("【检索到的相关记忆】");
    }
}
