package com.inkforge.planning;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 规划配置。temperature 故意低于正文续写（0.8）——规划要求稳定与克制；
 * planTokenReserve 是正文生成时为计划附录预留的预算上限（见 ContinuationService）。
 */
@ConfigurationProperties(prefix = "inkforge.planning")
public record PlanningProperties(
        int maxOutputTokens,
        double temperature,
        int maxRetries,
        int planTokenReserve,
        int directionCount) {

    public PlanningProperties {
        if (maxOutputTokens <= 0) {
            maxOutputTokens = 2048;
        }
        if (temperature < 0) {
            temperature = 0.3;
        }
        if (maxRetries < 0) {
            maxRetries = 2;
        }
        if (planTokenReserve <= 0) {
            planTokenReserve = 512;
        }
        if (directionCount <= 0) {
            directionCount = 3;
        }
    }
}
