package com.inkforge.generation;

import com.inkforge.chapter.Chapter;
import com.inkforge.common.LlmException;
import com.inkforge.common.NotFoundException;
import com.inkforge.context.ContinuationContextBuilder;
import com.inkforge.context.ContextProperties;
import com.inkforge.novel.Novel;
import com.inkforge.novel.NovelRepository;
import com.inkforge.provider.ChatMessage;
import com.inkforge.provider.LlmProvider;
import com.inkforge.provider.LlmRequest;
import com.inkforge.provider.LlmUsage;
import com.inkforge.provider.ProviderStreamEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContinuationServiceTest {

    private NovelRepository novelRepository;
    private LlmProvider llmProvider;
    private GenerationLogRepository logRepository;
    private ContinuationService service;

    private static final Novel NOVEL = new Novel("n1", "测试小说", "t.txt", List.of(
            new Chapter(0, 1, "拜入山门", "正文。")));

    @BeforeEach
    void setUp() {
        novelRepository = mock(NovelRepository.class);
        llmProvider = mock(LlmProvider.class);
        logRepository = new InMemoryGenerationLogRepository();

        ContinuationContextBuilder contextBuilder = mock(ContinuationContextBuilder.class);
        when(contextBuilder.build(any(), any(Integer.class)))
                .thenReturn(List.of(ChatMessage.system("sys"), ChatMessage.user("usr")));
        when(contextBuilder.buildWithTrace(any(), any(Integer.class), any(String.class)))
                .thenReturn(new com.inkforge.context.ContextBuildResult(
                        List.of(ChatMessage.system("sys"), ChatMessage.user("usr")), null, 0));
        when(llmProvider.name()).thenReturn("test-provider");
        when(llmProvider.defaultModel()).thenReturn("test-model");

        service = new ContinuationService(
                novelRepository, contextBuilder,
                new ContextProperties(8192, 2000, Map.of()),
                new GenerationProperties(2048, 0.8),
                llmProvider, logRepository,
                new CostCalculator(new CostProperties(Map.of())),
                new com.inkforge.planning.InMemoryStoryPlanRepository(),
                new com.inkforge.common.JtokkitTokenCounter(),
                new com.inkforge.planning.PlanPromptRenderer(new com.inkforge.common.prompt.ClasspathPromptCatalog()));
    }

    private static Flux<ProviderStreamEvent> okStream() {
        return Flux.just(
                ProviderStreamEvent.delta("林"),
                ProviderStreamEvent.delta("默"),
                ProviderStreamEvent.usage(new LlmUsage(10, 2)));
    }

    @Test
    void streamsTokensThenDoneAndPersistsLog() {
        when(novelRepository.findById("n1")).thenReturn(Optional.of(NOVEL));
        when(llmProvider.stream(any(LlmRequest.class))).thenReturn(okStream());

        List<GenerationEvent> events = service
                .streamContinuation("n1", new GenerationOptions(null, null))
                .collectList().block();

        assertThat(events).hasSize(3);
        assertThat(events.get(0)).isEqualTo(new GenerationEvent.Token("林"));
        assertThat(events.get(1)).isEqualTo(new GenerationEvent.Token("默"));
        assertThat(events.get(2)).isInstanceOf(GenerationEvent.Done.class);

        GenerationEvent.DoneMeta meta = ((GenerationEvent.Done) events.get(2)).meta();
        assertThat(meta.generationId()).isNotBlank();
        assertThat(meta.provider()).isEqualTo("test-provider");
        assertThat(meta.model()).isEqualTo("test-model");
        assertThat(meta.promptTokens()).isEqualTo(10);
        assertThat(meta.completionTokens()).isEqualTo(2);
        assertThat(meta.totalTokens()).isEqualTo(12);
        assertThat(meta.latencyMs()).isGreaterThanOrEqualTo(0);

        GenerationLog saved = logRepository.findByNovelId("n1").getFirst();
        assertThat(saved.generationId()).isEqualTo(meta.generationId());
        assertThat(saved.status()).isEqualTo("SUCCESS");
        assertThat(saved.totalTokens()).isEqualTo(12);
    }

    @Test
    void everyRunGetsAUniqueGenerationId() {
        when(novelRepository.findById("n1")).thenReturn(Optional.of(NOVEL));
        when(llmProvider.stream(any(LlmRequest.class))).thenReturn(okStream());

        List<GenerationEvent> first = service
                .streamContinuation("n1", new GenerationOptions(null, null)).collectList().block();
        List<GenerationEvent> second = service
                .streamContinuation("n1", new GenerationOptions(null, null)).collectList().block();

        String id1 = ((GenerationEvent.Done) first.getLast()).meta().generationId();
        String id2 = ((GenerationEvent.Done) second.getLast()).meta().generationId();
        assertThat(id1).isNotEqualTo(id2);
        assertThat(logRepository.findByNovelId("n1")).hasSize(2);
    }

    @Test
    void streamFailureEmitsErrorEventAndFailedLog() {
        when(novelRepository.findById("n1")).thenReturn(Optional.of(NOVEL));
        when(llmProvider.stream(any(LlmRequest.class)))
                .thenReturn(Flux.error(new LlmException("模拟流失败")));

        List<GenerationEvent> events = service
                .streamContinuation("n1", new GenerationOptions(null, null))
                .collectList().block();

        assertThat(events).hasSize(1);
        assertThat(events.getFirst()).isEqualTo(new GenerationEvent.Error("模拟流失败"));
        assertThat(logRepository.findByNovelId("n1").getFirst().status()).isEqualTo("FAILED");
    }

    @Test
    void missingNovelThrowsNotFoundExceptionEagerly() {
        when(novelRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.streamContinuation("missing", new GenerationOptions(null, null)))
                .isInstanceOf(NotFoundException.class);
        assertThat(logRepository.findByNovelId("missing")).isEmpty();
    }

    @Test
    void requestOverridesBeatDefaults() {
        when(novelRepository.findById("n1")).thenReturn(Optional.of(NOVEL));
        when(llmProvider.stream(any(LlmRequest.class))).thenAnswer(invocation -> {
            LlmRequest request = invocation.getArgument(0);
            assertThat(request.maxOutputTokens()).isEqualTo(500);
            assertThat(request.temperature()).isEqualTo(0.5);
            return okStream();
        });

        service.streamContinuation("n1", new GenerationOptions(500, 0.5)).collectList().block();
    }
}
