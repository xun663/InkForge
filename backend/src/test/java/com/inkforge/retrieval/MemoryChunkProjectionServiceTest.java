package com.inkforge.retrieval;

import com.inkforge.memory.ChapterSummary;
import com.inkforge.memory.Character;
import com.inkforge.memory.CharacterFact;
import com.inkforge.memory.CharacterStatus;
import com.inkforge.memory.FactCategory;
import com.inkforge.memory.FactStatus;
import com.inkforge.memory.InMemoryStoryMemoryRepository;
import com.inkforge.memory.StoryEvent;
import com.inkforge.memory.SummaryCharacter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Projection rules: SUMMARY / EVENT / SUPERSEDED+FACT / UNCERTAIN+FACT are projected;
 * CURRENT facts are NEVER projected; re-projection is idempotent; sourceIds trace back
 * to P2 entities; novels never mix.
 */
class MemoryChunkProjectionServiceTest {

    private InMemoryStoryMemoryRepository memoryRepository;
    private InMemoryMemoryChunkRepository chunkRepository;
    private MemoryChunkProjectionService projectionService;

    private static final Instant NOW = Instant.parse("2026-08-16T10:00:00Z");

    @BeforeEach
    void setUp() {
        memoryRepository = new InMemoryStoryMemoryRepository();
        chunkRepository = new InMemoryMemoryChunkRepository();
        projectionService = new MemoryChunkProjectionService(memoryRepository, chunkRepository);
    }

    private void seedChapterMemory(String novelId, int ordinal) {
        memoryRepository.saveSummary(new ChapterSummary(novelId, ordinal,
                "林默与血魔对峙，右手受伤，血魔逃离。", List.of("对峙", "受伤"),
                List.of(new SummaryCharacter("林默", "主角")),
                List.of("后山"), List.of("玄霜剑"), List.of("血魔的行踪"), NOW));
        Character linMo = memoryRepository.saveCharacter(new Character(
                "c-" + novelId, novelId, "林默", List.of("林小默"), 0, ordinal,
                CharacterStatus.ACTIVE, NOW, NOW));
        memoryRepository.saveFact(new CharacterFact("f-cur-" + novelId, linMo.id(),
                FactCategory.STATE, "当前状态", "右手受伤", null, FactStatus.CURRENT,
                ordinal, null, 0.9, ordinal, "他试着活动右臂。", NOW, NOW));
        memoryRepository.saveFact(new CharacterFact("f-sup-" + novelId, linMo.id(),
                FactCategory.ABILITY, "境界", "金丹", null, FactStatus.SUPERSEDED,
                ordinal - 1, ordinal, 0.9, ordinal, "第一章。", NOW, NOW));
        memoryRepository.saveFact(new CharacterFact("f-unc-" + novelId, linMo.id(),
                FactCategory.ABILITY, "境界", "元婴", null, FactStatus.UNCERTAIN,
                ordinal, null, 0.5, ordinal, "传闻。", NOW, NOW));
        memoryRepository.saveEvent(new StoryEvent("e-" + novelId, novelId, ordinal,
                "后山对峙", "林默与血魔对峙。", List.of("林默", "血魔"),
                "后山", List.of("林默受伤"), 4, "引用。", NOW));
    }

    @Test
    void projectsSummaryFactsAndEventsButNeverCurrentFacts() {
        seedChapterMemory("n1", 3);
        projectionService.projectChapter("n1", 3);

        List<MemoryChunk> chunks = chunkRepository.findByNovelIdAndChapter("n1", 3);
        assertThat(chunks).hasSize(4); // SUMMARY + SUPERSEDED + UNCERTAIN + EVENT
        assertThat(chunks).extracting(MemoryChunk::memoryType)
                .containsExactlyInAnyOrder(MemoryChunkType.SUMMARY, MemoryChunkType.FACT,
                        MemoryChunkType.FACT, MemoryChunkType.EVENT);
        // CURRENT fact 绝不进入 chunk
        assertThat(chunks).noneMatch(c -> c.text().contains("当前状态=右手受伤"));
        assertThat(chunks.stream().filter(c -> c.memoryType() == MemoryChunkType.FACT))
                .extracting(MemoryChunk::text)
                .anyMatch(t -> t.contains("历史状态"))
                .anyMatch(t -> t.contains("传闻"));
    }

    @Test
    void sourceIdsTraceBackToP2Entities() {
        seedChapterMemory("n1", 3);
        projectionService.projectChapter("n1", 3);

        assertThat(chunkRepository.findByNovelIdAndChapter("n1", 3))
                .anySatisfy(c -> {
                    assertThat(c.memoryType()).isEqualTo(MemoryChunkType.EVENT);
                    assertThat(c.sourceId()).isEqualTo("e-n1");
                })
                .anySatisfy(c -> {
                    assertThat(c.memoryType()).isEqualTo(MemoryChunkType.SUMMARY);
                    assertThat(c.sourceId()).isEqualTo("n1:3");
                });
        // 事实 chunk 的 sourceId = 原 CharacterFact.id，可直接追溯原文
        assertThat(chunkRepository.findByNovelIdAndChapter("n1", 3))
                .filteredOn(c -> c.memoryType() == MemoryChunkType.FACT)
                .extracting(MemoryChunk::sourceId)
                .containsExactlyInAnyOrder("f-sup-n1", "f-unc-n1");
    }

    @Test
    void chapterOrdinalIsPreserved() {
        seedChapterMemory("n1", 7);
        projectionService.projectChapter("n1", 7);

        assertThat(chunkRepository.findByNovelIdAndChapter("n1", 7))
                .allMatch(c -> c.chapterOrdinal() == 7);
    }

    @Test
    void repeatedProjectionIsIdempotentWithDeterministicIds() {
        seedChapterMemory("n1", 3);
        projectionService.projectChapter("n1", 3);
        List<MemoryChunk> first = chunkRepository.findByNovelIdAndChapter("n1", 3);

        projectionService.projectChapter("n1", 3);
        List<MemoryChunk> second = chunkRepository.findByNovelIdAndChapter("n1", 3);

        assertThat(second).hasSize(first.size());
        assertThat(second).extracting(MemoryChunk::id)
                .containsExactlyInAnyOrderElementsOf(first.stream().map(MemoryChunk::id).toList());
    }

    @Test
    void differentNovelsNeverMixChunks() {
        seedChapterMemory("n1", 3);
        seedChapterMemory("n2", 3);
        projectionService.projectChapter("n1", 3);
        projectionService.projectChapter("n2", 3);

        assertThat(chunkRepository.findByNovelId("n1"))
                .allMatch(c -> c.novelId().equals("n1"));
        assertThat(chunkRepository.findByNovelId("n1")).hasSize(4);
        assertThat(chunkRepository.findByNovelId("n2")).hasSize(4);
    }
}
