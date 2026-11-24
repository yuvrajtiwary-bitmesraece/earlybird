package io.github.yuvrajtiwary.earlybird.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Real API responses saved under src/test/resources/fixtures (trimmed to a few jobs each). */
public final class Fixtures {

    private Fixtures() {}

    public static String read(String path) {
        try (InputStream in = Fixtures.class.getResourceAsStream("/fixtures/" + path)) {
            if (in == null) {
                throw new IllegalArgumentException("no fixture " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
