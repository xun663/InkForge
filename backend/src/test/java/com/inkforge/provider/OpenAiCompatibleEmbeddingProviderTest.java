package com.inkforge.provider;

import com.inkforge.common.EmbeddingException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises the OpenAI-compatible provider against a local HTTP server (no real API key). */
class OpenAiCompatibleEmbeddingProviderTest {

    private HttpServer server;
    private OpenAiCompatibleEmbeddingProvider provider;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastAuth = new AtomicReference<>("none");
    private final AtomicReference<Integer> lastStatus = new AtomicReference<>(200);
    private final AtomicReference<Integer> returnedDimension = new AtomicReference<>(1024);

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/embeddings", exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastAuth.set(exchange.getRequestHeaders().containsKey("Authorization") ? "bearer" : "none");
            int dimension = returnedDimension.get();
            String response = buildResponse(dimension);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(lastStatus.get(), response.getBytes(StandardCharsets.UTF_8).length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response.getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();

        EmbeddingProperties properties = new EmbeddingProperties(
                "openai-compatible", "bge-m3", "http://127.0.0.1:" + server.getAddress().getPort(),
                "test-key", 1024, 2, 30); // batch-size 2 → batching is observable
        provider = new OpenAiCompatibleEmbeddingProvider("openai-compatible",
                WebClient.builder().baseUrl(properties.baseUrl()).build(), properties, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private static String buildResponse(int dimension) {
        StringBuilder sb = new StringBuilder("{\"data\":[");
        for (int i = 0; i < 2; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"index\":").append(i).append(",\"embedding\":[");
            for (int j = 0; j < dimension; j++) {
                if (j > 0) {
                    sb.append(',');
                }
                sb.append(i == 0 ? 0.1 : 0.2);
            }
            sb.append("]}");
        }
        sb.append("],\"model\":\"bge-m3\"}");
        return sb.toString();
    }

    @Test
    void embedParsesResponseAndSendsAuth() {
        Embedding e = provider.embed("测试文本");

        assertThat(e.dimension()).isEqualTo(1024);
        assertThat(e.values()[0]).isEqualTo(0.1f);
        assertThat(lastAuth.get()).isEqualTo("bearer");
    }

    @Test
    void embedBatchSplitsByBatchSizeAndKeepsOrder() {
        // 4 个文本 ÷ batch-size 2 = 2 次请求；每次返回 2 条（mock server 固定返回 2 条）
        List<Embedding> embeddings = provider.embedBatch(List.of("一", "二", "三", "四"));

        assertThat(embeddings).hasSize(4);
        assertThat(embeddings.get(0).values()[0]).isEqualTo(0.1f); // 第 1 批 index 0
        assertThat(embeddings.get(1).values()[0]).isEqualTo(0.2f); // 第 1 批 index 1
        assertThat(embeddings.get(2).values()[0]).isEqualTo(0.1f); // 第 2 批 index 0
        assertThat(embeddings.get(3).values()[0]).isEqualTo(0.2f); // 第 2 批 index 1
    }

    @Test
    void dimensionMismatchFailsLoudly() {
        returnedDimension.set(128);

        assertThatThrownBy(() -> provider.embed("文本"))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("维度不匹配");
    }

    @Test
    void httpErrorMapsToEmbeddingException() {
        lastStatus.set(401);

        assertThatThrownBy(() -> provider.embed("文本"))
                .isInstanceOf(EmbeddingException.class);
    }
}
