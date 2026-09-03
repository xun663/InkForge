package com.inkforge.common.prompt;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads prompt templates from classpath resources under {@code prompts/} (UTF-8).
 * Rendering fails fast on unknown templates or unresolved placeholders instead of
 * silently producing broken prompts.
 */
@Component
public class ClasspathPromptCatalog implements PromptCatalog {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_]+)\\s*}}");

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    @Override
    public String render(String templateName, Map<String, String> variables) {
        String template = cache.computeIfAbsent(templateName, this::load);
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = variables.get(key);
            if (value == null) {
                throw new IllegalStateException(
                        "Unresolved placeholder '{{" + key + "}}' in prompt template '" + templateName + "'");
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String load(String templateName) {
        String resourcePath = "prompts/" + templateName;
        try {
            return new ClassPathResource(resourcePath).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("Prompt template not found: " + resourcePath, e);
        }
    }
}
