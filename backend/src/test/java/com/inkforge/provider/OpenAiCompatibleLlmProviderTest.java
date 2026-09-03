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
 * (JDK built-in, no external mock framework).
 */
class OpenAiCompatibleLlmProviderTest {

    private HttpServer server;
    private OpenAiCompatibleLlmProvider provider;
    private final AtomicReference<String> lastResponse = new AtomicReference<>();
    private final AtomicReference<Integer> lastStatus = new AtomicReference<>(200);
    private final AtomicReference<String> lastRequestPath = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/chat/completions", exchange -> {
            lastRequestPath.set(exchange.getRequestURI().getPath());
            byte[] body = exchange.getRequestBody().readAllBytes();
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

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private static String buildResponse(String requestBody) {
        if (requestBody.contains("\"stream\":true")) {
            return "data: {\"choices\":[{\"delta\":{\"content\":\"林\"}}]}\n\n"
                    + "data: {\"choices\":[{\"delta\":{\"content\":\"默\"}}]}\n\n"
                    + "data: {\"choices\":[{\"delta\":{\"content\":\"醒来\"}}]}\n\n"
                    + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":101,\"completion_tokens\":3}}\n\n"
                    + "data: [DONE]\n\n";
        }
        return "{\"choices\":[{\"message\":{\"content\":\"林默醒来\"}}],"
                + "\"usage\":{\"prompt_tokens\":101,\"completion_tokens\":3}}";
    }

    private static LlmRequest request() {
        return new LlmRequest(List.of(ChatMessage.user("续写")), 2048, 0.8, null);
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
    void httpErrorMapsToLlmException() {
        lastStatus.set(401);

        assertThatThrownBy(() -> provider.complete(request()))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("openai-compatible");
    }
}
