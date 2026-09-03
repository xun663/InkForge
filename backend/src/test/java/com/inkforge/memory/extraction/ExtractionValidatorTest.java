package com.inkforge.memory.extraction;

import com.inkforge.memory.FactCategory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExtractionValidatorTest {

    private final ExtractionValidator validator = new ExtractionValidator();

    private static final String CHAPTER = "林默缓缓拔出玄霜剑，剑身映出他苍白的脸。\n"
            + "\"这柄剑，认主了。\"长老的声音在身后响起。";

    private static ChapterExtractionResult validResult() {
        return new ChapterExtractionResult(
                new ExtractedSummary("摘要", List.of("事件一"),
                        List.of(new ExtractedSummaryCharacter("林默", "主角")),
                        List.of("后山"), List.of("玄霜剑"), List.of("线索一")),
                List.of(new ExtractedCharacter("林默", List.of("林小默"), List.of(
                        new ExtractedFact(FactCategory.STATE, "当前状态", "受伤", null,
                                0.9, "林默缓缓拔出玄霜剑，剑身映出他苍白的脸。"),
                        new ExtractedFact(FactCategory.RELATIONSHIP, "关系", "敌对", "血魔",
                                0.95, "\"这柄剑，认主了。\"长老的声音在身后响起。")))),
                List.of(new ExtractedEvent("后山对峙", "对峙描述", List.of("林默", "血魔"),
                        "后山", List.of("受伤"), 4, "林默缓缓拔出玄霜剑，剑身映出他苍白的脸。")));
    }

    @Test
    void validResultPassesAndCountsQuotes() {
        ExtractionValidator.ValidationResult result = validator.validate(validResult(), CHAPTER, 300);

        assertThat(result.quotesValidated()).isEqualTo(3);
        assertThat(result.quotesRejected()).isZero();
        assertThat(result.cleaned().characters()).hasSize(1);
        assertThat(result.cleaned().events()).hasSize(1);
    }

    @Test
    void quoteNotSubstringOfChapterIsRejected() {
        ChapterExtractionResult bad = new ChapterExtractionResult(
                new ExtractedSummary("摘要", List.of(), List.of(), List.of(), List.of(), List.of()),
                List.of(new ExtractedCharacter("林默", List.of(), List.of(
                        new ExtractedFact(FactCategory.STATE, "当前状态", "受伤", null,
                                0.9, "这段文字不在原文中。")))),
                List.of());

        ExtractionValidator.ValidationResult result = validator.validate(bad, CHAPTER, 300);

        assertThat(result.quotesRejected()).isEqualTo(1);
        assertThat(result.cleaned().characters()).isEmpty();
    }

    @Test
    void quoteOverLengthCapIsRejected() {
        String longQuote = "长".repeat(301);
        String content = longQuote + "其余原文。";
        ChapterExtractionResult result = new ChapterExtractionResult(
                new ExtractedSummary("摘要", List.of(), List.of(), List.of(), List.of(), List.of()),
                List.of(new ExtractedCharacter("林默", List.of(), List.of(
                        new ExtractedFact(FactCategory.STATE, "当前状态", "受伤", null, 0.9, longQuote)))),
                List.of());

        assertThat(validator.validate(result, content, 300).quotesRejected()).isEqualTo(1);
    }

    @Test
    void confidenceOutOfRangeIsRejected() {
        ChapterExtractionResult result = new ChapterExtractionResult(
                new ExtractedSummary("摘要", List.of(), List.of(), List.of(), List.of(), List.of()),
                List.of(new ExtractedCharacter("林默", List.of(), List.of(
                        new ExtractedFact(FactCategory.STATE, "当前状态", "受伤", null, 1.7, "原文引用")))),
                List.of());

        assertThat(validator.validate(result, "原文引用。", 300).cleaned().characters()).isEmpty();
    }

    @Test
    void relationshipWithoutTargetIsRejected() {
        ChapterExtractionResult result = new ChapterExtractionResult(
                new ExtractedSummary("摘要", List.of(), List.of(), List.of(), List.of(), List.of()),
                List.of(new ExtractedCharacter("林默", List.of(), List.of(
                        new ExtractedFact(FactCategory.RELATIONSHIP, "关系", "敌对", null, 0.9, "原文引用")))),
                List.of());

        assertThat(validator.validate(result, "原文引用。", 300).cleaned().characters()).isEmpty();
    }

    @Test
    void blankSummaryIsStructuralFailure() {
        ChapterExtractionResult result = new ChapterExtractionResult(
                new ExtractedSummary("  ", List.of(), List.of(), List.of(), List.of(), List.of()),
                List.of(), List.of());

        assertThatThrownBy(() -> validator.validate(result, CHAPTER, 300))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("summary");
    }
}
