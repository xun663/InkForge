package com.inkforge.memory;

import com.inkforge.chapter.Chapter;
import com.inkforge.common.NotFoundException;
import com.inkforge.generation.CostCalculator;
import com.inkforge.generation.GenerationLog;
import com.inkforge.generation.GenerationLogRepository;
import com.inkforge.memory.extraction.MemoryExtractionProperties;
import com.inkforge.memory.extraction.MemoryExtractor;
import com.inkforge.novel.Novel;
import com.inkforge.novel.NovelRepository;
import com.inkforge.provider.LlmProvider;
import com.inkforge.retrieval.MemoryChunkProjectionService;
import com.inkforge.retrieval.MemoryEmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Facade over Story Memory: runs extraction+update for the most recent unprocessed
 * chapters, and serves memory overviews for the UI and the Context Builder.
 * Synchronous by design — no background tasks in Phase 2.
 */
@Service
public class StoryMemoryService {

    private static final Logger log = LoggerFactory.getLogger(StoryMemoryService.class);
    private static final int OVERVIEW_EVENT_LIMIT = 20;

    private final NovelRepository novelRepository;
    private final StoryMemoryRepository memoryRepository;
    private final MemoryExtractor extractor;
    private final MemoryUpdateService updateService;
    private final MemoryExtractionProperties properties;
    private final GenerationLogRepository generationLogRepository;
    private final CostCalculator costCalculator;
    private final LlmProvider llmProvider;
    private final MemoryChunkProjectionService projectionService;
    private final MemoryEmbeddingService embeddingService;

    public StoryMemoryService(NovelRepository novelRepository,
                              StoryMemoryRepository memoryRepository,
                              MemoryExtractor extractor,
                              MemoryUpdateService updateService,
                              MemoryExtractionProperties properties,
                              GenerationLogRepository generationLogRepository,
                              CostCalculator costCalculator,
                              LlmProvider llmProvider,
                              MemoryChunkProjectionService projectionService,
                              MemoryEmbeddingService embeddingService) {
        this.novelRepository = novelRepository;
        this.memoryRepository = memoryRepository;
        this.extractor = extractor;
        this.updateService = updateService;
        this.properties = properties;
        this.generationLogRepository = generationLogRepository;
        this.costCalculator = costCalculator;
        this.llmProvider = llmProvider;
        this.projectionService = projectionService;
        this.embeddingService = embeddingService;
    }

    /**
     * Extracts Story Memory for the most recent {@code count} chapters that have no
     * successful extraction yet (default: extract-window). Synchronous.
     */
    public List<MemoryExtractionRecord> extractRecent(String novelId, int count) {
        Novel novel = novelRepository.findById(novelId)
                .orElseThrow(() -> new NotFoundException("小说不存在: " + novelId));
        int window = count > 0 ? count : properties.extractWindow();

        List<Chapter> targets = novel.chapters().stream()
                .filter(c -> c.ordinal() >= novel.chapterCount() - window)
                .filter(c -> memoryRepository.findExtractionRecord(novelId, c.ordinal())
                        .filter(MemoryExtractionRecord::succeeded)
                        .isEmpty())
                .toList();

        List<MemoryExtractionRecord> records = new ArrayList<>();
        for (Chapter chapter : targets) {
            records.add(buildChapter(novelId, chapter));
        }
        return records;
    }

    /**
     * 单章处理（P5-A：全量 Memory Build Job 与 recent-window 共用）：
     * extract → apply → project → embed → save record。返回 extraction record（SUCCESS/FAILED）。
     *
     * <p>幂等约束：只有 extract+apply 全部成功才记 SUCCESS；同一章已有 SUCCESS 时调用方必须跳过
     * （Event 是 append-only 语义，绝不能对已 SUCCESS 的章节重复 apply）。
     */
    public MemoryExtractionRecord buildChapter(String novelId, Chapter chapter) {
        MemoryExtractor.ExtractionOutcome outcome =
                extractor.extract(chapter, display(chapter));
        if (outcome.result() != null) {
            updateService.apply(novelId, chapter, outcome.result());
            // P3-B projection hook (idempotent); projection failure must never
            // break Story Memory or the extraction flow
            try {
                projectionService.projectChapter(novelId, chapter.ordinal());
            } catch (Exception e) {
                log.warn("MemoryChunk 投影失败（不影响故事记忆）: novelId={}, ordinal={}",
                        novelId, chapter.ordinal(), e);
            }
            // Vector 接线：投影成功后为新增 chunk 生成 embedding（幂等，只处理缺失/内容变化的）。
            // embedding 失败绝不影响提取 / 投影 / 续写 / SSE done。
            try {
                embeddingService.embedNovel(novelId);
            } catch (Exception e) {
                log.warn("MemoryChunk embedding 失败（不影响故事记忆与续写）: novelId={}",
                        novelId, e);
            }
        }
        MemoryExtractionRecord record = new MemoryExtractionRecord(
                novelId, chapter.ordinal(),
                outcome.result() != null ? "SUCCESS" : "FAILED",
                outcome.errorMessage(), llmProvider.defaultModel(),
                outcome.stats(), Instant.now());
        memoryRepository.saveExtractionRecord(record);
        logExtractionCost(novelId, chapter, outcome);
        return record;
    }

    public MemoryOverview overview(String novelId) {
        novelRepository.findById(novelId)
                .orElseThrow(() -> new NotFoundException("小说不存在: " + novelId));
        List<MemoryExtractionRecord> records = memoryRepository.findExtractionRecords(novelId);
        Integer lastExtractedOrdinal = records.stream()
                .filter(MemoryExtractionRecord::succeeded)
                .map(MemoryExtractionRecord::chapterOrdinal)
                .max(Integer::compareTo)
                .orElse(null);

        List<CharacterView> characters = memoryRepository.findCharacters(novelId).stream()
                .map(c -> new CharacterView(
                        c.name(), c.aliases(), c.status(),
                        memoryRepository.findCurrentFacts(c.id()),
                        memoryRepository.findFacts(c.id()).stream()
                                .filter(f -> f.status() == FactStatus.SUPERSEDED)
                                .sorted(Comparator.comparingInt(CharacterFact::validFromChapter))
                                .toList()))
                .toList();

        List<SummaryView> summaries = memoryRepository.findSummaries(novelId, 0, Integer.MAX_VALUE)
                .stream()
                .sorted(Comparator.comparingInt(ChapterSummary::chapterOrdinal).reversed())
                .limit(properties.extractWindow() + 2)
                .map(s -> new SummaryView(s.chapterOrdinal(), s.summary(), s.unresolvedThreads()))
                .toList();

        int facts = characters.stream().mapToInt(c -> c.currentFacts().size()).sum()
                + characters.stream().mapToInt(c -> c.historyFacts().size()).sum();
        long totalDurationMs = records.stream()
                .mapToLong(r -> r.stats().durationMs()).sum();

        return new MemoryOverview(novelId, lastExtractedOrdinal, characters,
                memoryRepository.findEvents(novelId, OVERVIEW_EVENT_LIMIT, true),
                summaries, new AggregateStats(records.size(), characters.size(), facts,
                records.stream().flatMap(r -> List.of(r.stats().eventsExtracted()).stream())
                        .mapToInt(Integer::intValue).sum(),
                totalDurationMs));
    }

    private void logExtractionCost(String novelId, Chapter chapter, MemoryExtractor.ExtractionOutcome outcome) {
        MemoryExtractionStats stats = outcome.stats();
        generationLogRepository.save(new GenerationLog(
                UUID.randomUUID().toString(), novelId, llmProvider.name(), llmProvider.defaultModel(),
                stats.tokenUsage().promptTokens(), stats.tokenUsage().completionTokens(),
                stats.durationMs(),
                costCalculator.estimate(llmProvider.defaultModel(),
                        stats.tokenUsage().promptTokens(), stats.tokenUsage().completionTokens()),
                outcome.result() != null ? "SUCCESS" : "FAILED",
                outcome.errorMessage(),
                "EXTRACTION",
                Instant.now()));
    }

    private static String display(Chapter chapter) {
        return chapter.chapterNo() != null ? "第" + chapter.chapterNo() + "章" : chapter.title();
    }

    public record MemoryOverview(String novelId, Integer lastExtractedOrdinal,
                                 List<CharacterView> characters, List<StoryEvent> recentEvents,
                                 List<SummaryView> recentSummaries, AggregateStats aggregateStats) {
    }

    public record CharacterView(String name, List<String> aliases, CharacterStatus status,
                                List<CharacterFact> currentFacts, List<CharacterFact> historyFacts) {
    }

    public record SummaryView(int chapterOrdinal, String summary, List<String> unresolvedThreads) {
    }

    public record AggregateStats(int chaptersExtracted, int characters, int facts, int events,
                                 long totalDurationMs) {
    }
}
