package com.inkforge.memory;

import com.inkforge.chapter.Chapter;
import com.inkforge.memory.extraction.ChapterExtractionResult;
import com.inkforge.memory.extraction.ExtractedCharacter;
import com.inkforge.memory.extraction.ExtractedEvent;
import com.inkforge.memory.extraction.ExtractedFact;
import com.inkforge.memory.extraction.MemoryExtractionProperties;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Deterministic merge of extracted facts into Story Memory. The LLM extracts;
 * THIS class decides create / update / supersede / ignore / uncertain — never
 * the model, and never a raw INSERT of whatever the model returned.
 *
 * <p>Fact identity (§6 of the design):
 * regular facts (characterId, category, canonicalAttribute);
 * relationship facts additionally key on targetCharacter, so relations to
 * DIFFERENT targets coexist and never overwrite each other.
 *
 * <p>UNCERTAIN facts are kept independently: a later CURRENT fact never
 * auto-refutes them (evidence resolution is a Phase 5 concern).
 */
@Component
public class MemoryUpdateService {

    private static final Map<String, String> ATTRIBUTE_ALIASES = Map.ofEntries(
            Map.entry("修为", "境界"), Map.entry("实力", "境界"),
            Map.entry("宗门", "所属势力"), Map.entry("门派", "所属势力"), Map.entry("势力", "所属势力"),
            Map.entry("状态", "当前状态"),
            Map.entry("法宝", "武器"), Map.entry("兵器", "武器"));

    private final StoryMemoryRepository repository;
    private final double confirmConfidence;

    public MemoryUpdateService(StoryMemoryRepository repository, MemoryExtractionProperties properties) {
        this.repository = repository;
        this.confirmConfidence = properties.confirmConfidence();
    }

    public UpdateStats apply(String novelId, Chapter chapter, ChapterExtractionResult extraction) {
        UpdateStats stats = new UpdateStats();
        Instant now = Instant.now();

        // --- chapter summary (pure story content) ---
        repository.saveSummary(new ChapterSummary(
                novelId, chapter.ordinal(),
                extraction.summary().summary(),
                extraction.summary().keyEvents(),
                extraction.summary().characters().stream()
                        .map(c -> new SummaryCharacter(c.name(), c.role())).toList(),
                extraction.summary().locations(),
                extraction.summary().importantItems(),
                extraction.summary().unresolvedThreads(),
                now));

        // --- characters & facts ---
        for (ExtractedCharacter extracted : extraction.characters()) {
            Character character = resolveOrCreate(novelId, chapter, extracted, stats, now);
            applyFacts(character, chapter, extracted.facts(), stats, now);
        }

        // --- events ---
        for (ExtractedEvent event : extraction.events()) {
            repository.saveEvent(new StoryEvent(
                    UUID.randomUUID().toString(), novelId, chapter.ordinal(),
                    event.title(), event.description(), event.participants(), event.location(),
                    event.consequences(), event.importance(), event.sourceQuote(), now));
            stats.eventsCreated++;
        }
        return stats;
    }

    private Character resolveOrCreate(String novelId, Chapter chapter,
                                      ExtractedCharacter extracted, UpdateStats stats, Instant now) {
        Character existing = repository.findCharacterByName(novelId, extracted.name()).orElse(null);
        if (existing == null) {
            Character created = new Character(
                    UUID.randomUUID().toString(), novelId, normalize(extracted.name()),
                    normalizeAliases(extracted.aliases()),
                    chapter.ordinal(), chapter.ordinal(), CharacterStatus.UNKNOWN, now, now);
            repository.saveCharacter(created);
            stats.charactersCreated++;
            return created;
        }
        // merge aliases — conservative: only non-colliding ones, never rename
        List<String> merged = new ArrayList<>(existing.aliases());
        for (String alias : extracted.aliases()) {
            String normalized = normalize(alias);
            if (normalized.isEmpty() || normalize(existing.name()).equals(normalized)
                    || merged.stream().anyMatch(a -> normalize(a).equals(normalized))) {
                continue;
            }
            if (repository.findCharacterByName(novelId, normalized).isPresent()) {
                stats.aliasConflicts++; // belongs to someone else — never steal it
                continue;
            }
            merged.add(alias.strip());
            stats.aliasesAdded++;
        }
        Character updated = new Character(
                existing.id(), existing.novelId(), existing.name(), merged,
                existing.firstChapter(), chapter.ordinal(), existing.status(),
                existing.createdAt(), now);
        repository.saveCharacter(updated);
        stats.charactersUpdated++;
        return updated;
    }

    private void applyFacts(Character character, Chapter chapter,
                            List<ExtractedFact> facts, UpdateStats stats, Instant now) {
        for (ExtractedFact fact : facts) {
            String key = factKey(fact);
            CharacterFact current = repository.findCurrentFacts(character.id()).stream()
                    .filter(f -> factKey(f).equals(key))
                    .findFirst()
                    .orElse(null);

            if (current == null) {
                repository.saveFact(toFact(character.id(), chapter, fact,
                        fact.confidence() >= confirmConfidence ? FactStatus.CURRENT : FactStatus.UNCERTAIN, now));
                if (fact.confidence() >= confirmConfidence) {
                    stats.factsCreated++;
                } else {
                    stats.factsUncertain++;
                }
                continue;
            }
            if (sameValue(current.value(), fact.value())) {
                stats.factsIgnored++;
                continue;
            }
            if (fact.confidence() >= confirmConfidence) {
                // supersede the old CURRENT and append the new one — history is preserved
                repository.saveFact(new CharacterFact(
                        current.id(), current.characterId(), current.category(), current.attribute(),
                        current.value(), current.targetCharacter(), FactStatus.SUPERSEDED,
                        current.validFromChapter(), chapter.ordinal(), current.confidence(),
                        current.sourceChapter(), current.sourceQuote(), current.createdAt(), now));
                repository.saveFact(toFact(character.id(), chapter, fact, FactStatus.CURRENT, now));
                stats.factsSuperseded++;
                stats.factsCreated++;
            } else {
                // rumor: kept independently, never touches the CURRENT fact
                repository.saveFact(toFact(character.id(), chapter, fact, FactStatus.UNCERTAIN, now));
                stats.factsUncertain++;
            }
        }
    }

    private CharacterFact toFact(String characterId, Chapter chapter, ExtractedFact fact,
                                 FactStatus status, Instant now) {
        return new CharacterFact(
                UUID.randomUUID().toString(), characterId, fact.category(),
                canonicalAttribute(fact.attribute()), fact.value().strip(),
                fact.targetCharacter() == null ? null : normalize(fact.targetCharacter()),
                status, chapter.ordinal(), null, fact.confidence(),
                chapter.ordinal(), fact.sourceQuote(), now, now);
    }

    /** Identity of a fact: what makes two facts "the same fact". */
    private static String factKey(CharacterFact fact) {
        return factKey(fact.category(), fact.attribute(), fact.targetCharacter());
    }

    private static String factKey(ExtractedFact fact) {
        return factKey(fact.category(), canonicalAttribute(fact.attribute()), fact.targetCharacter());
    }

    private static String factKey(FactCategory category, String attribute, String targetCharacter) {
        if (category == FactCategory.RELATIONSHIP) {
            return "REL:" + attribute + "→" + normalize(targetCharacter);
        }
        return category + ":" + attribute;
    }

    /** Canonical attribute names — 修为/实力 → 境界 etc., so updates match across wording drift. */
    private static String canonicalAttribute(String attribute) {
        String trimmed = attribute == null ? "" : attribute.strip();
        return ATTRIBUTE_ALIASES.getOrDefault(trimmed, trimmed);
    }

    /** Value equality tolerant to trailing punctuation churn (五转 vs 五转。). */
    private static boolean sameValue(String a, String b) {
        return normalizeValue(a).equals(normalizeValue(b));
    }

    private static String normalizeValue(String value) {
        if (value == null) {
            return "";
        }
        String v = value.strip();
        while (!v.isEmpty() && "。，！？；、".indexOf(v.charAt(v.length() - 1)) >= 0) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }

    private static String normalize(String name) {
        return name == null ? "" : name.replaceAll("\\s+", "").strip();
    }

    private static List<String> normalizeAliases(List<String> aliases) {
        List<String> normalized = new ArrayList<>();
        for (String alias : aliases) {
            String n = normalize(alias);
            if (!n.isEmpty()) {
                normalized.add(alias.strip());
            }
        }
        return normalized;
    }

    /** Observable per-run counters — the primary assertion target of the update tests. */
    public static class UpdateStats {
        public int charactersCreated;
        public int charactersUpdated;
        public int aliasesAdded;
        public int aliasConflicts;
        public int factsCreated;
        public int factsSuperseded;
        public int factsIgnored;
        public int factsUncertain;
        public int eventsCreated;
    }
}
