package com.inkforge.provider;

import java.util.List;

/**
 * A provider-neutral generation request.
 *
 * @param messages        prompt messages (system + user)
 * @param maxOutputTokens upper bound on generated tokens
 * @param temperature     sampling temperature
 * @param model           explicit model name, or null to use the provider default
 * @param taskType        semantic task hint; real providers may specialize on it
 * @param thinking        thinking mode for thinking-capable models (e.g. DeepSeek V4).
 *                        All task types default to {@link ThinkingMode#DISABLED}: DeepSeek V4
 *                        defaults to thinking ON, which can leave {@code content} empty
 *                        (output lands in {@code reasoning_content}). Disabling gives stable
 *                        structured output for extraction/rerank AND visible prose for
 *                        continuation. Explicit {@code ENABLED} is still supported per-request.
 */
public record LlmRequest(List<ChatMessage> messages, int maxOutputTokens, double temperature,
                         String model, TaskType taskType, ThinkingMode thinking) {

    /** Thinking on/off for models that support a reasoning mode (DeepSeek V4, etc.). */
    public enum ThinkingMode { ENABLED, DISABLED }

    public LlmRequest {
        messages = List.copyOf(messages);
        if (taskType == null) {
            taskType = TaskType.CONTINUATION;
        }
        if (thinking == null) {
            thinking = ThinkingMode.DISABLED;
        }
    }

    /** Phase 1 compatibility constructor — defaults to a continuation task. */
    public LlmRequest(List<ChatMessage> messages, int maxOutputTokens, double temperature, String model) {
        this(messages, maxOutputTokens, temperature, model, TaskType.CONTINUATION, null);
    }

    /** Compatibility constructor with taskType; thinking derives from taskType when null. */
    public LlmRequest(List<ChatMessage> messages, int maxOutputTokens, double temperature,
                      String model, TaskType taskType) {
        this(messages, maxOutputTokens, temperature, model, taskType, null);
    }
}
