package com.inkforge.chapter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChineseNumeralTest {

    @Test
    void parsesBasicDigits() {
        assertThat(ChineseNumeral.parse("一")).isEqualTo(1);
        assertThat(ChineseNumeral.parse("七")).isEqualTo(7);
        assertThat(ChineseNumeral.parse("九")).isEqualTo(9);
    }

    @Test
    void parsesTens() {
        assertThat(ChineseNumeral.parse("十")).isEqualTo(10);
        assertThat(ChineseNumeral.parse("十二")).isEqualTo(12);
        assertThat(ChineseNumeral.parse("二十")).isEqualTo(20);
        assertThat(ChineseNumeral.parse("二十一")).isEqualTo(21);
        assertThat(ChineseNumeral.parse("九十九")).isEqualTo(99);
    }

    @Test
    void parsesHundreds() {
        assertThat(ChineseNumeral.parse("一百")).isEqualTo(100);
        assertThat(ChineseNumeral.parse("一百零三")).isEqualTo(103);
        assertThat(ChineseNumeral.parse("一百二十三")).isEqualTo(123);
        assertThat(ChineseNumeral.parse("两百零五")).isEqualTo(205);
    }

    @Test
    void parsesThousandsAndBeyond() {
        assertThat(ChineseNumeral.parse("一千")).isEqualTo(1000);
        assertThat(ChineseNumeral.parse("一千零一")).isEqualTo(1001);
        assertThat(ChineseNumeral.parse("三千二百五十")).isEqualTo(3250);
        assertThat(ChineseNumeral.parse("一万")).isEqualTo(10000);
        assertThat(ChineseNumeral.parse("十二万三千四百五十六")).isEqualTo(123456);
    }

    @Test
    void parsesTwoVariantAndRejectsBareZero() {
        assertThat(ChineseNumeral.parse("两")).isEqualTo(2);
        // 第零章 is not a valid chapter number
        assertThat(ChineseNumeral.parse("零")).isEqualTo(-1);
        assertThat(ChineseNumeral.parse("〇")).isEqualTo(-1);
        // …but zero works as a placeholder inside larger numbers
        assertThat(ChineseNumeral.parse("一百零三")).isEqualTo(103);
    }

    @Test
    void returnsMinusOneWhenNoNumber() {
        assertThat(ChineseNumeral.parse("")).isEqualTo(-1);
        assertThat(ChineseNumeral.parse("章节")).isEqualTo(-1);
    }
}
