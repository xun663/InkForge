package com.inkforge.planning;

import com.inkforge.chapter.Chapter;
import com.inkforge.context.BreakpointAnalyzer;
import com.inkforge.context.BreakpointInfo;
import com.inkforge.memory.Character;
import com.inkforge.memory.CharacterFact;
import com.inkforge.memory.ChapterSummary;
import com.inkforge.memory.StoryEvent;
import com.inkforge.memory.StoryMemoryRepository;
import com.inkforge.novel.Novel;
import com.inkforge.retrieval.HybridRetrievalService;
import com.inkforge.retrieval.QueryConstructionService;
import com.inkforge.retrieval.RetrievalResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 规划上下文装配：为剧情规划（而非正文生成）组装 LLM 输入。
 *
 * <p>与生成期检索刻意不同（规划重线索/人物/世界观，生成重当前场景）：
 * 使用 {@link QueryConstructionService}（P5-B1 成果，首次接入生产路径）构造检索表达，
 * 经 {@link HybridRetrievalService#retrieveMulti} 检索。
 *
 * <p>只读约束：仅调用 StoryMemoryRepository 的 find* 方法，绝不写入；
 * 检索是增强能力，任何异常降级为空（与生成期同一契约）。
 */
@Service
public class PlanningContextAssembler {

    private static final int MAX_FACT_CHARACTERS = 12;
    private static final int MAX_FACTS_PER_CHARACTER = 4;
    private static final int MAX_RECENT_EVENTS = 5;
    private static final int MAX_RETRIEVED = 8;
    private static final int RETRIEVED_SNIPPET_CHARS = 200;
    private static final int QUERY_TAIL_CHARS = 300;
    private static final int QUERY_SUMMARY_CHARS = 200;

    private final StoryMemoryRepository memoryRepository;
    private final PlotThreadRepository plotThreadRepository;
    private final BreakpointAnalyzer breakpointAnalyzer;
    private final QueryConstructionService queryConstructionService;
    private final HybridRetrievalService hybridRetrievalService;

    public PlanningContextAssembler(StoryMemoryRepository memoryRepository,
                                    PlotThreadRepository plotThreadRepository,
                                    BreakpointAnalyzer breakpointAnalyzer,
                                    QueryConstructionService queryConstructionService,
                                    HybridRetrievalService hybridRetrievalService) {
        this.memoryRepository = memoryRepository;
        this.plotThreadRepository = plotThreadRepository;
        this.breakpointAnalyzer = breakpointAnalyzer;
        this.queryConstructionService = queryConstructionService;
        this.hybridRetrievalService = hybridRetrievalService;
    }

    /** 组装规划上下文；四个文本块都可能为空说明文案（PromptCatalog 变量必须存在，空串合法）。 */
    public PlanningContext assemble(Novel novel, String userInstruction) {
        Chapter last = novel.lastChapter();
        BreakpointInfo bp = breakpointAnalyzer.analyze(novel);
        String breakpointDisplay = bp.chapterNo() == null
                ? "《" + bp.chapterTitle() + "》" : "第" + bp.chapterNo() + "章";
        String breakpoint = "《" + novel.title() + "》断点：" + breakpointDisplay
                + "《" + bp.chapterTitle() + "》\n" + bp.tailExcerpt();

        String storyState = renderStoryState(novel, last);
        String openThreads = renderOpenThreads(novel);
        String retrieved = renderRetrieved(novel, bp, userInstruction);

        return new PlanningContext(breakpoint, storyState, openThreads, retrieved);
    }

    private String renderStoryState(Novel novel, Chapter last) {
        StringBuilder state = new StringBuilder();
        Optional<ChapterSummary> summary = memoryRepository.findSummary(novel.id(), last.ordinal());
        if (summary.isPresent()) {
            ChapterSummary s = summary.get();
            state.append("断点摘要：").append(s.summary()).append('\n');
            if (!s.keyEvents().isEmpty()) {
                state.append("关键事件：").append(String.join("；", s.keyEvents())).append('\n');
            }
        } else {
            state.append("（尚未构建 Story Memory，请主要依据断点原文推断状态）\n");
        }

        int characters = 0;
        for (Character character : memoryRepository.findCharacters(novel.id())) {
            if (characters >= MAX_FACT_CHARACTERS) {
                break;
            }
            List<CharacterFact> facts = memoryRepository.findCurrentFacts(character.id());
            if (facts.isEmpty()) {
                continue;
            }
            List<String> pairs = facts.stream()
                    .limit(MAX_FACTS_PER_CHARACTER)
                    .map(f -> f.attribute() + "=" + f.value())
                    .toList();
            state.append("当前人物状态 · ").append(character.name()).append("：")
                    .append(String.join("；", pairs)).append('\n');
            characters++;
        }

        List<StoryEvent> events = memoryRepository.findEvents(novel.id(), MAX_RECENT_EVENTS, true);
        if (!events.isEmpty()) {
            state.append("最近事件：\n");
            for (StoryEvent event : events) {
                state.append("· 第").append(event.chapterOrdinal() + 1).append("章 ")
                        .append(event.title()).append('\n');
            }
        }
        return state.toString().trim();
    }

    private String renderOpenThreads(Novel novel) {
        List<PlotThread> open = plotThreadRepository.findOpenByNovelId(novel.id());
        if (open.isEmpty()) {
            return "";
        }
        StringBuilder threads = new StringBuilder();
        for (PlotThread thread : open) {
            threads.append("· ").append(thread.title());
            if (thread.firstSeenChapter() != null) {
                threads.append("（第").append(thread.firstSeenChapter() + 1).append("章起）");
            }
            if (thread.summary() != null && !thread.summary().isBlank()) {
                threads.append("：").append(thread.summary());
            }
            threads.append('\n');
        }
        return threads.toString().trim();
    }

    /** 规划期检索：断点尾部 + 断点摘要线索 + 用户要求，三条种子经意图构造后多路检索。 */
    private String renderRetrieved(Novel novel, BreakpointInfo bp, String userInstruction) {
        List<String> seeds = new ArrayList<>();
        String tail = bp.tailExcerpt() == null ? "" : bp.tailExcerpt();
        if (tail.length() > QUERY_TAIL_CHARS) {
            tail = tail.substring(tail.length() - QUERY_TAIL_CHARS);
        }
        if (!tail.isBlank()) {
            seeds.add(tail);
        }
        memoryRepository.findSummary(novel.id(), bp.chapterOrdinal())
                .ifPresent(summary -> {
                    String threadText = String.join("；", summary.unresolvedThreads());
                    if (!threadText.isBlank()) {
                        if (threadText.length() > QUERY_SUMMARY_CHARS) {
                            threadText = threadText.substring(0, QUERY_SUMMARY_CHARS);
                        }
                        seeds.add(threadText);
                    }
                });
        if (userInstruction != null && !userInstruction.isBlank()) {
            seeds.add(userInstruction.trim());
        }
        if (seeds.isEmpty()) {
            return "";
        }
        try {
            List<RetrievalResult> results = hybridRetrievalService.retrieveMulti(novel.id(), seeds);
            if (results.isEmpty()) {
                return "";
            }
            StringBuilder rendered = new StringBuilder();
            results.stream()
                    .limit(MAX_RETRIEVED)
                    .forEach(result -> rendered.append("· 第").append(result.chapterOrdinal() + 1)
                            .append("章 [").append(result.memoryType()).append("] ")
                            .append(snippet(result.text()))
                            .append('\n'));
            return rendered.toString().trim();
        } catch (Exception e) {
            return ""; // 检索是增强能力：异常降级，规划继续
        }
    }

    private static String snippet(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() > RETRIEVED_SNIPPET_CHARS
                ? flat.substring(0, RETRIEVED_SNIPPET_CHARS) + "…" : flat;
    }

    /** 规划上下文（四个渲染后的文本块）。 */
    public record PlanningContext(String breakpoint, String storyState, String openThreads, String retrieved) {
    }
}
