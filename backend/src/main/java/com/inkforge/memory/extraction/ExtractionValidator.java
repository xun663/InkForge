package com.inkforge.memory.extraction;

import com.inkforge.memory.FactCategory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic validation of LLM extraction output, in two tiers:
 * <ul>
 *   <li><b>structural</b> (throws → triggers an extraction retry): unparseable JSON already
 *       failed earlier; here: missing/blank summary</li>
 *   <li><b>per-item</b> (drops the offending item, never retries): invalid quote (not a
 *       verbatim substring of the chapter, or over the length cap), confidence out of
 *       range, blank names, relationship facts without a target</li>
 * </ul>
 * Quote validation is what makes every stored memory traceable to the original text.
 */
@Component
public class ExtractionValidator {

    public ValidationResult validate(ChapterExtractionResult result, String chapterContent, int quoteMaxChars) {
        if (result == null || result.summary() == null || isBlank(result.summary().summary())) {
            throw new IllegalArgumentException("提取结果缺少有效的 summary 字段");
        }

        int quotesValidated = 0;
        int quotesRejected = 0;
        List<ExtractedCharacter> cleanCharacters = new ArrayList<>();
        for (ExtractedCharacter character : result.characters()) {
            if (isBlank(character.name())) {
                continue;
            }
            List<ExtractedFact> cleanFacts = new ArrayList<>();
            for (ExtractedFact fact : character.facts()) {
                if (fact.category() == null || isBlank(fact.attribute()) || isBlank(fact.value())
                        || fact.confidence() < 0 || fact.confidence() > 1) {
                    continue;
                }
                if (fact.category() == FactCategory.RELATIONSHIP && isBlank(fact.targetCharacter())) {
                    continue; // relationship without a target is meaningless
                }
                if (fact.sourceQuote() == null) {
                    continue; // quote is required by the extraction contract
                }
                if (quoteInvalid(fact.sourceQuote(), chapterContent, quoteMaxChars)) {
                    quotesRejected++;
                    continue;
                }
                quotesValidated++;
                cleanFacts.add(fact);
            }
            if (!cleanFacts.isEmpty()) {
                cleanCharacters.add(new ExtractedCharacter(character.name(), character.aliases(), cleanFacts));
            }
        }

        List<ExtractedEvent> cleanEvents = new ArrayList<>();
        for (ExtractedEvent event : result.events()) {
            if (isBlank(event.title()) || isBlank(event.description())) {
                continue;
            }
            if (event.sourceQuote() == null || quoteInvalid(event.sourceQuote(), chapterContent, quoteMaxChars)) {
                quotesRejected++;
                continue;
            }
            quotesValidated++;
            cleanEvents.add(event);
        }

        ChapterExtractionResult cleaned = new ChapterExtractionResult(result.summary(), cleanCharacters, cleanEvents);
        return new ValidationResult(cleaned, quotesValidated, quotesRejected);
    }

    private static boolean quoteInvalid(String quote, String chapterContent, int quoteMaxChars) {
        if (quote.isBlank() || quote.length() > quoteMaxChars) {
            return true;
        }
        return !chapterContent.contains(quote.strip());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    public record ValidationResult(ChapterExtractionResult cleaned,
                                   int quotesValidated, int quotesRejected) {
    }
}
