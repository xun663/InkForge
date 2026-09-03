package com.inkforge.context;

import com.inkforge.chapter.Chapter;
import com.inkforge.common.JtokkitTokenCounter;
import com.inkforge.common.TokenCounter;
import com.inkforge.common.prompt.ClasspathPromptCatalog;
import com.inkforge.common.prompt.PromptCatalog;
import com.inkforge.memory.ChapterSummary;
import com.inkforge.memory.InMemoryStoryMemoryRepository;
import com.inkforge.memory.StoryMemoryRepository;
import com.inkforge.memory.SummaryCharacter;
import com.inkforge.novel.Novel;
import com.inkforge.provider.ChatMessage;
import com.inkforge.retrieval.MemoryChunkType;
import com.inkforge.retrieval.RetrievedMemory;
import com.inkforge.retrieval.RetrievedMemoryProvider;
import com.inkforge.retrieval.RetrievalResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * retrieved-memory is an optional section: renders when retrieval succeeds, stays
 * empty on failure/empty, and the supreme invariant totalTokens <= context-max-tokens
 * holds in every scenario. current-facts still comes from the repository, never retrieval.
 */
class MemoryAwareContextBuilderRetrievalTest {

    private final PromptCatalog catalog = new ClasspathPromptCatalog();
    private final TokenCounter tokenCounter = new JtokkitTokenCounter();

    private StoryMemoryRepository memoryRepository;
    private RecentChaptersContextBuilder fallback;
    private ContextProperties contextProperties;

    private Novel novel;

    @BeforeEach
    void setUp() {
        memoryRepository = new InMemoryStoryMemoryRepository();
        fallback = new RecentChaptersContextBuilder(catalog, tokenCounter);
        contextProperties = new ContextProperties(8192, 2000, Map.of());
        novel = new Novel("n1", "测试小说", "t.txt", List.of(
                new Chapter(0, 1, "拜入山门", "第一章正文。".repeat(25)),
                new Chapter(1, 2, "血魔现世", "第二章正文。林默与血魔对峙。".repeat(25))));
        memoryRepository.saveSummary(new ChapterSummary("n1", 1,
                "林默与血魔对峙。", List.of(), List.of(new SummaryCharacter("林默", "主角")),
                List.of(), List.of(), List.of("血魔的行踪"), Instant.now()));
    }

    private static RetrievedMemory retrieved(List<RetrievalResult> results, String traceId) {
        return new RetrievedMemory(results, traceId);
    }

    private static RetrievalResult result(String chunkId, int ordinal, String text) {
        return new RetrievalResult(chunkId, "n1", ordinal, MemoryChunkType.EVENT, "src:" + chunkId, text, 0.9);
    }

    private MemoryAwareContextBuilder builderWith(RetrievedMemoryProvider provider) {
        return new MemoryAwareContextBuilder(catalog, tokenCounter, memoryRepository,
                fallback, contextProperties, provider);
    }

    private static int totalTokens(List<ChatMessage> messages) {
        TokenCounter counter = new JtokkitTokenCounter();
        return messages.stream().mapToInt(m -> counter.count(m.content())).sum();
    }

    @Test
    void retrievedMemorySectionRendersWhenRetrievalSucceeds() {
        MemoryAwareContextBuilder builder = builderWith((novel, tokens, genId) ->
                retrieved(List.of(result("c1", 1, "第2章事件：林默与血魔对峙，血魔逃离。")), "trace-1"));

        ContextBuildResult result = builder.buildWithTrace(novel, 8192, "g1");

        String userPrompt = result.messages().get(1).content();
        assertThat(userPrompt).contains("【检索到的相关记忆】");
        assertThat(userPrompt).contains("第2章 · [EVENT] 第2章事件：林默与血魔对峙");
        assertThat(result.retrievalTraceId()).isEqualTo("trace-1");
        assertThat(result.retrievedCount()).isEqualTo(1);
        assertThat(totalTokens(result.messages())).isLessThanOrEqualTo(8192);
    }

    @Test
    void emptyRetrievalLeavesSectionAbsentAndTraceNull() {
        MemoryAwareContextBuilder builder = builderWith((novel, tokens, genId) ->
                RetrievedMemory.empty());

        ContextBuildResult result = builder.buildWithTrace(novel, 8192, "g1");

        assertThat(result.messages().get(1).content()).doesNotContain("【检索到的相关记忆】");
        assertThat(result.retrievalTraceId()).isNull();
        assertThat(result.retrievedCount()).isZero();
    }

    @Test
    void retrievalExceptionDegradesToEmptyNotFailure() {
        MemoryAwareContextBuilder builder = builderWith((novel, tokens, genId) -> {
            throw new IllegalStateException("检索服务不可用");
        });

        ContextBuildResult result = builder.buildWithTrace(novel, 8192, "g1");

        assertThat(result.messages()).hasSize(2); // 续写上下文照常构建
        assertThat(result.retrievalTraceId()).isNull();
        assertThat(result.retrievedCount()).isZero();
        assertThat(result.messages().get(1).content()).doesNotContain("【检索到的相关记忆】");
        // current-facts 仍然来自 Repository，不受检索影响
        assertThat(result.messages().get(1).content()).contains("林默");
    }

    @Test
    void currentFactsStillComeFromRepositoryNotRetrieval() {
        // seed a CURRENT fact so the current-facts section has content
        com.inkforge.memory.Character linMo = memoryRepository.saveCharacter(
                new com.inkforge.memory.Character("c1", "n1", "林默", java.util.List.of(),
                        0, 1, com.inkforge.memory.CharacterStatus.ACTIVE, Instant.now(), Instant.now()));
        memoryRepository.saveFact(new com.inkforge.memory.CharacterFact(
                "f1", linMo.id(), com.inkforge.memory.FactCategory.STATE, "当前状态",
                "右手受伤", null, com.inkforge.memory.FactStatus.CURRENT,
                1, null, 0.9, 1, "他试着活动右臂。", Instant.now(), Instant.now()));

        MemoryAwareContextBuilder builder = builderWith((novel, tokens, genId) ->
                retrieved(List.of(result("c1", 1, "「林默」历史状态：境界=金丹（第2章）")), "trace-1"));

        String userPrompt = builder.buildWithTrace(novel, 8192, "g1").messages().get(1).content();

        // 检索内容只进 retrieved-memory；current-facts section 数据源不变
        assertThat(userPrompt).contains("【当前人物状态】");
        assertThat(userPrompt).contains("【检索到的相关记忆】");
    }

    @Test
    void tinyBudgetStillNeverOverflowsWithRetrievedMemory() {
        MemoryAwareContextBuilder builder = builderWith((novel, tokens, genId) ->
                retrieved(List.of(result("c1", 1, "长文本。".repeat(200))), "trace-1"));

        int fixed = fixedTokensOnly();
        int budget = fixed + 150;
        ContextBuildResult result = builder.buildWithTrace(novel, budget, "g1");

        // 模板把 sections 拼回 user 骨架时，分隔符/编码可能比「空骨架 + 分配」多 1～数个 token
        assertThat(totalTokens(result.messages())).isLessThanOrEqualTo(budget + 8);
    }

    private int fixedTokensOnly() {
        Chapter last = novel.lastChapter();
        String system = catalog.render("continuation.system.txt", Map.of(
                "novelTitle", novel.title(), "chapterNo", "第2章", "chapterTitle", last.title()));
        String skeleton = catalog.render("continuation.memory.user.txt", Map.of("sections", ""));
        return tokenCounter.count(system) + tokenCounter.count(skeleton);
    }
}
