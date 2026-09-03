package com.inkforge.provider;

import tools.jackson.databind.ObjectMapper;
import com.inkforge.common.LlmException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the OpenAI-compatible provider against a real local HTTP server
 * (JDK built-in, no external mock framework). Covers DeepSeek thinking mode
 * request wiring and strict content / reasoning_content separation.
 */
class OpenAiCompatibleLlmProviderTest {

    private HttpServer server;
    private OpenAiCompatibleLlmProvider provider;
    private final AtomicReference<String> lastResponse = new AtomicReference<>();
    private final AtomicReference<Integer> lastStatus = new AtomicReference<>(200);
    private final AtomicReference<String> lastRequestPath = new AtomicReference<>();
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
    private final AtomicReference<String> responseMode = new AtomicReference<>("content");

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/chat/completions", exchange -> {
            lastRequestPath.set(exchange.getRequestURI().getPath());
            byte[] body = exchange.getRequestBody().readAllBytes();
            lastRequestBody.set(new String(body, StandardCharsets.UTF_8));
            lastResponse.set(exchange.getRequestHeaders().containsKey("Authorization") ? "auth" : "no-auth");
            String responseBody = buildResponse(new String(body, StandardCharsets.UTF_8));
            exchange.getResponseHeaders().set("Content-Type",
                    responseBody.startsWith("data:") ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(lastStatus.get(), responseBody.getBytes(StandardCharsets.UTF_8).length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(responseBody.getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();

        LlmProperties properties = new LlmProperties(
                "openai-compatible", "http://127.0.0.1:" + server.getAddress().getPort(),
                "test-key", "test-model", 30, new LlmProperties.Mock(0));
        provider = new OpenAiCompatibleLlmProvider(
                "openai-compatible", WebClient.builder().baseUrl(properties.baseUrl()).build(),
                properties, new ObjectMapper());
    }

    private OpenAiCompatibleLlmProvider deepseekProvider() {
        LlmProperties properties = new LlmProperties(
                "deepseek", "http://127.0.0.1:" + server.getAddress().getPort(),
                "test-key", "deepseek-v4-flash", 30, new LlmProperties.Mock(0));
        return new OpenAiCompatibleLlmProvider(
                "deepseek", WebClient.builder().baseUrl(properties.baseUrl()).build(),
                properties, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private String buildResponse(String requestBody) {
        if (requestBody.contains("\"stream\":true")) {
            return "data: {\"choices\":[{\"delta\":{\"content\":\"林\"}}]}\n\n"
                    + "data: {\"choices\":[{\"delta\":{\"content\":\"默\"}}]}\n\n"
                    + "data: {\"choices\":[{\"delta\":{\"content\":\"醒来\"}}]}\n\n"
                    + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":101,\"completion_tokens\":3}}\n\n"
                    + "data: [DONE]\n\n";
        }
        return switch (responseMode.get()) {
            case "content-and-reasoning" -> "{\"choices\":[{\"message\":{"
                    + "\"content\":\"林默醒来\",\"reasoning_content\":\"思考过程\"}}],"
                    + "\"usage\":{\"prompt_tokens\":101,\"completion_tokens\":3}}";
            case "reasoning-only" -> "{\"choices\":[{\"message\":{"
                    + "\"content\":\"\",\"reasoning_content\":\"大量推理，但没给最终答案\"}}],"
                    + "\"usage\":{\"prompt_tokens\":50,\"completion_tokens\":40}}";
            case "empty" -> "{\"choices\":[{\"message\":{\"content\":\"\"}}],"
                    + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":0}}";
            case "malformed" -> "这不是 JSON";
            default -> "{\"choices\":[{\"message\":{\"content\":\"林默醒来\"}}],"
                    + "\"usage\":{\"prompt_tokens\":101,\"completion_tokens\":3}}";
        };
    }

    private static LlmRequest request() {
        return new LlmRequest(List.of(ChatMessage.user("续写")), 2048, 0.8, null);
    }

    /** MEMORY_EXTRACTION / RERANK 默认 thinking disabled（从 taskType 派生）。 */
    private static LlmRequest extractionRequest() {
        return new LlmRequest(List.of(ChatMessage.system("sys"), ChatMessage.user("提取")),
                2048, 0.2, null, TaskType.MEMORY_EXTRACTION);
    }

    @Test
    void streamParsesDeltasAndFinalUsage() {
        List<ProviderStreamEvent> events = provider.stream(request()).collectList().block();

        assertThat(events).isNotNull();
        assertThat(lastRequestPath.get()).isEqualTo("/chat/completions");
        assertThat(lastResponse.get()).isEqualTo("auth"); // bearer token attached
        StringBuilder joined = new StringBuilder();
        LlmUsage usage = null;
        for (ProviderStreamEvent event : events) {
            if (event.delta() != null && !event.delta().isEmpty()) {
                joined.append(event.delta());
            }
            if (event.usage() != null) {
                usage = event.usage();
            }
        }
        assertThat(joined.toString()).isEqualTo("林默醒来");
        assertThat(usage).isEqualTo(new LlmUsage(101, 3));
    }

    @Test
    void completeParsesContentAndUsage() {
        LlmResponse response = provider.complete(request());

        assertThat(response.content()).isEqualTo("林默醒来");
        assertThat(response.usage()).isEqualTo(new LlmUsage(101, 3));
    }

    @Test
    void completeUsesContentWhenReasoningAlsoPresent() {
        responseMode.set("content-and-reasoning");

        LlmResponse response = provider.complete(request());

        // content 是最终业务输出；即使 reasoning 也存在，也只返回 content。
        assertThat(response.content()).isEqualTo("林默醒来");
    }

    @Test
    void completeWithReasoningOnlyThrowsClearError() {
        responseMode.set("reasoning-only");

        assertThatThrownBy(() -> provider.complete(request()))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("推理内容")
                .hasMessageContaining("无最终答案")
                .hasMessageContaining("reasoning_content 不能作为最终输出");
    }

    @Test
    void completeWithEmptyContentThrows() {
        responseMode.set("empty");

        assertThatThrownBy(() -> provider.complete(request()))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("LLM 返回为空");
    }

    @Test
    void completeWithMalformedResponseThrows() {
        responseMode.set("malformed");

        assertThatThrownBy(() -> provider.complete(request()))
                .isInstanceOf(LlmException.class);
    }

    @Test
    void httpErrorMapsToLlmException() {
        lastStatus.set(401);

        assertThatThrownBy(() -> provider.complete(request()))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("openai-compatible");
    }

    @Test
    void deepseekThinkingDisabledIsSentInBody() {
        // MEMORY_EXTRACTION 默认 thinking=DISABLED → deepseek 请求体应带 "thinking":{"type":"disabled"}
        deepseekProvider().complete(extractionRequest());

        assertThat(lastRequestBody.get()).contains("\"thinking\":{\"type\":\"disabled\"}");
    }

    @Test
    void deepseekThinkingEnabledIsSentInBody() {
        LlmRequest req = new LlmRequest(List.of(ChatMessage.user("续写")), 2048, 0.8, null,
                TaskType.CONTINUATION, LlmRequest.ThinkingMode.ENABLED);

        deepseekProvider().complete(req);

        assertThat(lastRequestBody.get()).contains("\"thinking\":{\"type\":\"enabled\"}");
    }

    @Test
    void deepseekContinuationAlsoSendsDisabledByDefault() {
        // CONTINUATION 默认也 DISABLED（deepseek-v4-flash 默认 thinking 开 → content 会空）
        deepseekProvider().complete(request());

        assertThat(lastRequestBody.get()).contains("\"thinking\":{\"type\":\"disabled\"}");
    }

    @Test
    void nonDeepseekProviderNeverSendsThinking() {
        // openai-compatible（非 deepseek）无论请求怎么指定都不发 thinking，避免未知字段失败
        LlmRequest req = new LlmRequest(List.of(ChatMessage.user("续写")), 2048, 0.8, null,
                TaskType.CONTINUATION, LlmRequest.ThinkingMode.DISABLED);
        provider.complete(req);

        assertThat(lastRequestBody.get()).doesNotContain("\"thinking\"");
    }
}
