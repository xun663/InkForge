package com.inkforge.context;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * Context budget and breakpoint defaults — plain configuration values.
 * The 8192 default lives here (application.yml), never hardcoded into logic.
 */
@ConfigurationProperties(prefix = "inkforge.context")
public record ContextProperties(int contextMaxTokens, int breakpointTailChars,
                                Map<String, ContextSection> sections) {

    public ContextProperties {
        if (contextMaxTokens <= 0) {
            contextMaxTokens = 8192;
        }
        if (breakpointTailChars <= 0) {
            breakpointTailChars = 2000;
        }
        if (sections == null) {
            sections = Map.of();
        }
    }
}
