package com.inkforge.generation;

/** Optional per-request overrides; null fields fall back to configured defaults. */
public record GenerationOptions(Integer maxOutputTokens, Double temperature) {
}
