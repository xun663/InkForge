package com.inkforge.memory;

import com.inkforge.provider.LlmUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryStoryMemoryRepositoryTest {

    private InMemoryStoryMemoryRepository repository;

    @BeforeEach
    void setUp() {
        repository = new InMemoryStoryMemoryRepository();
    }

    private static Character character(String id, String novelId, String name, List<String> aliases) {
        return new Character(id, novelId, name, aliases, 1, 3, CharacterStatus.ACTIVE,
                Instant.now(), Instant.now());
    }

    private static CharacterFact fact(String id, String characterId, FactCategory category,
                                      String attribute, String value, FactStatus status) {
        return new CharacterFact(id, characterId, category, attribute, value, null, status,
                3, null, 0.9, 3, "原文引用", Instant.now(), Instant.now());
    }

    @Test
    void findsCharactersByExactNameAndAlias() {
        repository.saveCharacter(character("c1", "n1", "林默", List.of("林小默", "小默")));

        assertThat(repository.findCharacterByName("n1", "林默")).isPresent();
        assertThat(repository.findCharacterByName("n1", "林小默")).isPresent();
        assertThat(repository.findCharacterByName("n1", "林 小 默")).isPresent(); // whitespace normalized
        assertThat(repository.findCharacterByName("n1", "林二")).isEmpty();
        assertThat(repository.findCharacterByName("other-novel", "林默")).isEmpty();
    }

    @Test
    void factsAreQueriedByLifecycleStatus() {
        repository.saveFact(fact("f1", "c1", FactCategory.STATE, "当前状态", "受伤", FactStatus.CURRENT));
        repository.saveFact(fact("f2", "c1", FactCategory.STATE, "当前状态", "健康", FactStatus.SUPERSEDED));
        repository.saveFact(fact("f3", "c1", FactCategory.ABILITY, "境界", "六转", FactStatus.UNCERTAIN));

        assertThat(repository.findFacts("c1")).hasSize(3);
        assertThat(repository.findCurrentFacts("c1"))
                .extracting(CharacterFact::id)
                .containsExactly("f1");
        assertThat(repository.findCurrentFacts("unknown")).isEmpty();
    }

    @Test
    void summariesAndEventsAreFoundByChapterRange() {
        repository.saveSummary(new ChapterSummary("n1", 1, "摘要一", List.of(), List.of(),
                List.of(), List.of(), List.of(), Instant.now()));
        repository.saveSummary(new ChapterSummary("n1", 3, "摘要三", List.of(), List.of(),
                List.of(), List.of(), List.of(), Instant.now()));

        assertThat(repository.findSummary("n1", 1)).isPresent();
        assertThat(repository.findSummary("n1", 2)).isEmpty();
        assertThat(repository.findSummaries("n1", 0, 5)).hasSize(2);
        assertThat(repository.findSummaries("n1", 3, 3))
                .extracting(ChapterSummary::chapterOrdinal)
                .containsExactly(3);

        repository.saveEvent(new StoryEvent("e1", "n1", 3, "后山对峙", "对峙", List.of("林默"),
                "后山", List.of(), 4, "引用", Instant.now()));
        repository.saveEvent(new StoryEvent("e2", "n1", 1, "入门", "入门", List.of("林默"),
                "山门", List.of(), 2, "引用", Instant.now()));

        assertThat(repository.findEvents("n1", 10, true))
                .extracting(StoryEvent::id)
                .containsExactly("e1", "e2");
        assertThat(repository.findEvents("n1", 1, true))
                .extracting(StoryEvent::id)
                .containsExactly("e1");
    }

    @Test
    void extractionRecordsKeepLatestPerChapter() {
        MemoryExtractionStats stats = new MemoryExtractionStats(2, 3, 1, 3, 0, 0, 100, new LlmUsage(1, 1));
        repository.saveExtractionRecord(new MemoryExtractionRecord(
                "n1", 3, "SUCCESS", null, "inkforge-mock", stats, Instant.now()));

        assertThat(repository.findExtractionRecord("n1", 3)).isPresent().get()
                .satisfies(r -> assertThat(r.succeeded()).isTrue());
        assertThat(repository.findExtractionRecord("n1", 2)).isEmpty();
        assertThat(repository.findExtractionRecords("n1")).hasSize(1);
    }
}
