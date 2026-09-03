package com.inkforge.infrastructure.persistence;

import com.inkforge.chapter.Chapter;
import com.inkforge.generation.GenerationLog;
import com.inkforge.generation.GenerationLogRepository;
import com.inkforge.memory.ChapterSummary;
import com.inkforge.memory.Character;
import com.inkforge.memory.CharacterFact;
import com.inkforge.memory.CharacterStatus;
import com.inkforge.memory.FactCategory;
import com.inkforge.memory.FactStatus;
import com.inkforge.memory.MemoryExtractionRecord;
import com.inkforge.memory.MemoryExtractionStats;
import com.inkforge.memory.StoryEvent;
import com.inkforge.memory.StoryMemoryRepository;
import com.inkforge.memory.SummaryCharacter;
import com.inkforge.novel.Novel;
import com.inkforge.novel.NovelRepository;
import com.inkforge.provider.EmbeddingProperties;
import com.inkforge.provider.LlmUsage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PostgreSQL integration tests against pgvector/pgvector:pg17 via Testcontainers.
 * WITHOUT Docker these are SKIPPED automatically (disabledWithoutDocker) — the
 * default InMemory + Mock path and all unit tests are unaffected.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("postgres")
@Testcontainers(disabledWithoutDocker = true)
class PostgresPersistenceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    @Autowired
    private StoryMemoryRepository storyMemoryRepository;

    @Autowired
    private NovelRepository novelRepository;

    @Autowired
    private GenerationLogRepository generationLogRepository;

    @Autowired
    private EmbeddingProperties embeddingProperties;

    @Autowired
    private DataSource dataSource;

    private static final Instant NOW = Instant.parse("2026-08-16T10:00:00Z");

    @Test
    void flywayCreatesAllTenTables() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public'")) {
            rs.next();
            assertThat(rs.getInt(1)).isGreaterThanOrEqualTo(10);
        }
    }

    @Test
    void vectorColumnDimensionMatchesConfig() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("""
                     SELECT a.atttypmod - 4 FROM pg_attribute a
                     JOIN pg_class c ON a.attrelid = c.oid
                     WHERE c.relname = 'memory_chunk' AND a.attname = 'embedding'
                     """)) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(embeddingProperties.dimension());
        }
    }

    @Test
    void storyMemoryRoundTripThroughPostgres() {
        storyMemoryRepository.saveSummary(new ChapterSummary("n1", 3,
                "林默与血魔对峙，右手受伤，血魔逃离。", List.of("对峙"),
                List.of(new SummaryCharacter("林默", "主角")),
                List.of("后山"), List.of("玄霜剑"), List.of("血魔的行踪"), NOW));
        storyMemoryRepository.saveCharacter(new Character("c1", "n1", "林默",
                List.of("林小默"), 1, 3, CharacterStatus.ACTIVE, NOW, NOW));
        storyMemoryRepository.saveFact(new CharacterFact("f1", "c1", FactCategory.STATE,
                "当前状态", "右手受伤", null, FactStatus.CURRENT, 3, null, 0.9,
                3, "他试着活动右臂。", NOW, NOW));
        storyMemoryRepository.saveFact(new CharacterFact("f2", "c1", FactCategory.ABILITY,
                "境界", "金丹", null, FactStatus.SUPERSEDED, 1, 3, 0.9,
                1, "第一章。", NOW, NOW));
        storyMemoryRepository.saveEvent(new StoryEvent("e1", "n1", 3, "后山对峙",
                "林默与血魔对峙。", List.of("林默", "血魔"), "后山", List.of("受伤"), 4, "引用。", NOW));
        storyMemoryRepository.saveExtractionRecord(new MemoryExtractionRecord(
                "n1", 3, "SUCCESS", null, "inkforge-mock",
                new MemoryExtractionStats(1, 2, 1, 2, 0, 0, 39, new LlmUsage(885, 498)), NOW));

        assertThat(storyMemoryRepository.findSummary("n1", 3)).isPresent().get()
                .satisfies(s -> assertThat(s.unresolvedThreads()).containsExactly("血魔的行踪"));
        assertThat(storyMemoryRepository.findCharacters("n1")).hasSize(1);
        assertThat(storyMemoryRepository.findCharacterByName("n1", "林小默")).isPresent();
        assertThat(storyMemoryRepository.findCharacterByName("n1", "林 小 默")).isPresent();
        assertThat(storyMemoryRepository.findCurrentFacts("c1"))
                .extracting(CharacterFact::value)
                .containsExactly("右手受伤");
        assertThat(storyMemoryRepository.findFacts("c1")).hasSize(2);
        assertThat(storyMemoryRepository.findEvents("n1", 10, true)).hasSize(1);
        assertThat(storyMemoryRepository.findExtractionRecord("n1", 3)).isPresent().get()
                .satisfies(r -> assertThat(r.succeeded()).isTrue());
    }

    @Test
    void factMergeIsUpsertByIdMatchingP2Semantics() {
        storyMemoryRepository.saveCharacter(new Character("c2", "n1", "方源",
                List.of(), 1, 1, CharacterStatus.UNKNOWN, NOW, NOW));
        storyMemoryRepository.saveFact(new CharacterFact("fx", "c2", FactCategory.ABILITY,
                "境界", "五转", null, FactStatus.CURRENT, 100, null, 0.9,
                100, "引用。", NOW, NOW));
        // supersede: same id, new status — must replace, not duplicate
        storyMemoryRepository.saveFact(new CharacterFact("fx", "c2", FactCategory.ABILITY,
                "境界", "五转", null, FactStatus.SUPERSEDED, 100, 200, 0.9,
                100, "引用。", NOW, NOW));
        storyMemoryRepository.saveFact(new CharacterFact("fy", "c2", FactCategory.ABILITY,
                "境界", "六转", null, FactStatus.CURRENT, 200, null, 0.96,
                200, "引用。", NOW, NOW));

        assertThat(storyMemoryRepository.findFacts("c2")).hasSize(2);
        assertThat(storyMemoryRepository.findCurrentFacts("c2"))
                .extracting(CharacterFact::value)
                .containsExactly("六转");
    }

    @Test
    void novelWithChaptersRoundTripThroughPostgres() {
        novelRepository.save(new Novel("n1", "测试小说", "t.txt", List.of(
                new Chapter(0, 1, "拜入山门", "第一章正文。"),
                new Chapter(1, 2, "玄霜剑", "第二章正文。"),
                new Chapter(2, null, "番外", "番外正文。"))));

        Novel back = novelRepository.findById("n1").orElseThrow();
        assertThat(back.chapterCount()).isEqualTo(3);
        assertThat(back.chapters().get(1).title()).isEqualTo("玄霜剑");
        assertThat(back.chapters().get(2).chapterNo()).isNull();
        assertThat(novelRepository.findAll()).hasSize(1);
    }

    @Test
    void generationLogRoundTripThroughPostgres() {
        generationLogRepository.save(new GenerationLog("g1", "n1", "mock", "inkforge-mock",
                878, 245, 27L, new java.math.BigDecimal("0.0056"), "SUCCESS", null,
                "CONTINUATION", NOW));

        assertThat(generationLogRepository.findById("g1")).isPresent();
        assertThat(generationLogRepository.findByNovelId("n1")).hasSize(1);
        assertThat(generationLogRepository.findByNovelId("n1").getFirst().type())
                .isEqualTo("CONTINUATION");
    }
}
