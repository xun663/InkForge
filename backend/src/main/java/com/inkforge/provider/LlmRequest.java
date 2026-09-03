package com.inkforge.provider;

import java.util.List;

/**
 * A provider-neutral generation request.
 *
 * @param messages        prompt messages (system + user)
 * @param maxOutputTokens upper bound on generated tokens
 * @param temperature     sampling temperature
 * @param model           explicit model name, or null to use the provider default
 * @param taskType        semantic task hint; real providers ignore it, the Mock specializes on it
 */
public record LlmRequest(List<ChatMessage> messages, int maxOutputTokens, double temperature,
                         String model, TaskType taskType) {

    public LlmRequest {
        messages = List.copyOf(messages);
        if (taskType == null) {
            taskType = TaskType.CONTINUATION;
        }
    }

    /** Phase 1 compatibility constructor — defaults to a continuation task. */
    public LlmRequest(List<ChatMessage> messages, int maxOutputTokens, double temperature, String model) {
        this(messages, maxOutputTokens, temperature, model, TaskType.CONTINUATION);
    }
}
