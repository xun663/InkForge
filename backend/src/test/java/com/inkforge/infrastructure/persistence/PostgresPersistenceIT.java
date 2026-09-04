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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

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
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "inkforge.llm.provider=mock",
                "inkforge.embedding.provider=mock"})
@ActiveProfiles("postgres")
@Testcontainers(disabledWithoutDocker = true)
class PostgresPersistenceIT {

    @Container
    static PostgreSQLContainer<?> postgres = PostgresITSupport.postgres();

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        PostgresITSupport.registerDatasource(postgres, registry);
    }

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
        String novelId = "mem-" + System.nanoTime();
        PostgresITSupport.saveNovel(novelRepository, novelId);
        String characterId = "c1-" + novelId;
        storyMemoryRepository.saveSummary(new ChapterSummary(novelId, 3,
                "林默与血魔对峙，右手受伤，血魔逃离。", List.of("对峙"),
                List.of(new SummaryCharacter("林默", "主角")),
                List.of("后山"), List.of("玄霜剑"), List.of("血魔的行踪"), NOW));
        storyMemoryRepository.saveCharacter(new Character(characterId, novelId, "林默",
                List.of("林小默"), 1, 3, CharacterStatus.ACTIVE, NOW, NOW));
        storyMemoryRepository.saveFact(new CharacterFact("f1-" + novelId, characterId, FactCategory.STATE,
                "当前状态", "右手受伤", null, FactStatus.CURRENT, 3, null, 0.9,
                3, "他试着活动右臂。", NOW, NOW));
        storyMemoryRepository.saveFact(new CharacterFact("f2-" + novelId, characterId, FactCategory.ABILITY,
                "境界", "金丹", null, FactStatus.SUPERSEDED, 1, 3, 0.9,
                1, "第一章。", NOW, NOW));
        storyMemoryRepository.saveEvent(new StoryEvent("e1-" + novelId, novelId, 3, "后山对峙",
                "林默与血魔对峙。", List.of("林默", "血魔"), "后山", List.of("受伤"), 4, "引用。", NOW));
        storyMemoryRepository.saveExtractionRecord(new MemoryExtractionRecord(
                novelId, 3, "SUCCESS", null, "inkforge-mock",
                new MemoryExtractionStats(1, 2, 1, 2, 0, 0, 39, new LlmUsage(885, 498)), NOW));

        assertThat(storyMemoryRepository.findSummary(novelId, 3)).isPresent().get()
                .satisfies(s -> assertThat(s.unresolvedThreads()).containsExactly("血魔的行踪"));
        assertThat(storyMemoryRepository.findCharacters(novelId)).hasSize(1);
        assertThat(storyMemoryRepository.findCharacterByName(novelId, "林小默")).isPresent();
        assertThat(storyMemoryRepository.findCharacterByName(novelId, "林 小 默")).isPresent();
        assertThat(storyMemoryRepository.findCurrentFacts(characterId))
                .extracting(CharacterFact::value)
                .containsExactly("右手受伤");
        assertThat(storyMemoryRepository.findFacts(characterId)).hasSize(2);
        assertThat(storyMemoryRepository.findEvents(novelId, 10, true)).hasSize(1);
        assertThat(storyMemoryRepository.findExtractionRecord(novelId, 3)).isPresent().get()
                .satisfies(r -> assertThat(r.succeeded()).isTrue());
    }

    @Test
    void factMergeIsUpsertByIdMatchingP2Semantics() {
        String novelId = "fact-" + System.nanoTime();
        PostgresITSupport.saveNovel(novelRepository, novelId);
        String characterId = "c2-" + novelId;
        storyMemoryRepository.saveCharacter(new Character(characterId, novelId, "方源",
                List.of(), 1, 1, CharacterStatus.UNKNOWN, NOW, NOW));
        storyMemoryRepository.saveFact(new CharacterFact("fx-" + novelId, characterId, FactCategory.ABILITY,
                "境界", "五转", null, FactStatus.CURRENT, 100, null, 0.9,
                100, "引用。", NOW, NOW));
        storyMemoryRepository.saveFact(new CharacterFact("fx-" + novelId, characterId, FactCategory.ABILITY,
                "境界", "五转", null, FactStatus.SUPERSEDED, 100, 200, 0.9,
                100, "引用。", NOW, NOW));
        storyMemoryRepository.saveFact(new CharacterFact("fy-" + novelId, characterId, FactCategory.ABILITY,
                "境界", "六转", null, FactStatus.CURRENT, 200, null, 0.96,
                200, "引用。", NOW, NOW));

        assertThat(storyMemoryRepository.findFacts(characterId)).hasSize(2);
        assertThat(storyMemoryRepository.findCurrentFacts(characterId))
                .extracting(CharacterFact::value)
                .containsExactly("六转");
    }

    @Test
    void novelWithChaptersRoundTripThroughPostgres() {
        String novelId = "chap-" + System.nanoTime();
        novelRepository.save(new Novel(novelId, "测试小说", "t.txt", List.of(
                new Chapter(0, 1, "拜入山门", "第一章正文。"),
                new Chapter(1, 2, "玄霜剑", "第二章正文。"),
                new Chapter(2, null, "番外", "番外正文。"))));

        Novel back = novelRepository.findById(novelId).orElseThrow();
        assertThat(back.chapterCount()).isEqualTo(3);
        assertThat(back.chapters().get(1).title()).isEqualTo("玄霜剑");
        assertThat(back.chapters().get(2).chapterNo()).isNull();
        assertThat(novelRepository.findAll()).anyMatch(n -> novelId.equals(n.id()));
    }

    @Test
    void generationLogRoundTripThroughPostgres() {
        String novelId = "log-" + System.nanoTime();
        String generationId = "g-" + novelId;
        generationLogRepository.save(new GenerationLog(generationId, novelId, "mock", "inkforge-mock",
                878, 245, 27L, new java.math.BigDecimal("0.0056"), "SUCCESS", null,
                "CONTINUATION", NOW));

        assertThat(generationLogRepository.findById(generationId)).isPresent();
        assertThat(generationLogRepository.findByNovelId(novelId)).hasSize(1);
        assertThat(generationLogRepository.findByNovelId(novelId).getFirst().type())
                .isEqualTo("CONTINUATION");
    }
}
