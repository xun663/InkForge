package com.inkforge.generation;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Generation defaults — configuration values, never hardcoded into logic. */
@ConfigurationProperties(prefix = "inkforge.generation")
public record GenerationProperties(int maxOutputTokens, double temperature) {

    public GenerationProperties {
        if (maxOutputTokens <= 0) {
            maxOutputTokens = 2048;
        }
        if (temperature < 0) {
            temperature = 0.8;
        }
    }
}
