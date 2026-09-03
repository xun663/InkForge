package com.inkforge.memory;

import com.inkforge.chapter.Chapter;
import com.inkforge.memory.extraction.ChapterExtractionResult;
import com.inkforge.novel.Novel;
import com.inkforge.novel.NovelRepository;
import com.inkforge.provider.LlmUsage;
import com.inkforge.retrieval.MemoryChunkProjectionService;
import com.inkforge.retrieval.MemoryEmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 把磁盘上的章节提取 outcomes 确定性重放到运行中 App 的仓储：
 * 按节切分正文 → apply → project → 记 SUCCESS record →（可选）embedNovel 一次。
 * 不调用 LLM，不改写 outcomes 目录。
 */
@Service
public class OutcomeReplayService {

    private static final Logger log = LoggerFactory.getLogger(OutcomeReplayService.class);

    private final NovelRepository novelRepository;
    private final StoryMemoryRepository memoryRepository;
    private final MemoryUpdateService updateService;
    private final MemoryChunkProjectionService projectionService;
    private final MemoryEmbeddingService embeddingService;
    private final ObjectMapper objectMapper;

    public OutcomeReplayService(NovelRepository novelRepository,
                                StoryMemoryRepository memoryRepository,
                                MemoryUpdateService updateService,
                                MemoryChunkProjectionService projectionService,
                                MemoryEmbeddingService embeddingService,
                                ObjectMapper objectMapper) {
        this.novelRepository = novelRepository;
        this.memoryRepository = memoryRepository;
        this.updateService = updateService;
        this.projectionService = projectionService;
        this.embeddingService = embeddingService;
        this.objectMapper = objectMapper;
    }

    public ReplayResult replay(Path sourceTxt, Path outcomesDir, String title, boolean embed) {
        if (sourceTxt == null || !Files.isRegularFile(sourceTxt)) {
            throw new IllegalArgumentException("源 TXT 不存在: " + sourceTxt);
        }
        Path outcomeRoot = outcomesDir == null ? null : outcomesDir.resolve("outcomes");
        if (outcomesDir == null || !Files.isDirectory(outcomesDir) || !Files.isDirectory(outcomeRoot)) {
            throw new IllegalArgumentException("outcomes 目录不存在: " + outcomesDir);
        }
        List<Chapter> chapters;
        try {
            chapters = GzrSectionSplitter.split(Files.readString(sourceTxt, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("读取源 TXT 失败: " + e.getMessage());
        }
        if (chapters.isEmpty()) {
            throw new IllegalArgumentException("源 TXT 未切出任何节");
        }
        String novelId = UUID.randomUUID().toString();
        String fileName = sourceTxt.getFileName().toString();
        String novelTitle = title == null || title.isBlank() ? "蛊真人" : title.strip();
        Novel novel = novelRepository.save(new Novel(novelId, novelTitle, fileName, chapters));

        int replayed = 0;
        int skipped = 0;
        List<Integer> applyFailed = new ArrayList<>();
        for (Chapter chapter : chapters) {
            Path file = outcomeRoot.resolve("chapter-" + chapter.ordinal() + ".json");
            Map<?, ?> rec = readOutcome(file);
            if (rec == null || !Boolean.TRUE.equals(rec.get("ok"))) {
                skipped++;
                continue;
            }
            Object raw = rec.get("result");
            if (!(raw instanceof String json) || json.isBlank()) {
                skipped++;
                continue;
            }
            try {
                ChapterExtractionResult extraction = objectMapper.readValue(json, ChapterExtractionResult.class);
                MemoryUpdateService.UpdateStats stats = updateService.apply(novelId, chapter, extraction);
                try {
                    projectionService.projectChapter(novelId, chapter.ordinal());
                } catch (Exception e) {
                    log.warn("投影失败（记忆已写入）: novelId={} ordinal={}", novelId, chapter.ordinal(), e);
                }
                memoryRepository.saveExtractionRecord(new MemoryExtractionRecord(
                        novelId, chapter.ordinal(), "SUCCESS", null, "replay",
                        statsFrom(stats, rec), Instant.now()));
                replayed++;
                if (replayed % 200 == 0) {
                    log.info("gzr replay progress replayed={} skipped={} / {}", replayed, skipped, chapters.size());
                }
            } catch (Exception e) {
                skipped++;
                applyFailed.add(chapter.ordinal());
                log.warn("重放失败，跳过: ordinal={} {}", chapter.ordinal(), e.getMessage());
            }
        }

        int embedded = 0;
        String embedError = null;
        if (embed) {
            try {
                embedded = embeddingService.embedNovel(novelId);
            } catch (Exception e) {
                embedError = e.getMessage();
                log.warn("embedNovel 失败（BM25 仍可用）: novelId={}", novelId, e);
            }
        }
        log.info("gzr replay done novelId={} chapters={} replayed={} skipped={} embedded={} applyFailed={}",
                novelId, chapters.size(), replayed, skipped, embedded, applyFailed.size());
        return new ReplayResult(novelId, novelTitle, fileName, chapters.size(),
                replayed, skipped, embedded, embedError, List.copyOf(applyFailed));
    }

    private Map<?, ?> readOutcome(Path file) {
        try {
            if (!Files.exists(file)) {
                return null;
            }
            return objectMapper.readValue(Files.readString(file, StandardCharsets.UTF_8), Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static MemoryExtractionStats statsFrom(MemoryUpdateService.UpdateStats stats, Map<?, ?> rec) {
        return new MemoryExtractionStats(
                stats.charactersCreated,
                stats.factsCreated,
                stats.eventsCreated,
                0, 0, 0,
                number(rec, "ms"),
                new LlmUsage(number(rec, "inTokens"), number(rec, "outTokens")));
    }

    private static int number(Map<?, ?> rec, String key) {
        Object value = rec.get(key);
        if (value instanceof Number n) {
            long v = n.longValue();
            return v > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) v;
        }
        return 0;
    }

    public record ReplayResult(String novelId, String title, String sourceFileName, int chapterCount,
                               int replayed, int skipped, int embeddedChunks, String embedError,
                               List<Integer> applyFailedOrdinals) {
    }
}
