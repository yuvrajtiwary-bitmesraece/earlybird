package io.github.yuvrajtiwary.earlybird.enrich;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Gemini system instructions live in src/main/resources/prompts/, reviewable without reading Java. */
public final class Prompts {

    private Prompts() {}

    public static String read(String name) {
        try (InputStream in = Prompts.class.getResourceAsStream("/prompts/" + name)) {
            if (in == null) {
                throw new IllegalStateException("prompts/" + name + " not found");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
