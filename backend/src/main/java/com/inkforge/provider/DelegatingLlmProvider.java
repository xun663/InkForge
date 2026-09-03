package com.inkforge.provider;

import com.inkforge.config.RuntimeLlmConfig;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * Routes every LLM call to the current runtime configuration (仅 LLM 运行时切换).
 *
 * <p>Implements the existing {@link LlmProvider} interface, so all callers
 * (ContinuationService, MemoryExtractor, LlmListwiseReranker) are unchanged:
 * mock → {@link MockLlmProvider}; otherwise a fresh {@link OpenAiCompatibleLlmProvider}
 * built from the current {@link RuntimeLlmConfig} snapshot (baseUrl/apiKey/model/timeout).
 * The baseUrl is baked into a per-call WebClient — WebClient construction is cheap
 * relative to an LLM call, so no caching needed.
 */
public class DelegatingLlmProvider implements LlmProvider {

    public static final List<String> SUPPORTED_PROVIDERS =
            List.of(MockLlmProvider.NAME, "deepseek", "openai", "ollama", "openai-compatible");

    private final RuntimeLlmConfig runtimeConfig;
    private final MockLlmProvider mockProvider;
    private final ObjectMapper objectMapper;

    public DelegatingLlmProvider(RuntimeLlmConfig runtimeConfig,
                                 MockLlmProvider mockProvider,
                                 ObjectMapper objectMapper) {
        this.runtimeConfig = runtimeConfig;
        this.mockProvider = mockProvider;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return resolve().name();
    }

    @Override
    public String defaultModel() {
        return resolve().defaultModel();
    }

    @Override
    public Flux<ProviderStreamEvent> stream(LlmRequest request) {
        return resolve().stream(request);
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        return resolve().complete(request);
    }

    /** The provider serving the current runtime snapshot. */
    LlmProvider resolve() {
        RuntimeLlmConfig.Snapshot s = runtimeConfig.snapshot();
        if (s.provider() == null || MockLlmProvider.NAME.equalsIgnoreCase(s.provider())) {
            return mockProvider;
        }
        LlmProperties props = new LlmProperties(
                s.provider(), s.baseUrl(), s.apiKey(), s.model(), s.timeoutSeconds(), new LlmProperties.Mock(0));
        WebClient webClient = WebClient.builder().baseUrl(props.baseUrl()).build();
        return new OpenAiCompatibleLlmProvider(s.provider(), webClient, props, objectMapper);
    }
}
