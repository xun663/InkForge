package com.inkforge.memory;

import com.inkforge.chapter.Chapter;
import com.inkforge.memory.extraction.ChapterExtractionResult;
import com.inkforge.memory.extraction.ExtractedCharacter;
import com.inkforge.memory.extraction.ExtractedEvent;
import com.inkforge.memory.extraction.ExtractedFact;
import com.inkforge.memory.extraction.ExtractedSummary;
import com.inkforge.memory.extraction.ExtractedSummaryCharacter;
import com.inkforge.memory.extraction.MemoryExtractionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The crown-jewel suite of Phase 2: the deterministic merge rules. Everything here
 * runs without an LLM — extracted facts are hand-built inputs.
 */
class MemoryUpdateServiceTest {

    private InMemoryStoryMemoryRepository repository;
    private MemoryUpdateService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryStoryMemoryRepository();
        service = new MemoryUpdateService(repository,
                new MemoryExtractionProperties(3, 12000, 2048, 2048, 0.2, 2, 0.7, 300, 200));
    }

    private static Chapter chapter(int ordinal, String content) {
        return new Chapter(ordinal, ordinal + 1, "第" + (ordinal + 1) + "章", content);
    }

    private static ChapterExtractionResult extraction(ExtractedCharacter... characters) {
        return new ChapterExtractionResult(
                new ExtractedSummary("摘要", List.of("事件"), List.of(
                        new ExtractedSummaryCharacter("林默", "主角")),
                        List.of("后山"), List.of("玄霜剑"), List.of("线索")),
                List.of(characters),
                List.of(new ExtractedEvent("对峙", "对峙描述", List.of("林默"),
                        "后山", List.of(), 4, "原文引用")));
    }

    private static ExtractedFact fact(FactCategory category, String attribute, String value,
                                      String target, double confidence) {
        return new ExtractedFact(category, attribute, value, target, confidence, "原文引用");
    }

    @Test
    void createsNewCharacterWithCurrentFacts() {
        MemoryUpdateService.UpdateStats stats = service.apply("n1", chapter(1, "正文"),
                extraction(new ExtractedCharacter("林默", List.of(), List.of(
                        fact(FactCategory.STATE, "当前状态", "健康", null, 0.9)))));

        Character character = repository.findCharacterByName("n1", "林默").orElseThrow();
        assertThat(character.firstChapter()).isEqualTo(1);
        assertThat(stats.charactersCreated).isEqualTo(1);
        assertThat(repository.findCurrentFacts(character.id()))
                .extracting(CharacterFact::value)
                .containsExactly("健康");
    }

    @Test
    void mergesIntoExistingCharacterAndKeepsSingleIdentity() {
        service.apply("n1", chapter(1, "正文"),
                extraction(new ExtractedCharacter("林默", List.of(), List.of(
                        fact(FactCategory.STATE, "当前状态", "健康", null, 0.9)))));
        service.apply("n1", chapter(2, "正文"),
                extraction(new ExtractedCharacter("林默", List.of(), List.of(
                        fact(FactCategory.ABILITY, "境界", "金丹", null, 0.9)))));

        assertThat(repository.findCharacters("n1")).hasSize(1);
        Character character = repository.findCharacterByName("n1", "林默").orElseThrow();
        assertThat(character.lastChapter()).isEqualTo(2);
        assertThat(repository.findCurrentFacts(character.id())).hasSize(2);
    }

    @Test
    void aliasResolvesToSameCharacter() {
        service.apply("n1", chapter(1, "正文"),
                extraction(new ExtractedCharacter("林默", List.of("林小默"), List.of())));
        service.apply("n1", chapter(2, "正文"),
                extraction(new ExtractedCharacter("林小默", List.of(), List.of(
                        fact(FactCategory.STATE, "当前状态", "受伤", null, 0.9)))));

        assertThat(repository.findCharacters("n1")).hasSize(1);
        Character character = repository.findCharacterByName("n1", "林小默").orElseThrow();
        assertThat(character.name()).isEqualTo("林默");
        assertThat(repository.findCurrentFacts(character.id()))
                .extracting(CharacterFact::value)
                .containsExactly("受伤");
    }

    @Test
    void newAliasIsMergedAndConflictingAliasIsRejected() {
        service.apply("n1", chapter(1, "正文"),
                extraction(new ExtractedCharacter("林默", List.of(), List.of())));
        service.apply("n1", chapter(1, "正文"),
                extraction(new ExtractedCharacter("苏清雪", List.of(), List.of())));

        MemoryUpdateService.UpdateStats stats = service.apply("n1", chapter(2, "正文"),
                extraction(new ExtractedCharacter("林默", List.of("林小默", "苏清雪"), List.of())));

        assertThat(stats.aliasesAdded).isEqualTo(1);
        assertThat(stats.aliasConflicts).isEqualTo(1);
        Character linMo = repository.findCharacterByName("n1", "林默").orElseThrow();
        assertThat(linMo.aliases()).containsExactly("林小默");
        // the conflicting alias must NOT be stolen from 苏清雪
        assertThat(repository.findCharacterByName("n1", "苏清雪")).isPresent();
    }

    @Test
    void stateUpdateSupersedesOldValueAndKeepsTimeline() {
        service.apply("n1", chapter(100, "正文"),
                extraction(new ExtractedCharacter("方源", List.of(), List.of(
                        fact(FactCategory.ABILITY, "境界", "三转", null, 0.9)))));
        service.apply("n1", chapter(200, "正文"),
                extraction(new ExtractedCharacter("方源", List.of(), List.of(
                        fact(FactCategory.ABILITY, "境界", "五转", null, 0.9)))));
        MemoryUpdateService.UpdateStats stats = service.apply("n1", chapter(300, "正文"),
                extraction(new ExtractedCharacter("方源", List.of(), List.of(
                        fact(FactCategory.ABILITY, "境界", "六转", null, 0.96)))));

        Character character = repository.findCharacterByName("n1", "方源").orElseThrow();
        List<CharacterFact> all = repository.findFacts(character.id());

        // 当前境界 = 六转
        assertThat(repository.findCurrentFacts(character.id()))
                .extracting(CharacterFact::value)
                .containsExactly("六转");
        // 历史完整：100 → 三转，200 → 五转，300 → 六转
        assertThat(all.stream().filter(f -> f.status() == FactStatus.SUPERSEDED).toList())
                .extracting(f -> f.validFromChapter() + "→" + f.value())
                .containsExactly("100→三转", "200→五转");
        // 本次调用只取代了"五转"这一条（"三转→五转"的取代发生在上一次调用）
        assertThat(stats.factsSuperseded).isEqualTo(1);
    }

    @Test
    void attributeAliasesCanonicalizeSoUpdatesMatch() {
        service.apply("n1", chapter(1, "正文"),
                extraction(new ExtractedCharacter("方源", List.of(), List.of(
                        fact(FactCategory.ABILITY, "修为", "五转", null, 0.9)))));
        service.apply("n1", chapter(2, "正文"),
                extraction(new ExtractedCharacter("方源", List.of(), List.of(
                        fact(FactCategory.ABILITY, "境界", "六转", null, 0.9)))));

        Character character = repository.findCharacterByName("n1", "方源").orElseThrow();
        assertThat(repository.findCurrentFacts(character.id()))
                .extracting(CharacterFact::value)
                .containsExactly("六转");
        assertThat(repository.findFacts(character.id()).stream()
                .filter(f -> f.status() == FactStatus.SUPERSEDED)).hasSize(1);
    }

    @Test
    void identicalValueIsIgnoredWithoutChurn() {
        service.apply("n1", chapter(1, "正文"),
                extraction(new ExtractedCharacter("林默", List.of(), List.of(
                        fact(FactCategory.STATE, "当前状态", "受伤", null, 0.9)))));
        MemoryUpdateService.UpdateStats stats = service.apply("n1", chapter(2, "正文"),
                extraction(new ExtractedCharacter("林默", List.of(), List.of(
                        fact(FactCategory.STATE, "当前状态", "受伤。", null, 0.85)))));

        Character character = repository.findCharacterByName("n1", "林默").orElseThrow();
        assertThat(stats.factsIgnored).isEqualTo(1);
        assertThat(repository.findFacts(character.id())).hasSize(1);
    }

    @Test
    void lowConfidenceBecomesUncertainAndNeverCurrent() {
        service.apply("n1", chapter(100, "正文"),
                extraction(new ExtractedCharacter("方源", List.of(), List.of(
                        fact(FactCategory.ABILITY, "境界", "六转", null, 0.5)))));

        Character character = repository.findCharacterByName("n1", "方源").orElseThrow();
        assertThat(repository.findCurrentFacts(character.id())).isEmpty();
        assertThat(repository.findFacts(character.id()))
                .extracting(CharacterFact::status)
                .containsExactly(FactStatus.UNCERTAIN);
    }

    @Test
    void uncertainRumorIsNotRefutedByLaterCurrentFact() {
        // Chapter 100：传闻已突破六转
        service.apply("n1", chapter(100, "正文"),
                extraction(new ExtractedCharacter("方源", List.of(), List.of(
                        fact(FactCategory.ABILITY, "境界", "六转", null, 0.5)))));
        // Chapter 110：表现出五转实力 —— 不证伪传闻
        service.apply("n1", chapter(110, "正文"),
                extraction(new ExtractedCharacter("方源", List.of(), List.of(
                        fact(FactCategory.ABILITY, "境界", "五转", null, 0.9)))));

        Character character = repository.findCharacterByName("n1", "方源").orElseThrow();
        List<CharacterFact> all = repository.findFacts(character.id());
        assertThat(repository.findCurrentFacts(character.id()))
                .extracting(CharacterFact::value)
                .containsExactly("五转");
        // the rumor survives untouched
        assertThat(all.stream().filter(f -> f.status() == FactStatus.UNCERTAIN).toList())
                .extracting(CharacterFact::value)
                .containsExactly("六转");
    }

    @Test
    void relationshipFactsWithDifferentTargetsCoexist() {
        service.apply("n1", chapter(1, "正文"),
                extraction(new ExtractedCharacter("方源", List.of(), List.of(
                        fact(FactCategory.RELATIONSHIP, "关系", "敌对", "白凝冰", 0.9),
                        fact(FactCategory.RELATIONSHIP, "关系", "合作", "古月漠尘", 0.9)))));

        Character character = repository.findCharacterByName("n1", "方源").orElseThrow();
        assertThat(repository.findCurrentFacts(character.id())).hasSize(2);
        assertThat(repository.findCurrentFacts(character.id()))
                .extracting(f -> f.targetCharacter() + "=" + f.value())
                .containsExactlyInAnyOrder("白凝冰=敌对", "古月漠尘=合作");
    }

    @Test
    void relationshipUpdateOnSameTargetSupersedesOnlyThatRelation() {
        service.apply("n1", chapter(1, "正文"),
                extraction(new ExtractedCharacter("方源", List.of(), List.of(
                        fact(FactCategory.RELATIONSHIP, "关系", "敌对", "白凝冰", 0.9),
                        fact(FactCategory.RELATIONSHIP, "关系", "合作", "古月漠尘", 0.9)))));
        service.apply("n1", chapter(2, "正文"),
                extraction(new ExtractedCharacter("方源", List.of(), List.of(
                        fact(FactCategory.RELATIONSHIP, "关系", "合作", "白凝冰", 0.9)))));

        Character character = repository.findCharacterByName("n1", "方源").orElseThrow();
        List<CharacterFact> current = repository.findCurrentFacts(character.id());
        assertThat(current).hasSize(2);
        assertThat(current).extracting(f -> f.targetCharacter() + "=" + f.value())
                .containsExactlyInAnyOrder("白凝冰=合作", "古月漠尘=合作");
        // 与古月漠尘的关系未被波及
        assertThat(repository.findFacts(character.id()).stream()
                .filter(f -> f.status() == FactStatus.SUPERSEDED)
                .map(f -> f.targetCharacter() + "=" + f.value()))
                .containsExactly("白凝冰=敌对");
    }

    @Test
    void summaryAndEventsArePersistedWithSourceAnchors() {
        service.apply("n1", chapter(3, "原文"),
                extraction(new ExtractedCharacter("林默", List.of(), List.of(
                        fact(FactCategory.STATE, "当前状态", "受伤", null, 0.9)))));

        ChapterSummary summary = repository.findSummary("n1", 3).orElseThrow();
        assertThat(summary.summary()).isEqualTo("摘要");
        assertThat(summary.unresolvedThreads()).containsExactly("线索");
        assertThat(summary.characters()).extracting(SummaryCharacter::name).containsExactly("林默");

        assertThat(repository.findEvents("n1", 10, true)).hasSize(1);
        assertThat(repository.findEvents("n1", 10, true).getFirst().sourceQuote()).isEqualTo("原文引用");
    }
}
