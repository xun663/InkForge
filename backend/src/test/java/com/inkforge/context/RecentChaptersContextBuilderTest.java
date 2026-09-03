package com.inkforge.context;

import com.inkforge.chapter.Chapter;
import com.inkforge.common.JtokkitTokenCounter;
import com.inkforge.common.TokenCounter;
import com.inkforge.common.prompt.ClasspathPromptCatalog;
import com.inkforge.common.prompt.PromptCatalog;
import com.inkforge.novel.Novel;
import com.inkforge.provider.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The budget is a PARAMETER here — these tests exercise multiple budgets against
 * the same novel, proving nothing is hardcoded to 8192.
 */
class RecentChaptersContextBuilderTest {

    private final PromptCatalog catalog = new ClasspathPromptCatalog();
    private final TokenCounter tokenCounter = new JtokkitTokenCounter();
    private RecentChaptersContextBuilder builder;

    private Novel novel;

    @BeforeEach
    void setUp() {
        builder = new RecentChaptersContextBuilder(catalog, tokenCounter);
        novel = new Novel("n1", "测试小说", "t.txt", List.of(
                new Chapter(0, 1, "拜入山门", "第一章正文。".repeat(30)),
                new Chapter(1, 2, "玄霜剑", "第二章正文。".repeat(30)),
                new Chapter(2, 3, "血魔现世", "第三章正文。".repeat(30))));
    }

    private int fixedTokens() {
        Chapter last = novel.lastChapter();
        return tokenCounter.count(catalog.render("continuation.system.txt", Map.of(
                        "novelTitle", novel.title(),
                        "chapterNo", "第3章",
                        "chapterTitle", last.title())))
                + tokenCounter.count(catalog.render("continuation.user.txt", Map.of("context", "")));
    }

    private static String rendered(Chapter chapter) {
        String header = chapter.chapterNo() != null
                ? "第" + chapter.chapterNo() + "章 " + chapter.title()
                : chapter.title();
        return "【" + header + "】\n" + chapter.content();
    }

    private static int totalTokens(List<ChatMessage> messages) {
        int total = 0;
        for (ChatMessage message : messages) {
            total += new JtokkitTokenCounter().count(message.content());
        }
        return total;
    }

    @Test
    void generousBudgetIncludesAllChaptersInChronologicalOrder() {
        int budget = fixedTokens()
                + tokenCounter.count(rendered(novel.chapters().get(0)))
                + tokenCounter.count(rendered(novel.chapters().get(1)))
                + tokenCounter.count(rendered(novel.chapters().get(2))) + 10;

        List<ChatMessage> messages = builder.build(novel, budget);
        String userPrompt = messages.get(1).content();

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).role()).isEqualTo("system");
        assertThat(messages.get(1).role()).isEqualTo("user");
        assertThat(userPrompt).contains("第1章 拜入山门").contains("第2章 玄霜剑").contains("第3章 血魔现世");
        assertThat(userPrompt.indexOf("第1章")).isLessThan(userPrompt.indexOf("第2章"));
        assertThat(userPrompt.indexOf("第2章")).isLessThan(userPrompt.indexOf("第3章"));
        assertThat(totalTokens(messages)).isLessThanOrEqualTo(budget);
    }

    @Test
    void tightBudgetKeepsOnlyTheLastChapter() {
        int budget = fixedTokens() + tokenCounter.count(rendered(novel.chapters().get(2))) + 10;

        List<ChatMessage> messages = builder.build(novel, budget);
        String userPrompt = messages.get(1).content();

        assertThat(userPrompt).contains("第3章 血魔现世");
        assertThat(userPrompt).doesNotContain("第1章").doesNotContain("第2章");
        assertThat(totalTokens(messages)).isLessThanOrEqualTo(budget);
    }

    @Test
    void extremeBudgetTruncatesTheLastChapterTail() {
        // budget = fixed costs + the omission marker + a few tail tokens
        int markerTokens = tokenCounter.count("（前文过长，已按上下文预算省略）");
        int budget = fixedTokens() + markerTokens + 15;

        List<ChatMessage> messages = builder.build(novel, budget);
        String userPrompt = messages.get(1).content();

        assertThat(userPrompt).contains("第3章");
        assertThat(userPrompt).contains("省略");
        assertThat(totalTokens(messages)).isLessThanOrEqualTo(budget);
    }

    @Test
    void budgetBelowFixedCostsIsRejected() {
        int budget = fixedTokens() - 10;

        assertThatThrownBy(() -> builder.build(novel, budget))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("预算过小");
    }
}
