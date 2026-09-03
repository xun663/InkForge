package com.inkforge.context;

import com.inkforge.chapter.Chapter;
import com.inkforge.common.JtokkitTokenCounter;
import com.inkforge.common.TokenCounter;
import com.inkforge.common.prompt.ClasspathPromptCatalog;
import com.inkforge.common.prompt.PromptCatalog;
import com.inkforge.memory.Character;
import com.inkforge.memory.CharacterFact;
import com.inkforge.memory.CharacterStatus;
import com.inkforge.memory.ChapterSummary;
import com.inkforge.memory.FactCategory;
import com.inkforge.memory.FactStatus;
import com.inkforge.memory.InMemoryStoryMemoryRepository;
import com.inkforge.memory.StoryEvent;
import com.inkforge.memory.StoryMemoryRepository;
import com.inkforge.memory.SummaryCharacter;
import com.inkforge.novel.Novel;
import com.inkforge.provider.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The supreme invariant here: totalTokens <= context-max-tokens in EVERY scenario,
 * including tiny budgets where required sections must be compressed.
 */
class MemoryAwareContextBuilderTest {

    private final PromptCatalog catalog = new ClasspathPromptCatalog();
    private final TokenCounter tokenCounter = new JtokkitTokenCounter();

    private StoryMemoryRepository memoryRepository;
    private MemoryAwareContextBuilder builder;

    private Novel novel;

    @BeforeEach
    void setUp() {
        memoryRepository = new InMemoryStoryMemoryRepository();
        RecentChaptersContextBuilder fallback = new RecentChaptersContextBuilder(catalog, tokenCounter);
        builder = new MemoryAwareContextBuilder(catalog, tokenCounter, memoryRepository, fallback,
                new ContextProperties(8192, 2000, Map.of()));
        novel = new Novel("n1", "测试小说", "t.txt", List.of(
                new Chapter(0, 1, "拜入山门", "第一章正文。".repeat(25)),
                new Chapter(1, 2, "玄霜剑", "第二章正文。".repeat(25)),
                new Chapter(2, 3, "血魔现世", "第三章正文。林默与血魔对峙。".repeat(25))));
    }

    private void seedMemory() {
        Instant now = Instant.now();
        memoryRepository.saveSummary(new ChapterSummary("n1", 2, "林默与血魔对峙，右手受伤，血魔逃离。",
                List.of("对峙"), List.of(new SummaryCharacter("林默", "主角")),
                List.of("后山"), List.of(), List.of("血魔的行踪"), now));
        Character linMo = memoryRepository.saveCharacter(new Character(
                "c1", "n1", "林默", List.of(), 0, 2, CharacterStatus.ACTIVE, now, now));
        memoryRepository.saveFact(new CharacterFact("f1", "c1", FactCategory.STATE, "当前状态",
                "右手受伤", null, FactStatus.CURRENT, 2, null, 0.9, 2, "对峙。", now, now));
        memoryRepository.saveFact(new CharacterFact("f2", "c1", FactCategory.ABILITY, "境界",
                "金丹", null, FactStatus.SUPERSEDED, 1, 2, 0.9, 1, "第一章。", now, now));
        memoryRepository.saveEvent(new StoryEvent("e1", "n1", 2, "后山对峙",
                "林默与血魔对峙。", List.of("林默", "血魔"), "后山", List.of(), 4, "对峙。", now));
    }

    private static int totalTokens(List<ChatMessage> messages) {
        TokenCounter counter = new JtokkitTokenCounter();
        return messages.stream().mapToInt(m -> counter.count(m.content())).sum();
    }

    @Test
    void buildsMemoryAwareContextWithinBudget() {
        seedMemory();
        // generous budget so every section fits under its cap (default caps sum to ~9k)
        int budget = fixedTokensOnly() + 10000;

        List<ChatMessage> messages = builder.build(novel, budget);
        String userPrompt = messages.get(1).content();

        assertThat(messages).hasSize(2);
        assertThat(userPrompt).contains("【断点章节原文】")
                .contains("【断点章节摘要】")
                .contains("【当前人物状态】")
                .contains("林默")
                .contains("当前状态=右手受伤")
                .contains("未解决线索：血魔的行踪")
                .contains("【最近事件】")
                .contains("【人物状态历史】");
        assertThat(totalTokens(messages)).isLessThanOrEqualTo(budget);
    }

    @Test
    void midBudgetRespectsPriorityOrder() {
        seedMemory();
        // ~2500 tokens for sections: only the two required sections fit
        int budget = fixedTokensOnly() + 2500;

        List<ChatMessage> messages = builder.build(novel, budget);
        String userPrompt = messages.get(1).content();

        // high-priority required sections survive; everything below is starved out
        assertThat(userPrompt).contains("【断点章节原文】").contains("【断点章节摘要】");
        assertThat(userPrompt).doesNotContain("【最近事件】").doesNotContain("【人物状态历史】");
        assertThat(totalTokens(messages)).isLessThanOrEqualTo(budget);
    }

    @Test
    void tinyBudgetNeverOverflowsEvenForRequiredSections() {
        seedMemory();
        int budget = fixedTokensOnly() + 100;

        List<ChatMessage> messages = builder.build(novel, budget);

        assertThat(totalTokens(messages)).isLessThanOrEqualTo(budget);
        // the breakpoint text (required) is still present, compressed
        assertThat(messages.get(1).content()).contains("【断点章节原文】");
    }

    @Test
    void budgetBelowFixedCostsIsRejected() {
        seedMemory();
        int budget = fixedTokensOnly() - 10;

        assertThatThrownBy(() -> builder.build(novel, budget))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("预算过小");
    }

    @Test
    void novelWithoutMemoryFallsBackToPhaseOneBuilder() {
        List<ChatMessage> messages = builder.build(novel, 8192);

        assertThat(messages.get(1).content()).contains("按时间顺序排列");
        assertThat(messages.get(1).content()).doesNotContain("故事记忆");
    }

    private int fixedTokensOnly() {
        Chapter last = novel.lastChapter();
        String system = catalog.render("continuation.system.txt", Map.of(
                "novelTitle", novel.title(), "chapterNo", "第3章", "chapterTitle", last.title()));
        String skeleton = catalog.render("continuation.memory.user.txt", Map.of("sections", ""));
        return tokenCounter.count(system) + tokenCounter.count(skeleton);
    }
}
