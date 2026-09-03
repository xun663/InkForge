package com.inkforge.infrastructure.persistence;

import com.inkforge.chapter.Chapter;
import com.inkforge.generation.GenerationLog;
import com.inkforge.memory.ChapterSummary;
import com.inkforge.memory.Character;
import com.inkforge.memory.CharacterFact;
import com.inkforge.memory.CharacterStatus;
import com.inkforge.memory.FactCategory;
import com.inkforge.memory.FactStatus;
import com.inkforge.memory.MemoryExtractionRecord;
import com.inkforge.memory.MemoryExtractionStats;
import com.inkforge.memory.StoryEvent;
import com.inkforge.memory.SummaryCharacter;
import com.inkforge.novel.Novel;
import com.inkforge.provider.LlmUsage;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure round-trip tests for Domain record ↔ JPA entity mapping (no database needed)
 * — the JSONB converters are exercised through these mappings.
 */
class PersistenceMappersTest {

    private static final Instant NOW = Instant.parse("2026-08-16T10:00:00Z");

    @Test
    void chapterSummaryRoundTrip() {
        ChapterSummary domain = new ChapterSummary("n1", 3,
                "林默与血魔对峙。", List.of("对峙", "受伤"),
                List.of(new SummaryCharacter("林默", "主角")),
                List.of("后山"), List.of("玄霜剑"), List.of("血魔的行踪"), NOW);

        ChapterSummary back = StoryMemoryMappers.toDomain(StoryMemoryMappers.toEntity(domain));

        assertThat(back).isEqualTo(domain);
    }

    @Test
    void characterRoundTrip() {
        Character domain = new Character("c1", "n1", "方源", List.of("方老魔", "小方"),
                1, 300, CharacterStatus.ACTIVE, NOW, NOW);

        Character back = StoryMemoryMappers.toDomain(StoryMemoryMappers.toEntity(domain));

        assertThat(back).isEqualTo(domain);
    }

    @Test
    void characterFactRoundTripWithAllLifecycleStates() {
        for (FactStatus status : FactStatus.values()) {
            CharacterFact domain = new CharacterFact("f1", "c1", FactCategory.ABILITY,
                    "境界", "六转", "白凝冰", status, 100, 200, 0.87,
                    300, "原文引用。", NOW, NOW);

            CharacterFact back = StoryMemoryMappers.toDomain(StoryMemoryMappers.toEntity(domain));

            assertThat(back).isEqualTo(domain);
        }
    }

    @Test
    void storyEventRoundTrip() {
        StoryEvent domain = new StoryEvent("e1", "n1", 320, "后山对峙", "林默与血魔对峙。",
                List.of("林默", "血魔"), "后山", List.of("林默受伤"), 4, "引用。", NOW);

        StoryEvent back = StoryMemoryMappers.toDomain(StoryMemoryMappers.toEntity(domain));

        assertThat(back).isEqualTo(domain);
    }

    @Test
    void extractionRecordRoundTrip() {
        MemoryExtractionStats stats = new MemoryExtractionStats(2, 3, 1, 4, 0, 0, 39, new LlmUsage(885, 498));
        MemoryExtractionRecord domain = new MemoryExtractionRecord(
                "n1", 3, "SUCCESS", null, "inkforge-mock", stats, NOW);

        MemoryExtractionRecord back = StoryMemoryMappers.toDomain(StoryMemoryMappers.toEntity(domain));

        assertThat(back).isEqualTo(domain);
    }

    @Test
    void novelWithChaptersRoundTripPreservesOrder() {
        Novel domain = new Novel("n1", "测试小说", "t.txt", List.of(
                new Chapter(0, 1, "拜入山门", "第一章正文。"),
                new Chapter(1, 2, "玄霜剑", "第二章正文。"),
                new Chapter(2, null, "番外", "番外正文。")));

        Novel back = NovelMappers.toDomain(NovelMappers.toEntity(domain));

        assertThat(back.id()).isEqualTo("n1");
        assertThat(back.chapters()).isEqualTo(domain.chapters());
        assertThat(back.chapterCount()).isEqualTo(3);
    }

    @Test
    void generationLogRoundTrip() {
        GenerationLog domain = new GenerationLog("g1", "n1", "mock", "inkforge-mock",
                878, 245, 27L, new BigDecimal("0.0056"), "SUCCESS", null,
                "CONTINUATION", NOW);

        GenerationLog back = GenerationLogMappers.toDomain(GenerationLogMappers.toEntity(domain));

        assertThat(back).isEqualTo(domain);
    }
}
