package com.inkforge.generation;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CostCalculatorTest {

    private final CostCalculator calculator = new CostCalculator(new CostProperties(Map.of(
            "deepseek-chat", new CostProperties.ModelPrice(
                    new BigDecimal("0.27"), new BigDecimal("1.10")),
            "free-model", new CostProperties.ModelPrice(BigDecimal.ZERO, BigDecimal.ZERO))));

    @Test
    void unknownModelCostsZero() {
        assertThat(calculator.estimate("inkforge-mock", 12000, 2000))
                .isEqualByComparingTo("0.0000");
    }

    @Test
    void computesCostFromPriceTable() {
        // 1M input @0.27 + 1M output @1.10 = 1.37 USD
        assertThat(calculator.estimate("deepseek-chat", 1_000_000, 1_000_000))
                .isEqualByComparingTo("1.3700");
    }

    @Test
    void roundsToFourDecimals() {
        // 12431*0.27/1e6 + 2031*1.10/1e6 = 0.00335637 + 0.00223410 = 0.00559047 → 0.0056
        assertThat(calculator.estimate("deepseek-chat", 12431, 2031))
                .isEqualByComparingTo("0.0056");
    }

    @Test
    void freeModelCostsZeroEvenWithUsage() {
        assertThat(calculator.estimate("free-model", 999999, 999999))
                .isEqualByComparingTo("0.0000");
    }
}
