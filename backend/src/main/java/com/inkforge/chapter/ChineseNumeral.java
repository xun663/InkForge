package com.inkforge.chapter;

/**
 * Parses Chinese numerals used in chapter titles (第一章, 第十二章, 一百二十三章,
 * 两百零五章). Deterministic and offline — chapter detection must never call an LLM.
 * Unknown characters are skipped; returns -1 when no valid number is found.
 */
final class ChineseNumeral {

    private ChineseNumeral() {
    }

    static int parse(String text) {
        long total = 0;
        long section = 0;
        long number = 0;
        for (char c : text.toCharArray()) {
            int digit = digit(c);
            if (digit >= 0) {
                number = digit;
                continue;
            }
            long unit = unit(c);
            if (unit == 10_000 || unit == 100_000_000) {
                total = (total + section + number) * unit;
                section = 0;
                number = 0;
            } else if (unit > 0) {
                section += (number == 0 ? 1 : number) * unit;
                number = 0;
            }
            // unrecognized character: skip
        }
        long result = total + section + number;
        if (result <= 0 || result > Integer.MAX_VALUE) {
            return -1;
        }
        return (int) result;
    }

    private static int digit(char c) {
        return switch (c) {
            case '零', '〇' -> 0;
            case '一' -> 1;
            case '二', '两' -> 2;
            case '三' -> 3;
            case '四' -> 4;
            case '五' -> 5;
            case '六' -> 6;
            case '七' -> 7;
            case '八' -> 8;
            case '九' -> 9;
            default -> -1;
        };
    }

    private static long unit(char c) {
        return switch (c) {
            case '十' -> 10;
            case '百' -> 100;
            case '千' -> 1000;
            case '万' -> 10_000;
            case '亿' -> 100_000_000;
            default -> 0;
        };
    }
}
