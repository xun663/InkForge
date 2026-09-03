package com.inkforge.provider;

import com.inkforge.common.EmbeddingException;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI-compatible /embeddings client — one implementation covers any compatible
 * endpoint (SiliconFlow bge-m3, OpenAI text-embedding-*, Ollama, …) via configuration.
 * Batch splitting happens here; the provider-reported vectors are validated against
 * the configured dimension and FAIL LOUDLY on mismatch (never truncated/padded).
 */
public class OpenAiCompatibleEmbeddingProvider implements EmbeddingProvider {

    private static final String EMBEDDINGS_PATH = "/embeddings";

    private final String name;
    private final WebClient webClient;
    private final EmbeddingProperties properties;
    private final ObjectMapper objectMapper;

    public OpenAiCompatibleEmbeddingProvider(String name, WebClient webClient,
                                             EmbeddingProperties properties, ObjectMapper objectMapper) {
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
    public Embedding embed(String text) {
        return embedBatch(List.of(text)).getFirst();
    }

    @Override
    public List<Embedding> embedBatch(List<String> texts) {
        List<Embedding> all = new ArrayList<>();
        for (int start = 0; start < texts.size(); start += properties.batchSize()) {
            List<String> batch = texts.subList(start, Math.min(start + properties.batchSize(), texts.size()));
            all.addAll(callApi(batch));
        }
        return all;
    }

    private List<Embedding> callApi(List<String> batch) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.model());
        body.put("input", batch);

        JsonNode response = webClient.post()
                .uri(EMBEDDINGS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> {
                    if (properties.apiKey() != null && !properties.apiKey().isBlank()) {
                        headers.setBearerAuth(properties.apiKey());
                    }
                })
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(properties.timeoutSeconds()))
                .onErrorMap(e -> e instanceof EmbeddingException ? e
                        : new EmbeddingException("Embedding provider '" + name + "' 调用失败: " + e.getMessage(), e))
                .block();

        JsonNode data = response == null ? null : response.path("data");
        if (data == null || !data.isArray()) {
            throw new EmbeddingException("Embedding provider '" + name + "' 返回缺少 data 数组");
        }
        List<JsonNode> items = new ArrayList<>();
        data.forEach(items::add);
        items.sort(Comparator.comparingInt(n -> n.path("index").asInt()));

        List<Embedding> embeddings = new ArrayList<>(items.size());
        for (JsonNode item : items) {
            JsonNode values = item.path("embedding");
            if (!values.isArray() || values.size() != properties.dimension()) {
                throw new EmbeddingException(
                        "Embedding 维度不匹配：provider '" + name + "' 返回 " + values.size()
                                + "，配置 dimension=" + properties.dimension()
                                + "（不允许截断或填充，请同步配置与模型）");
            }
            float[] floats = new float[values.size()];
            for (int i = 0; i < floats.length; i++) {
                floats[i] = (float) values.get(i).asDouble();
            }
            embeddings.add(new Embedding(floats));
        }
        return embeddings;
    }
}
