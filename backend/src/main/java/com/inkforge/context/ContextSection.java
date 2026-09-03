package com.inkforge.context;

/**
 * A budget allocation rule for one context section. {@code required} means "prioritize" —
 * the section gets its minTokens reserved first, but the global invariant
 * {@code totalTokens <= context-max-tokens} is always supreme: in tiny budgets even
 * required sections are compressed/truncated, never allowed to overflow.
 */
public record ContextSection(int priority, int maxTokens, int minTokens, boolean required) {

    public ContextSection {
        if (priority <= 0) {
            priority = Integer.MAX_VALUE;
        }
        if (maxTokens < 0) {
            maxTokens = 0;
        }
        if (minTokens < 0) {
            minTokens = 0;
        }
        if (maxTokens < minTokens) {
            maxTokens = minTokens;
        }
    }
}
