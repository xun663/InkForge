package com.inkforge.generation;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Estimates USD cost from the configured price table; zero when the model has no entry (e.g. mock). */
@Component
public class CostCalculator {

    private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000);

    private final CostProperties costProperties;

    public CostCalculator(CostProperties costProperties) {
        this.costProperties = costProperties;
    }

    public BigDecimal estimate(String model, int promptTokens, int completionTokens) {
        CostProperties.ModelPrice price = costProperties.prices().get(model);
        if (price == null) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(promptTokens).multiply(price.inputUsdPer1m())
                .add(BigDecimal.valueOf(completionTokens).multiply(price.outputUsdPer1m()))
                .divide(ONE_MILLION, 6, RoundingMode.HALF_UP)
                .setScale(4, RoundingMode.HALF_UP);
    }
}
