package com.inkforge.provider;

/** What a completion is used for. Providers MAY specialize behavior (the Mock does); real providers ignore it. */
public enum TaskType {
    CONTINUATION,
    MEMORY_EXTRACTION,
    RERANK
}
