package com.inkforge.context;

import com.inkforge.chapter.Chapter;
import com.inkforge.novel.Novel;
import org.springframework.stereotype.Component;

/**
 * Phase 1: deterministic, text-based breakpoint analysis.
 * Reports the last chapter and a tail excerpt. No LLM, no semantic plot
 * understanding — that arrives with later phases.
 */
@Component
public class BreakpointAnalyzer {

    private static final String TRUNCATION_PREFIX = "……";

    private final int tailChars;

    public BreakpointAnalyzer(ContextProperties properties) {
        this.tailChars = properties.breakpointTailChars();
    }

    public BreakpointInfo analyze(Novel novel) {
        Chapter last = novel.lastChapter();
        String content = last.content();
        String tail = content.length() > tailChars
                ? TRUNCATION_PREFIX + content.substring(content.length() - tailChars)
                : content;
        return new BreakpointInfo(last.ordinal(), last.chapterNo(), last.title(), tail);
    }
}
