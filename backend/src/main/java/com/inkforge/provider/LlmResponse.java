package com.inkforge.provider;

/** Non-streaming completion result. */
public record LlmResponse(String content, LlmUsage usage) {
}
