package com.inkforge.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JtokkitTokenCounterTest {

    private final TokenCounter tokenCounter = new JtokkitTokenCounter();

    @Test
    void emptyOrNullTextCountsZero() {
        assertThat(tokenCounter.count("")).isZero();
        assertThat(tokenCounter.count(null)).isZero();
    }

    @Test
    void asciiTextCountsPositiveTokens() {
        assertThat(tokenCounter.count("hello")).isPositive();
        assertThat(tokenCounter.count("hello world")).isGreaterThan(tokenCounter.count("hello"));
    }

    @Test
    void chineseTextCountsRoughlyOneToThreeTokensPerChar() {
        String text = "林默缓缓拔出玄霜剑，剑身映出他苍白的脸。".repeat(10);
        int chars = text.length();
        assertThat(tokenCounter.count(text))
                .as("cl100k 对常见汉字的 token 数应在 1~3 倍字符数之间")
                .isGreaterThanOrEqualTo(chars)
                .isLessThanOrEqualTo(chars * 3);
    }

    @Test
    void countingIsDeterministic() {
        String text = "天剑宗立派三千载，坐镇大荒北境，以剑问道。";
        assertThat(tokenCounter.count(text)).isEqualTo(tokenCounter.count(text));
    }
}
