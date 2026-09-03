package com.inkforge.memory.extraction;

/**
 * Compact JSON schema description embedded into the extraction prompt.
 * Kept next to the DTOs it describes so the two can be kept in sync.
 */
public final class ExtractionSchema {

    private ExtractionSchema() {
    }

    public static String text() {
        return """
                {
                  "summary": {"summary": "...", "keyEvents": ["..."],
                              "characters": [{"name": "...", "role": "主角/配角/反派/其他"}],
                              "locations": ["..."], "importantItems": ["..."],
                              "unresolvedThreads": ["..."]},
                  "characters": [{"name": "...", "aliases": ["..."],
                                  "facts": [{"category": "STATE", "attribute": "当前状态",
                                             "value": "...", "targetCharacter": null,
                                             "confidence": 0.9, "sourceQuote": "原文引用"}]}],
                  "events": [{"title": "...", "description": "...", "participants": ["..."],
                              "location": "...", "consequences": ["..."],
                              "importance": 4, "sourceQuote": "原文引用"}]
                }
                """;
    }
}
