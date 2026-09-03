package com.inkforge.context;

import com.inkforge.chapter.Chapter;
import com.inkforge.common.TokenCounter;
import com.inkforge.common.prompt.PromptCatalog;
import com.inkforge.memory.Character;
import com.inkforge.memory.CharacterFact;
import com.inkforge.memory.ChapterSummary;
import com.inkforge.memory.FactStatus;
import com.inkforge.memory.StoryEvent;
import com.inkforge.memory.StoryMemoryRepository;
import com.inkforge.novel.Novel;
import com.inkforge.provider.ChatMessage;
import com.inkforge.retrieval.RetrievedMemory;
import com.inkforge.retrieval.RetrievedMemoryProvider;
import com.inkforge.retrieval.RetrievalResult;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase 2 context assembly: recent chapters + Story Memory under a strict token budget.
 *
 * <p>Allocation (docs/phase2-design.md §18):
 * <ol>
 *   <li>fixed costs (system prompt + user skeleton) are reserved first</li>
 *   <li>required sections get their minTokens reserved in priority order — but the global
 *       invariant {@code total <= context-max-tokens} is supreme: in tiny budgets required
 *       sections are compressed further, never allowed to overflow</li>
 *   <li>remaining budget is distributed greedily by priority, capped per section at maxTokens</li>
 * </ol>
 * Sections are plain configuration data ({@link ContextSection}) — Phase 3 adds a
 * retrieved-memory section without redesigning this builder.
 *
 * <p>Novels without any Story Memory fall back to the Phase 1 recent-chapters builder.
 */
@Component
@Primary
public class MemoryAwareContextBuilder implements ContinuationContextBuilder {

    private static final String OMITTED_MARK = "（内容过长，已按上下文预算省略）";

    /** Built-in fallback when no sections are configured. */
    private static final Map<String, ContextSection> DEFAULT_SECTIONS = Map.of(
            "breakpoint-text", new ContextSection(1, 4096, 2048, true),
            "breakpoint-memory", new ContextSection(2, 1024, 128, true),
            "current-facts", new ContextSection(3, 1024, 0, false),
            "recent-events", new ContextSection(4, 768, 0, false),
            "retrieved-memory", new ContextSection(5, 1024, 0, false),
            "recent-chapters", new ContextSection(6, 1280, 0, false),
            "fact-history", new ContextSection(7, 512, 0, false),
            "older-summaries", new ContextSection(8, 256, 0, false));

    private static final RetrievedMemoryProvider NOOP_PROVIDER = (novel, tokens, generationId) ->
            RetrievedMemory.empty();

    private final PromptCatalog promptCatalog;
    private final TokenCounter tokenCounter;
    private final StoryMemoryRepository memoryRepository;
    private final RecentChaptersContextBuilder fallback;
    private final ContextProperties contextProperties;
    private final RetrievedMemoryProvider retrievedMemoryProvider;

    /**
     * Phase-2-compatible constructor (no retrieval). Kept so existing tests/uses of
     * {@code build(...)} stay untouched; Spring uses the 6-arg constructor.
     */
    public MemoryAwareContextBuilder(PromptCatalog promptCatalog, TokenCounter tokenCounter,
                                     StoryMemoryRepository memoryRepository,
                                     RecentChaptersContextBuilder fallback,
                                     ContextProperties contextProperties) {
        this(promptCatalog, tokenCounter, memoryRepository, fallback, contextProperties, NOOP_PROVIDER);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public MemoryAwareContextBuilder(PromptCatalog promptCatalog, TokenCounter tokenCounter,
                                     StoryMemoryRepository memoryRepository,
                                     RecentChaptersContextBuilder fallback,
                                     ContextProperties contextProperties,
                                     RetrievedMemoryProvider retrievedMemoryProvider) {
        this.promptCatalog = promptCatalog;
        this.tokenCounter = tokenCounter;
        this.memoryRepository = memoryRepository;
        this.fallback = fallback;
        this.contextProperties = contextProperties;
        this.retrievedMemoryProvider = retrievedMemoryProvider;
    }

    @Override
    public List<ChatMessage> build(Novel novel, int contextMaxTokens) {
        return buildWithTrace(novel, contextMaxTokens, null).messages();
    }

    @Override
    public ContextBuildResult buildWithTrace(Novel novel, int contextMaxTokens, String generationId) {
        if (!hasMemory(novel)) {
            return new ContextBuildResult(fallback.build(novel, contextMaxTokens), null, 0);
        }
        RetrievedMemory retrieved = safeRetrieve(novel, contextMaxTokens, generationId);
        List<ChatMessage> messages = buildWithMemory(novel, contextMaxTokens, retrieved);
        return new ContextBuildResult(messages, retrieved.traceId(), retrieved.results().size());
    }

    private RetrievedMemory safeRetrieve(Novel novel, int contextMaxTokens, String generationId) {
        try {
            return retrievedMemoryProvider.retrieve(novel, contextMaxTokens, generationId);
        } catch (Exception e) {
            // 检索是增强能力：任何异常 → 空记忆，续写继续
            return RetrievedMemory.empty();
        }
    }

    private boolean hasMemory(Novel novel) {
        return !memoryRepository.findCharacters(novel.id()).isEmpty()
                || memoryRepository.findSummary(novel.id(), novel.lastChapter().ordinal()).isPresent();
    }

    private List<ChatMessage> buildWithMemory(Novel novel, int contextMaxTokens, RetrievedMemory retrieved) {
        Chapter last = novel.lastChapter();
        String systemPrompt = promptCatalog.render("continuation.system.txt", Map.of(
                "novelTitle", novel.title(),
                "chapterNo", display(last),
                "chapterTitle", last.title()));
        String userSkeleton = promptCatalog.render("continuation.memory.user.txt", Map.of("sections", ""));

        int fixedTokens = tokenCounter.count(systemPrompt) + tokenCounter.count(userSkeleton);
        int remaining = contextMaxTokens - fixedTokens;
        if (remaining <= 0) {
            throw new IllegalArgumentException(
                    "上下文预算过小：系统提示与指令模板已占用 " + fixedTokens
                            + " tokens（预算 " + contextMaxTokens + "）");
        }

        List<Map.Entry<String, ContextSection>> sections = sectionsByPriority();
        Map<String, Integer> allocation = allocate(sections, remaining);

        // render each section within its allocation, then assemble chronologically meaningful order
        List<String> rendered = new ArrayList<>();
        for (Map.Entry<String, ContextSection> entry : sections) {
            String content = renderSection(entry.getKey(), novel, allocation.get(entry.getKey()), retrieved);
            if (!content.isBlank()) {
                rendered.add(content);
            }
        }

        String userPrompt = promptCatalog.render("continuation.memory.user.txt",
                Map.of("sections", String.join("\n", rendered)));
        return List.of(ChatMessage.system(systemPrompt), ChatMessage.user(userPrompt));
    }

    private List<Map.Entry<String, ContextSection>> sectionsByPriority() {
        // config (application.yml) overrides/adds; built-in defaults fill the rest
        Map<String, ContextSection> merged = new HashMap<>(DEFAULT_SECTIONS);
        merged.putAll(contextProperties.sections());
        return merged.entrySet().stream()
                .sorted(Comparator.comparingInt(e -> e.getValue().priority()))
                .toList();
    }

    /** Phase 1: required mins in priority order (each capped by remaining). Phase 2: greedy top-up. */
    private static Map<String, Integer> allocate(List<Map.Entry<String, ContextSection>> sections, int budget) {
        Map<String, Integer> allocation = new HashMap<>();
        int remaining = budget;
        for (Map.Entry<String, ContextSection> entry : sections) {
            ContextSection section = entry.getValue();
            if (section.required()) {
                int reserve = Math.min(section.minTokens(), remaining);
                allocation.put(entry.getKey(), reserve);
                remaining -= reserve;
            } else {
                allocation.put(entry.getKey(), 0);
            }
        }
        for (Map.Entry<String, ContextSection> entry : sections) {
            ContextSection section = entry.getValue();
            int topUp = Math.min(Math.max(0, section.maxTokens() - allocation.get(entry.getKey())), remaining);
            if (topUp > 0) {
                allocation.merge(entry.getKey(), topUp, Integer::sum);
                remaining -= topUp;
            }
        }
        return allocation;
    }

    private String renderSection(String key, Novel novel, int tokenBudget, RetrievedMemory retrieved) {
        if (tokenBudget <= 0) {
            return "";
        }
        if ("retrieved-memory".equals(key)) {
            // P5-B3-0: retrieved-memory 用 rank-preserving 保序选择（改前 fitTail 是"裁头保尾"，
            // 与"检索结果高分在前"矛盾，会把高排名 Gold 裁掉）。其他 section 不受影响。
            return renderRetrievedMemory(retrieved, tokenBudget);
        }
        String content = switch (key) {
            case "breakpoint-text" -> "【断点章节原文】\n" + novel.lastChapter().content();
            case "breakpoint-memory" -> renderBreakpointMemory(novel);
            case "current-facts" -> renderCurrentFacts(novel);
            case "recent-events" -> renderRecentEvents(novel);
            case "recent-chapters" -> renderRecentChapters(novel);
            case "fact-history" -> renderFactHistory(novel);
            case "older-summaries" -> renderOlderSummaries(novel);
            default -> "";
        };
        if (content.isBlank()) {
            return "";
        }
        // the section header survives even under extreme truncation — only the body is cut
        int newline = content.indexOf('\n');
        String header = newline > 0 ? content.substring(0, newline) : "";
        String body = newline > 0 ? content.substring(newline + 1) : content;
        String headerText = header.isEmpty() ? "" : header + "\n";
        String fitted = fitTail(body, tokenBudget - tokenCounter.count(headerText), tokenCounter);
        return headerText + fitted;
    }

    /**
     * P5-B3-0: retrieved-memory 区段，rank-preserving 保序选择。
     * 检索结果已是"高分在前"（{@code DefaultRetrievedMemoryProvider} 按 score 降序），这里按 rank
     * 从 1 起依次加入；下一个放不下就停，不删前面已加入的高排名证据。首个即使超预算也保留
     * （交由 {@link #fitTail} 兜底裁到预算内，避免"超长单条被整条丢弃"）。
     */
    private String renderRetrievedMemory(RetrievedMemory retrieved, int tokenBudget) {
        if (retrieved == null || retrieved.results().isEmpty()) {
            return "";
        }
        String headerText = "【检索到的相关记忆】\n";
        int bodyBudget = Math.max(0, tokenBudget - tokenCounter.count(headerText));
        if (bodyBudget <= 0) {
            return "";
        }
        String body = rankPreservingRetrievedBody(retrieved.results(), bodyBudget, tokenCounter);
        return headerText + body;
    }

    /** rank-preserving 选择逻辑（static、可测）：保序累加，放不下即停。 */
    static String rankPreservingRetrievedBody(List<RetrievalResult> results, int bodyTokenBudget,
                                              TokenCounter tokenCounter) {
        if (results == null || results.isEmpty() || bodyTokenBudget <= 0) {
            return "";
        }
        StringBuilder acc = new StringBuilder();
        boolean first = true;
        for (RetrievalResult r : results) {
            String unit = "\n· 第" + (r.chapterOrdinal() + 1) + "章 · [" + r.memoryType() + "] "
                    + (r.text() == null ? "" : r.text());
            String piece = first ? unit.substring(1) : unit;
            if (first) {                 // 最高排名必须保留（超预算由 fitTail 兜底）
                acc.append(piece);
                first = false;
                continue;
            }
            String cand = acc + piece;
            if (tokenCounter.count(cand) <= bodyTokenBudget) {
                acc = new StringBuilder(cand);
            } else {
                break;                   // 放不下 → 停，保序保前面的高排名证据
            }
        }
        return fitTail(acc.toString(), bodyTokenBudget, tokenCounter);
    }

    private String renderBreakpointMemory(Novel novel) {
        ChapterSummary summary = memoryRepository
                .findSummary(novel.id(), novel.lastChapter().ordinal()).orElse(null);
        if (summary == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder("【断点章节摘要】\n").append(summary.summary());
        if (!summary.keyEvents().isEmpty()) {
            sb.append("\n关键事件：").append(String.join("；", summary.keyEvents()));
        }
        if (!summary.unresolvedThreads().isEmpty()) {
            sb.append("\n未解决线索：").append(String.join("；", summary.unresolvedThreads()));
        }
        return sb.toString();
    }

    private String renderCurrentFacts(Novel novel) {
        StringBuilder sb = new StringBuilder("【当前人物状态】");
        boolean any = false;
        for (Character character : currentCharacters(novel)) {
            List<CharacterFact> facts = memoryRepository.findCurrentFacts(character.id());
            if (facts.isEmpty()) {
                continue;
            }
            any = true;
            sb.append("\n· ").append(character.name()).append("：")
                    .append(String.join("；", facts.stream().map(this::factText).toList()));
        }
        return any ? sb.toString() : "";
    }

    private String renderRecentEvents(Novel novel) {
        List<StoryEvent> events = memoryRepository.findEvents(novel.id(), 10, true);
        if (events.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("【最近事件】");
        for (StoryEvent event : events) {
            sb.append("\n· 第").append(event.chapterOrdinal() + 1).append("章 ")
                    .append(event.title()).append("：").append(event.description());
        }
        return sb.toString();
    }

    /** Phase 1 rolling window over the chapters before the breakpoint, whole-chapter fit. */
    private String renderRecentChapters(Novel novel) {
        List<Chapter> chapters = novel.chapters();
        StringBuilder sb = new StringBuilder("【最近章节原文】");
        for (int i = chapters.size() - 2; i >= 0; i--) {
            Chapter chapter = chapters.get(i);
            sb.append("\n【").append(display(chapter)).append("】\n").append(chapter.content());
        }
        return sb.toString();
    }

    private String renderFactHistory(Novel novel) {
        StringBuilder sb = new StringBuilder("【人物状态历史】");
        boolean any = false;
        for (Character character : currentCharacters(novel)) {
            List<CharacterFact> history = memoryRepository.findFacts(character.id()).stream()
                    .filter(f -> f.status() == FactStatus.SUPERSEDED)
                    .sorted(Comparator.comparingInt(CharacterFact::validFromChapter))
                    .toList();
            if (history.isEmpty()) {
                continue;
            }
            any = true;
            for (CharacterFact fact : history) {
                sb.append("\n· ").append(character.name()).append(" · 第")
                        .append(fact.validFromChapter() + 1).append("章 ")
                        .append(factText(fact));
            }
        }
        return any ? sb.toString() : "";
    }

    private String renderOlderSummaries(Novel novel) {
        int lastOrdinal = novel.lastChapter().ordinal();
        List<ChapterSummary> summaries = memoryRepository
                .findSummaries(novel.id(), 0, lastOrdinal - 3).stream()
                .sorted(Comparator.comparingInt(ChapterSummary::chapterOrdinal).reversed())
                .limit(3)
                .toList();
        if (summaries.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("【更早章节摘要】");
        for (ChapterSummary summary : summaries) {
            sb.append("\n· 第").append(summary.chapterOrdinal() + 1).append("章：").append(summary.summary());
        }
        return sb.toString();
    }

    private String factText(CharacterFact fact) {
        if (fact.targetCharacter() != null) {
            return "与" + fact.targetCharacter() + "的关系=" + fact.value();
        }
        return fact.attribute() + "=" + fact.value();
    }

    /** Deterministic memory selection (P2, no retrieval): characters of the recent summaries. */
    private Set<Character> currentCharacters(Novel novel) {
        Set<Character> characters = new LinkedHashSet<>();
        int lastOrdinal = novel.lastChapter().ordinal();
        for (int ordinal = lastOrdinal; ordinal > lastOrdinal - 3 && ordinal >= 0; ordinal--) {
            memoryRepository.findSummary(novel.id(), ordinal).ifPresent(summary -> {
                for (var mentioned : summary.characters()) {
                    memoryRepository.findCharacterByName(novel.id(), mentioned.name())
                            .ifPresent(characters::add);
                }
            });
        }
        // fallback: all known characters when no summary mentions resolve
        if (characters.isEmpty()) {
            characters.addAll(memoryRepository.findCharacters(novel.id()));
        }
        return characters;
    }

    /** Keeps the tail of the content that fits the budget, prefixed with an omission marker when cut. */
    static String fitTail(String content, int tokenBudget, TokenCounter tokenCounter) {
        String tail = content;
        while (tokenCounter.count(OMITTED_MARK + tail) > tokenBudget && tail.length() > 0) {
            tail = tail.substring(Math.max(1, tail.length() / 10));
        }
        if (tokenCounter.count(OMITTED_MARK + tail) > tokenBudget) {
            return "";
        }
        return tail.equals(content) ? content : OMITTED_MARK + tail;
    }

    private static String display(Chapter chapter) {
        return chapter.chapterNo() != null ? "第" + chapter.chapterNo() + "章" : chapter.title();
    }
}
