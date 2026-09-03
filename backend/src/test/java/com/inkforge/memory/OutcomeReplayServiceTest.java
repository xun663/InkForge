package com.inkforge.memory;

import com.inkforge.memory.extraction.ChapterExtractionResult;
import com.inkforge.memory.extraction.ExtractedCharacter;
import com.inkforge.memory.extraction.ExtractedEvent;
import com.inkforge.memory.extraction.ExtractedFact;
import com.inkforge.memory.extraction.ExtractedSummary;
import com.inkforge.memory.extraction.ExtractedSummaryCharacter;
import com.inkforge.memory.extraction.MemoryExtractionProperties;
import com.inkforge.novel.InMemoryNovelRepository;
import com.inkforge.provider.EmbeddingProperties;
import com.inkforge.provider.MockEmbeddingProvider;
import com.inkforge.retrieval.InMemoryChunkEmbeddingStore;
import com.inkforge.retrieval.InMemoryMemoryChunkRepository;
import com.inkforge.retrieval.MemoryChunkProjectionService;
import com.inkforge.retrieval.MemoryEmbeddingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OutcomeReplayServiceTest {

    private InMemoryNovelRepository novels;
    private InMemoryStoryMemoryRepository memory;
    private OutcomeReplayService service;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        novels = new InMemoryNovelRepository();
        memory = new InMemoryStoryMemoryRepository();
        var chunks = new InMemoryMemoryChunkRepository();
        var update = new MemoryUpdateService(memory,
                new MemoryExtractionProperties(3, 12000, 2048, 2048, 0.2, 2, 0.7, 300, 200));
        var projection = new MemoryChunkProjectionService(memory, chunks);
        EmbeddingProperties ep = new EmbeddingProperties("mock", "bge-m3", "https://unused", "", 1024, 16, 120);
        var embedding = new MemoryEmbeddingService(new MockEmbeddingProvider(ep), chunks,
                new InMemoryChunkEmbeddingStore(), ep);
        mapper = new ObjectMapper();
        service = new OutcomeReplayService(novels, memory, update, projection, embedding, mapper);
    }

    @Test
    void replaysOkOutcomesSkipsFailedAndEmbeds(@TempDir Path dir) throws Exception {
        Path source = dir.resolve("gzr.txt");
        Files.writeString(source, """
                目录
                第一节：开窍
                方源睁开眼睛，确认自己回到了五百年前。
                第二节：族学
                开窍大典在即。
                """);
        Path outcomes = dir.resolve("outcomes");
        Files.createDirectories(outcomes);
        writeOutcome(outcomes.resolve("chapter-0.json"), true, extraction("方源", "方源睁开眼睛，确认自己回到了五百年前。"));
        writeOutcome(outcomes.resolve("chapter-1.json"), false, null);

        OutcomeReplayService.ReplayResult result = service.replay(source, dir, "蛊真人", true);

        assertThat(result.chapterCount()).isEqualTo(2);
        assertThat(result.replayed()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.embeddedChunks()).isPositive();
        assertThat(result.embedError()).isNull();
        assertThat(novels.findById(result.novelId())).isPresent();
        assertThat(memory.findSummary(result.novelId(), 0)).isPresent();
        assertThat(memory.findSummary(result.novelId(), 1)).isEmpty();
        assertThat(memory.findExtractionRecord(result.novelId(), 0)).get()
                .extracting(MemoryExtractionRecord::status)
                .isEqualTo("SUCCESS");
        assertThat(memory.findCharacterByName(result.novelId(), "方源")).isPresent();
    }

    private void writeOutcome(Path file, boolean ok, ChapterExtractionResult extraction) throws Exception {
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("ok", ok);
        rec.put("attempts", ok ? 1 : 3);
        rec.put("inTokens", 10);
        rec.put("outTokens", 5);
        rec.put("ms", 20);
        if (ok && extraction != null) {
            rec.put("result", mapper.writeValueAsString(extraction));
        } else {
            rec.put("error", "permanent fail");
        }
        Files.writeString(file, mapper.writeValueAsString(rec));
    }

    private static ChapterExtractionResult extraction(String name, String quote) {
        return new ChapterExtractionResult(
                new ExtractedSummary("摘要", List.of("事件"),
                        List.of(new ExtractedSummaryCharacter(name, "主角")),
                        List.of("古月山寨"), List.of("春秋蝉"), List.of("线索")),
                List.of(new ExtractedCharacter(name, List.of(), List.of(
                        new ExtractedFact(FactCategory.IDENTITY, "身份", "少年", null, 0.9, quote)))),
                List.of(new ExtractedEvent("重生", "回到五百年前", List.of(name),
                        "古月山寨", List.of(), 5, quote)));
    }
}
