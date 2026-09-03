package com.inkforge.common.web;

import tools.jackson.databind.ObjectMapper;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * SSE helpers. All event data is JSON-encoded so embedded newlines in streamed
 * text can never break the SSE wire protocol.
 */
public final class SseEvents {

    private SseEvents() {
    }

    public static void send(SseEmitter emitter, String eventName, Object data, ObjectMapper objectMapper) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(objectMapper.writeValueAsString(data)));
        } catch (IOException e) {
            throw new IllegalStateException("SSE 事件发送失败: " + eventName, e);
        }
    }
}
