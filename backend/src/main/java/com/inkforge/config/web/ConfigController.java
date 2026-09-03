package com.inkforge.config.web;

import com.inkforge.config.RuntimeLlmConfig;
import com.inkforge.provider.DelegatingLlmProvider;
import com.inkforge.provider.MockLlmProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Runtime LLM configuration API (仅 LLM). Reads/writes the effective provider that
 * {@code DelegatingLlmProvider} routes to.
 *
 * <p>Security contract: the API key is NEVER returned — GET only exposes
 * {@code apiKeyConfigured: boolean}. The key is held in memory only, cleared on restart.
 * Invalid requests throw {@link IllegalArgumentException} → HTTP 400 via GlobalExceptionHandler.
 */
@RestController
@RequestMapping("/api/config/llm")
public class ConfigController {

    private final RuntimeLlmConfig runtimeConfig;

    public ConfigController(RuntimeLlmConfig runtimeConfig) {
        this.runtimeConfig = runtimeConfig;
    }

    @GetMapping
    public LlmConfigDto get() {
        RuntimeLlmConfig.Snapshot s = runtimeConfig.snapshot();
        return new LlmConfigDto(s.provider(), s.baseUrl(), s.model(),
                s.apiKey() != null && !s.apiKey().isBlank(),
                DelegatingLlmProvider.SUPPORTED_PROVIDERS);
    }

    @PutMapping
    public LlmConfigDto update(@RequestBody LlmConfigUpdate update) {
        if (update == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        RuntimeLlmConfig.Snapshot cur = runtimeConfig.snapshot();
        String provider = blankTo(update.provider(), cur.provider()).toLowerCase();
        if (!DelegatingLlmProvider.SUPPORTED_PROVIDERS.contains(provider)) {
            throw new IllegalArgumentException("未知 LLM Provider: '" + provider + "'. 支持: "
                    + String.join(", ", DelegatingLlmProvider.SUPPORTED_PROVIDERS));
        }
        String baseUrl = blankTo(update.baseUrl(), cur.baseUrl());
        String model = blankTo(update.model(), cur.model());
        String apiKey = update.apiKey() == null ? cur.apiKey() : update.apiKey(); // null=keep, ""=clear

        boolean mock = MockLlmProvider.NAME.equalsIgnoreCase(provider);
        if (!mock && (apiKey == null || apiKey.isBlank())) {
            throw new IllegalArgumentException("Provider '" + provider + "' 需要 API Key（非 mock 必须配置 Key）");
        }
        if (!mock && (baseUrl == null || baseUrl.isBlank())) {
            throw new IllegalArgumentException("Provider '" + provider + "' 需要 base-url");
        }
        if (!mock && (model == null || model.isBlank())) {
            throw new IllegalArgumentException("Provider '" + provider + "' 需要 model");
        }

        runtimeConfig.update(update.provider(), update.baseUrl(), update.model(), update.apiKey());
        RuntimeLlmConfig.Snapshot s = runtimeConfig.snapshot();
        return new LlmConfigDto(s.provider(), s.baseUrl(), s.model(),
                s.apiKey() != null && !s.apiKey().isBlank(),
                DelegatingLlmProvider.SUPPORTED_PROVIDERS);
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    /** Safe view of the effective LLM config — the key value is never exposed. */
    public record LlmConfigDto(String provider, String baseUrl, String model,
                               boolean apiKeyConfigured, List<String> supportedProviders) {
    }

    /** apiKey: null = keep current, "" = clear, non-blank = replace. */
    public record LlmConfigUpdate(String provider, String baseUrl, String model, String apiKey) {
    }
}
