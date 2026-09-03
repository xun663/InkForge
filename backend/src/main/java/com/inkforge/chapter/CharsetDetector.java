package com.inkforge.chapter;

import org.mozilla.universalchardet.UniversalDetector;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Detects the charset of an uploaded novel file. Strategy — deterministic first:
 * <ol>
 *   <li>UTF-8 BOM → UTF-8</li>
 *   <li>strict UTF-8 decode → UTF-8 (decoding validity is deterministic)</li>
 *   <li>statistical detection (chardet) for everything else</li>
 *   <li>GB18030 fallback — the dominant legacy encoding of Chinese web novels (superset of GBK)</li>
 * </ol>
 */
@Component
public class CharsetDetector {

    private static final Charset GB18030 = Charset.forName("GB18030");
    private static final char BOM = '﻿';

    public String decode(byte[] bytes) {
        if (hasUtf8Bom(bytes)) {
            return stripBom(new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8));
        }
        if (isStrictUtf8(bytes)) {
            return stripBom(new String(bytes, StandardCharsets.UTF_8));
        }
        Charset detected = detect(bytes);
        if (detected != null) {
            return new String(bytes, detected);
        }
        return new String(bytes, GB18030);
    }

    private static boolean hasUtf8Bom(byte[] bytes) {
        return bytes.length >= 3
                && bytes[0] == (byte) 0xEF
                && bytes[1] == (byte) 0xBB
                && bytes[2] == (byte) 0xBF;
    }

    private static boolean isStrictUtf8(byte[] bytes) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    private static Charset detect(byte[] bytes) {
        try (ByteArrayInputStream in = new ByteArrayInputStream(bytes)) {
            String name = UniversalDetector.detectCharset(in);
            return name == null ? null : Charset.forName(name);
        } catch (Exception e) {
            return null;
        }
    }

    private static String stripBom(String text) {
        return !text.isEmpty() && text.charAt(0) == BOM ? text.substring(1) : text;
    }
}
