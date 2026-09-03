package com.inkforge.memory.extraction;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Memory extraction configuration.
 *
 * <p>{@code extractionInputBudget} is the INPUT budget for one extraction call — NOT the
 * model's full context window. The extractor reserves the system prompt, the schema, the
 * user template and {@code extractionReservedOutputTokens} before deciding how much
 * chapter text fits (see Adaptive Extraction in docs/phase2-design.md §19).
 */
@ConfigurationProperties(prefix = "inkforge.memory")
public record MemoryExtractionProperties(
        int extractWindow,
        int extractionInputBudget,
        int extractionReservedOutputTokens,
        int extractionMaxOutputTokens,
        double extractionTemperature,
        int maxRetries,
        double confirmConfidence,
        int sourceQuoteMaxChars,
        int chunkOverlapChars) {

    public MemoryExtractionProperties {
        if (extractWindow <= 0) {
            extractWindow = 3;
        }
        if (extractionInputBudget <= 0) {
            extractionInputBudget = 12000;
        }
        if (extractionReservedOutputTokens <= 0) {
            extractionReservedOutputTokens = 2048;
        }
        if (extractionMaxOutputTokens <= 0) {
            extractionMaxOutputTokens = 2048;
        }
        if (extractionTemperature < 0) {
            extractionTemperature = 0.2;
        }
        if (maxRetries < 0) {
            maxRetries = 2;
        }
        if (confirmConfidence <= 0 || confirmConfidence > 1) {
            confirmConfidence = 0.7;
        }
        if (sourceQuoteMaxChars <= 0) {
            sourceQuoteMaxChars = 300;
        }
        if (chunkOverlapChars < 0) {
            chunkOverlapChars = 200;
        }
    }
}
