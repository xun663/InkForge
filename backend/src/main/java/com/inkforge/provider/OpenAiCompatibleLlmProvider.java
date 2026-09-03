package com.inkforge.provider;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.inkforge.common.LlmException;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OpenAI-compatible chat completions client. One implementation covers DeepSeek,
 * OpenAI, Ollama and any OpenAI-compatible endpoint via base-url / api-key / model
 * configuration. Streaming relies on {@code stream_options.include_usage} for the
 * authoritative usage report on the final chunk.
 */
public class OpenAiCompatibleLlmProvider implements LlmProvider {

    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";
    private static final String DONE = "[DONE]";

    private final String name;
    private final WebClient webClient;
    private final LlmProperties properties;
    private final ObjectMapper objectMapper;

    public OpenAiCompatibleLlmProvider(String name, WebClient webClient,
                                       LlmProperties properties, ObjectMapper objectMapper) {
        this.name = name;
        this.webClient = webClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String defaultModel() {
        return properties.model();
    }

    @Override
    public Flux<ProviderStreamEvent> stream(LlmRequest request) {
        return webClient.post()
                .uri(CHAT_COMPLETIONS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .headers(headers -> {
                    if (apiKeySet()) {
                        headers.setBearerAuth(properties.apiKey());
                    }
                })
                .bodyValue(buildBody(request, true))
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {
                })
                .timeout(Duration.ofSeconds(properties.timeoutSeconds()))
                .mapNotNull(event -> parseStreamEvent(event.data()))
                .onErrorMap(e -> e instanceof LlmException ? e
                        : new LlmException("LLM provider '" + name + "' stream failed: " + e.getMessage(), e));
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        return webClient.post()
                .uri(CHAT_COMPLETIONS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> {
                    if (apiKeySet()) {
                        headers.setBearerAuth(properties.apiKey());
                    }
                })
                .bodyValue(buildBody(request, false))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(properties.timeoutSeconds()))
                .map(node -> {
                    String content = node.path("choices").path(0).path("message").path("content").asText("");
                    JsonNode usageNode = node.path("usage");
                    LlmUsage usage = new LlmUsage(
                            usageNode.path("prompt_tokens").asInt(0),
                            usageNode.path("completion_tokens").asInt(0));
                    return new LlmResponse(content, usage);
                })
                .onErrorMap(e -> e instanceof LlmException ? e
                        : new LlmException("LLM provider '" + name + "' request failed: " + e.getMessage(), e))
                .block();
    }

    private ProviderStreamEvent parseStreamEvent(String data) {
        if (data == null || data.isBlank() || DONE.equals(data.strip())) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(data);
            String delta = node.path("choices").path(0).path("delta").path("content").asText(null);
            JsonNode usageNode = node.path("usage");
            LlmUsage usage = null;
            if (usageNode.has("prompt_tokens") && usageNode.has("completion_tokens")) {
                usage = new LlmUsage(
                        usageNode.get("prompt_tokens").asInt(),
                        usageNode.get("completion_tokens").asInt());
            }
            if (delta == null && usage == null) {
                return null;
            }
            return new ProviderStreamEvent(delta == null ? "" : delta, usage);
        } catch (Exception e) {
            throw new LlmException("Failed to parse SSE chunk from provider '" + name + "': " + data, e);
        }
    }

    private Map<String, Object> buildBody(LlmRequest request, boolean stream) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model() != null && !request.model().isBlank()
                ? request.model() : properties.model());
        body.put("messages", request.messages().stream()
                .map(m -> Map.of("role", m.role(), "content", m.content()))
                .toList());
        body.put("temperature", request.temperature());
        body.put("max_tokens", request.maxOutputTokens());
        body.put("stream", stream);
        if (stream) {
            body.put("stream_options", Map.of("include_usage", true));
        }
        return body;
    }

    private boolean apiKeySet() {
        return properties.apiKey() != null && !properties.apiKey().isBlank();
    }
}
