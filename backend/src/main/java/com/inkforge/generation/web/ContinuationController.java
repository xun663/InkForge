package com.inkforge.generation.web;

import com.inkforge.common.web.ApiError;
import com.inkforge.common.web.SseEvents;
import com.inkforge.generation.ContinuationService;
import com.inkforge.generation.GenerationEvent;
import com.inkforge.generation.GenerationLog;
import com.inkforge.generation.GenerationLogRepository;
import com.inkforge.generation.GenerationOptions;
import com.inkforge.planning.ContinuationIntent;
import com.inkforge.planning.ContinuationMode;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * SSE continuation endpoint. Event protocol (all data JSON-encoded):
 * <pre>
 *   event: token   data: "增量文本"
 *   event: done    data: {generationId, provider, model, promptTokens, ...}
 *   event: error   data: {message}
 * </pre>
 */
@RestController
@RequestMapping("/api/novels/{novelId}/continuations")
public class ContinuationController {

    private static final long SSE_TIMEOUT_MS = 10 * 60 * 1000L;

    private final ContinuationService continuationService;
    private final GenerationLogRepository generationLogRepository;
    private final ObjectMapper objectMapper;

    public ContinuationController(ContinuationService continuationService,
                                  GenerationLogRepository generationLogRepository,
                                  ObjectMapper objectMapper) {
        this.continuationService = continuationService;
        this.generationLogRepository = generationLogRepository;
        this.objectMapper = objectMapper;
    }

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter continueNovel(@PathVariable String novelId,
                                    @RequestBody(required = false) ContinuationRequestDto request) {
        GenerationOptions options = request == null
                ? new GenerationOptions(null, null)
                : new GenerationOptions(request.maxOutputTokens(), request.temperature());
        // P6：mode 用字符串接收（手动解析 → 未知值走 400，避免枚举反序列化异常漏成 500）；
        // mode/planId 均空 = legacy 续写，行为与 P5 字节级一致
        ContinuationIntent intent = request == null ? ContinuationIntent.legacy()
                : new ContinuationIntent(
                        request.mode() == null ? null : ContinuationMode.parse(request.mode()),
                        request.planId(), request.stepIndex(), request.userInstruction());

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        Disposable subscription = continuationService.streamContinuation(novelId, options, intent)
                .subscribe(
                        event -> emit(emitter, event),
                        error -> {
                            emitError(emitter, error.getMessage());
                            emitter.complete();
                        },
                        emitter::complete);
        emitter.onCompletion(subscription::dispose);
        emitter.onTimeout(subscription::dispose);
        emitter.onError(e -> subscription.dispose());
        return emitter;
    }

    @GetMapping
    public List<GenerationLog> logs(@PathVariable String novelId) {
        return generationLogRepository.findByNovelId(novelId);
    }

    private void emit(SseEmitter emitter, GenerationEvent event) {
        switch (event) {
            case GenerationEvent.Token token -> SseEvents.send(emitter, "token", token.delta(), objectMapper);
            case GenerationEvent.Done done -> SseEvents.send(emitter, "done", done.meta(), objectMapper);
            case GenerationEvent.Error error -> SseEvents.send(emitter, "error", error.message(), objectMapper);
        }
    }

    private void emitError(SseEmitter emitter, String message) {
        try {
            SseEvents.send(emitter, "error",
                    new ApiError(502, "generation_failed", message == null ? "生成失败" : message), objectMapper);
            emitter.complete();
        } catch (Exception ignored) {
            // client may already be gone
        }
    }

    /**
     * P6 起支持规划字段：mode（PLOT_CHOICE/ENDING/EXPANSION，字符串手动解析）、
     * planId（已确认的 StoryPlan）、stepIndex（ENDING 的当前阶段，0 基）、userInstruction。
     * 全部可空；mode+planId 均空 = 旧版直接续写。
     */
    public record ContinuationRequestDto(Integer maxOutputTokens, Double temperature,
                                         String mode, String planId,
                                         Integer stepIndex, String userInstruction) {
    }
}
