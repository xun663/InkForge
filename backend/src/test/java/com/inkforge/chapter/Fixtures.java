package com.inkforge.chapter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Test fixture loader for novel sample files under src/test/resources/fixtures. */
public final class Fixtures {

    private Fixtures() {
    }

    public static String text(String name) throws IOException {
        return new String(bytes(name), StandardCharsets.UTF_8);
    }

    public static byte[] bytes(String name) throws IOException {
        try (InputStream in = Fixtures.class.getResourceAsStream("/fixtures/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Fixture not found: " + name);
            }
            return in.readAllBytes();
        }
    }
}
