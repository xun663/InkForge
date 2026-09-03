package com.inkforge.memory;

import java.time.Instant;

/**
 * Atomic character fact with lifecycle. The "current state" of a character is a QUERY
 * (status=CURRENT facts), never a separately maintained blob that can drift from history.
 *
 * <p>Fact key (what makes two facts "the same fact"):
 * <ul>
 *   <li>regular facts: (characterId, category, canonicalAttribute)</li>
 *   <li>relationship facts: (characterId, RELATIONSHIP, canonicalAttribute, targetCharacter)
 *       — relations to DIFFERENT targets coexist and never overwrite each other</li>
 * </ul>
 */
public record CharacterFact(
        String id,
        String characterId,          // subject — reserves subject/target semantics for later phases
        FactCategory category,
        String attribute,            // canonical attribute name: 境界/所属势力/当前状态/武器/关系…
        String value,
        String targetCharacter,      // non-null for RELATIONSHIP facts: the other party's name
        FactStatus status,
        int validFromChapter,
        Integer validUntilChapter,   // null = still in effect; set when superseded
        double confidence,
        int sourceChapter,
        String sourceQuote,          // verbatim substring of the source chapter (≤300 chars)
        Instant createdAt,
        Instant updatedAt) {
}
