package com.inkforge.context;

import com.inkforge.chapter.Chapter;
import com.inkforge.common.TokenCounter;
import com.inkforge.common.prompt.PromptCatalog;
import com.inkforge.novel.Novel;
import com.inkforge.provider.ChatMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Phase 1 deterministic context assembly:
 * <ol>
 *   <li>reserve the system prompt and user-template skeleton from the budget</li>
 *   <li>walk chapters backwards from the breakpoint, prioritizing the LAST chapter:
 *       the last chapter is kept even if it must be truncated (its TAIL is what
 *       matters at a breakpoint); earlier chapters are added only if they fit entirely</li>
 * </ol>
 * No LLM compression in Phase 1 — plain deterministic truncation.
 */
@Component
public class RecentChaptersContextBuilder implements ContinuationContextBuilder {

    private static final String OMITTED_MARK = "（前文过长，已按上下文预算省略）";
    private static final String SYSTEM_TEMPLATE = "continuation.system.txt";
    private static final String USER_TEMPLATE = "continuation.user.txt";

    private final PromptCatalog promptCatalog;
    private final TokenCounter tokenCounter;

    public RecentChaptersContextBuilder(PromptCatalog promptCatalog, TokenCounter tokenCounter) {
        this.promptCatalog = promptCatalog;
        this.tokenCounter = tokenCounter;
    }

    @Override
    public List<ChatMessage> build(Novel novel, int contextMaxTokens) {
        Chapter last = novel.lastChapter();
        String systemPrompt = promptCatalog.render(SYSTEM_TEMPLATE, Map.of(
                "novelTitle", novel.title(),
                "chapterNo", displayChapterNo(last),
                "chapterTitle", last.title()));
        String userSkeleton = promptCatalog.render(USER_TEMPLATE, Map.of("context", ""));

        int fixedTokens = tokenCounter.count(systemPrompt) + tokenCounter.count(userSkeleton);
        int available = contextMaxTokens - fixedTokens;
        if (available <= 0) {
            throw new IllegalArgumentException(
                    "上下文预算过小：系统提示与指令模板已占用 " + fixedTokens
                            + " tokens（预算 " + contextMaxTokens + "）");
        }

        String userPrompt = promptCatalog.render(USER_TEMPLATE,
                Map.of("context", buildChapterContext(novel, available)));
        return List.of(ChatMessage.system(systemPrompt), ChatMessage.user(userPrompt));
    }

    private String buildChapterContext(Novel novel, int available) {
        List<Chapter> chapters = novel.chapters();
        List<String> rendered = new ArrayList<>();
        int used = 0;

        for (int i = chapters.size() - 1; i >= 0; i--) {
            Chapter chapter = chapters.get(i);
            boolean isLast = i == chapters.size() - 1;
            String renderedChapter;
            int tokens;
            if (isLast) {
                // the tail of the last chapter always wins, even if truncated;
                // the header tokens are accounted for inside the fit budget
                renderedChapter = renderChapterFitted(chapter, available - used);
                tokens = tokenCounter.count(renderedChapter);
            } else {
                renderedChapter = renderChapter(chapter, chapter.content());
                tokens = tokenCounter.count(renderedChapter);
                if (used + tokens > available) {
                    break; // older chapters are dropped entirely — deterministic priority order
                }
            }
            rendered.add(renderedChapter);
            used += tokens;
        }

        // restore chronological order so the breakpoint chapter is the final block
        StringBuilder context = new StringBuilder();
        for (int i = rendered.size() - 1; i >= 0; i--) {
            if (!context.isEmpty()) {
                context.append('\n');
            }
            context.append(rendered.get(i));
        }
        return context.toString();
    }

    /**
     * Renders the last chapter with a header, keeping only the tail of the content
     * that fits the budget (prefixed with an omission marker when cut).
     */
    private String renderChapterFitted(Chapter chapter, int tokenBudget) {
        String headerText = "【" + header(chapter) + "】\n";
        String body = fitTail(chapter.content(), tokenBudget - tokenCounter.count(headerText));
        return headerText + body;
    }

    /** Keeps the tail of the content that fits the budget, prefixed with an omission marker when cut. */
    private String fitTail(String content, int tokenBudget) {
        String tail = content;
        while (tokenCounter.count(OMITTED_MARK + tail) > tokenBudget && tail.length() > 0) {
            tail = tail.substring(Math.max(1, tail.length() / 10));
        }
        if (tokenCounter.count(OMITTED_MARK + tail) > tokenBudget) {
            return ""; // even the marker alone does not fit — drop content entirely
        }
        return tail.equals(content) ? content : OMITTED_MARK + tail;
    }

    private static String renderChapter(Chapter chapter, String body) {
        return "【" + header(chapter) + "】\n" + body;
    }

    private static String header(Chapter chapter) {
        if (chapter.chapterNo() != null) {
            return "第" + chapter.chapterNo() + "章"
                    + (chapter.title().isBlank() ? "" : " " + chapter.title());
        }
        return chapter.title();
    }

    private static String displayChapterNo(Chapter chapter) {
        return chapter.chapterNo() != null ? "第" + chapter.chapterNo() + "章" : chapter.title();
    }
}
