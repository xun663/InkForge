package com.inkforge.memory.extraction;

import com.inkforge.memory.FactCategory;

public record ExtractedFact(
        FactCategory category,
        String attribute,
        String value,
        String targetCharacter,   // required for RELATIONSHIP facts, null otherwise
        double confidence,
        String sourceQuote) {
}
