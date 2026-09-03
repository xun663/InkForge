package com.inkforge.retrieval;

import com.inkforge.chapter.Chapter;
import com.inkforge.memory.ChapterSummary;
import com.inkforge.memory.StoryMemoryRepository;
import com.inkforge.memory.SummaryCharacter;
import com.inkforge.novel.Novel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic query construction (P3-E, V1 — deliberately minimal, no LLM rewriting,
 * no query expansion):
 * <ul>
 *   <li>primary: breakpoint chapter tail + breakpoint summary</li>
 *   <li>character: characters of the breakpoint summary</li>
 *   <li>thread: unresolvedThreads of the breakpoint summary</li>
 * </ul>
 * At most 3 queries, fixed order (primary → character → thread); blank content is
 * never emitted. Same input → same queries (MultiQuery ablation in P3-G depends on it).
 */
@Service
public class RetrievalQueryBuilder {

    private static final int PRIMARY_TAIL_CHARS = 300;
    private static final int PRIMARY_SUMMARY_CHARS = 200;

    private final StoryMemoryRepository memoryRepository;
    private final QueryIntentClassifier intentClassifier;

    /** Spring：注入确定性意图分类器（P5-B1）。 */
    @org.springframework.beans.factory.annotation.Autowired
    public RetrievalQueryBuilder(StoryMemoryRepository memoryRepository, QueryIntentClassifier intentClassifier) {
        this.memoryRepository = memoryRepository;
        this.intentClassifier = intentClassifier;
    }

    /** P3 兼容构造（测试用）。 */
    public RetrievalQueryBuilder(StoryMemoryRepository memoryRepository) {
        this(memoryRepository, new QueryIntentClassifier());
    }

    public List<RetrievalQuery> build(Novel novel) {
        List<RetrievalQuery> queries = new ArrayList<>(3);
        Chapter last = novel.lastChapter();
        ChapterSummary summary = memoryRepository.findSummary(novel.id(), last.ordinal()).orElse(null);

        // 1. primary: 末章尾部 + 摘要
        String tail = truncate(last.content(), PRIMARY_TAIL_CHARS);
        String summaryText = summary == null ? "" : truncate(summary.summary(), PRIMARY_SUMMARY_CHARS);
        String primary = joinNonBlank(tail, summaryText);
        if (!primary.isBlank()) {
            queries.add(new RetrievalQuery("primary", primary,
                    QueryIntent.RECENT_PLOT, 0, "断点章节尾部 + 摘要"));
        }

        // 2. character: 断点摘要出场人物
        List<String> names = new ArrayList<>();
        if (summary != null) {
            for (SummaryCharacter character : summary.characters()) {
                if (character.name() != null && !character.name().isBlank()) {
                    names.add(character.name());
                }
            }
        }
        if (!names.isEmpty()) {
            queries.add(new RetrievalQuery("character", String.join(" ", names),
                    QueryIntent.CHARACTER, 1, "断点摘要出场人物"));
        }

        // 3. thread: 未解决线索
        if (summary != null && !summary.unresolvedThreads().isEmpty()) {
            String threadText = String.join("；", summary.unresolvedThreads());
            QueryIntent ti = intentClassifier.classify(threadText);
            queries.add(new RetrievalQuery("thread", threadText, ti, 2, "未解决线索（分类=" + ti + "）"));
        }

        return queries;
    }

    private static String joinNonBlank(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                if (!sb.isEmpty()) {
                    sb.append(' ');
                }
                sb.append(part);
            }
        }
        return sb.toString();
    }

    private static String truncate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }
}
