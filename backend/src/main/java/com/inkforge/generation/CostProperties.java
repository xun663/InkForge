package com.inkforge.generation;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.Map;

/** Price table per 1M tokens (USD). Used only for cost estimation display. */
@ConfigurationProperties(prefix = "inkforge.cost")
public record CostProperties(Map<String, ModelPrice> prices) {

    public CostProperties {
        if (prices == null) {
            prices = Map.of();
        }
    }

    public record ModelPrice(BigDecimal inputUsdPer1m, BigDecimal outputUsdPer1m) {
    }
}
