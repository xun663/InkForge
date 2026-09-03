package com.inkforge.memory;

/**
 * Lifecycle status of a character fact.
 * CURRENT: the active belief. SUPERSEDED: historical (validUntilChapter set).
 * UNCERTAIN: rumor/implication, kept independently — never auto-refuted by a CURRENT fact
 * (evidence resolution is a P5 concern).
 */
public enum FactStatus {
    CURRENT, SUPERSEDED, UNCERTAIN
}
