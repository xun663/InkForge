package com.inkforge.common;

/**
 * Counts tokens in text. Used for pre-flight context budgeting (deterministic, offline).
 * The authoritative usage always comes from the LLM provider response — this is an estimate only.
 */
public interface TokenCounter {

    int count(String text);
}
