package com.inkforge.chapter;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class CharsetDetectorTest {

    private final CharsetDetector detector = new CharsetDetector();

    @Test
    void decodesUtf8() {
        String text = "第一章 玄霜剑\n林默缓缓拔出玄霜剑。";
        assertThat(detector.decode(text.getBytes(StandardCharsets.UTF_8))).isEqualTo(text);
    }

    @Test
    void decodesUtf8WithBom() {
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = "第一章 玄霜剑\n林默缓缓拔出玄霜剑。".getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, bytes, 0, bom.length);
        System.arraycopy(body, 0, bytes, bom.length, body.length);

        assertThat(detector.decode(bytes)).isEqualTo("第一章 玄霜剑\n林默缓缓拔出玄霜剑。");
    }

    @Test
    void decodesGbkFixtureWithoutMojibake() throws IOException {
        String decoded = detector.decode(Fixtures.bytes("gbk_sample.txt"));

        assertThat(decoded)
                .contains("玄霜剑")
                .contains("认主")
                .doesNotContain("�");
    }
}
