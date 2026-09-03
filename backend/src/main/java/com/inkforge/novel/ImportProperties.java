package com.inkforge.novel;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Import-layer resource protection limits.
 *
 * <p>These are DEFENSIVE resource guards, not normal-path limits, and are completely
 * decoupled from the LLM extraction budget ({@code inkforge.memory.extraction-input-budget}):
 * import is always 文件 → Chapter → per-chapter/window Memory extraction — the whole
 * novel is never sent to an LLM in one piece. A novel of tens of MB (e.g. 蛊真人,
 * 600-750 万字) must be importable.
 */
@ConfigurationProperties(prefix = "inkforge.import")
public record ImportProperties(long maxFileSize, int maxChapters, int maxChapterChars) {

    public ImportProperties {
        if (maxFileSize <= 0) {
            maxFileSize = 104_857_600L; // 100MB
        }
        if (maxChapters <= 0) {
            maxChapters = 10_000;
        }
        if (maxChapterChars <= 0) {
            maxChapterChars = 100_000;
        }
    }
}
