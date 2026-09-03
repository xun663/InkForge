package com.inkforge.common.prompt;

import java.util.Map;

/**
 * Centralized prompt management. All prompts live as classpath resources under
 * {@code prompts/} and use {@code {{placeholder}}} syntax. Business code never
 * embeds prompt text — it renders a named template.
 */
public interface PromptCatalog {

    /**
     * Renders the template with the given name, replacing {@code {{key}}} placeholders.
     *
     * @throws IllegalArgumentException if the template does not exist
     * @throws IllegalStateException    if a placeholder has no matching variable
     */
    String render(String templateName, Map<String, String> variables);
}
