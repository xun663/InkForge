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
import com.inkforge.planning.PlotThread;
import com.inkforge.planning.StoryPlan;
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

    @Test
    void generationLogRoundTripWithPlanningMetadata() {
        GenerationLog domain = new GenerationLog("g2", "n1", "mock", "inkforge-mock",
                878, 245, 27L, new BigDecimal("0.0056"), "SUCCESS", null,
                "PLANNING", "ENDING", "p1", NOW);

        GenerationLog back = GenerationLogMappers.toDomain(GenerationLogMappers.toEntity(domain));

        assertThat(back).isEqualTo(domain);
        assertThat(back.mode()).isEqualTo("ENDING");
        assertThat(back.planId()).isEqualTo("p1");
    }

    @Test
    void storyPlanRoundTrip() {
        StoryPlan domain = new StoryPlan("p1", "n1", com.inkforge.planning.ContinuationMode.ENDING,
                "以终局战作结", "魔门战争决战", "林默对血魔", "终结血魔",
                List.of(new com.inkforge.planning.PlanStep(0, "揭示剑穗", "回溯恩怨", "收束伏笔"),
                        new com.inkforge.planning.PlanStep(1, "最终决战", "决战", "主线收束")),
                List.of("林默"), List.of("血魔行踪成谜"), List.of(), "尽快收束",
                new com.inkforge.planning.EndingAnalysis("魔门战争决战",
                        List.of(new com.inkforge.planning.EndingAnalysis.CharacterArc("林默", "成长弧")),
                        List.of("剑穗来历"), "大战在即", List.of("采药支线"),
                        "林默对血魔", "终结血魔",
                        List.of(new com.inkforge.planning.EndingAnalysis.EndingThread(
                                "血魔行踪成谜", "败退后去向不明", "决战揭露", 1, List.of("血魔")))),
                com.inkforge.planning.PlanStatus.DRAFT, NOW, NOW);

        StoryPlan back = PlanningMappers.toDomain(PlanningMappers.toEntity(domain));

        assertThat(back).isEqualTo(domain);
    }

    @Test
    void plotThreadRoundTripWithWhitespaceNormalization() {
        PlotThread domain = new PlotThread("t1", "n1", "血魔行踪成谜", "败退后去向不明",
                com.inkforge.planning.PlotThreadStatus.OPEN, 1, 6, List.of("血魔"), NOW, NOW);

        PlotThread back = PlanningMappers.toDomain(PlanningMappers.toEntity(domain));

        assertThat(back).isEqualTo(domain);
        // 映射派生归一化键（用于 upsert 匹配与唯一索引）
        com.inkforge.infrastructure.persistence.entity.PlotThreadEntity entity =
                PlanningMappers.toEntity(domain);
        assertThat(entity.getTitleNormalized())
                .isEqualTo(PlotThread.normalized("血魔行踪成谜"));
    }
}
