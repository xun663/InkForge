package com.inkforge.context;

import com.inkforge.provider.ChatMessage;

import java.util.List;

/**
 * Context build outcome: the messages plus retrieval observability (trace id and
 * retrieved count). Trace fields are null/0 when no retrieval ran or it failed —
 * the continuation pipeline never depends on them.
 */
public record ContextBuildResult(List<ChatMessage> messages,
                                 String retrievalTraceId,
                                 int retrievedCount) {

    public ContextBuildResult {
        messages = List.copyOf(messages);
    }
}
