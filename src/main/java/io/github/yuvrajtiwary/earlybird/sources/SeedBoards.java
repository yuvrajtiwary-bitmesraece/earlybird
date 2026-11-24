package io.github.yuvrajtiwary.earlybird.sources;

import io.github.yuvrajtiwary.earlybird.persistence.Source;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads {@code boards/seed-boards.csv}: a header {@code source,token,company}, then one board per line.
 * Lines starting with {@code #} are comments. A bad line stops loading with its line number, so a typo
 * can't silently drop a board.
 */
public final class SeedBoards {

    public record SeedBoard(Source source, String token, String company) {}

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");

    private SeedBoards() {}

    public static List<SeedBoard> parse(Reader reader) throws IOException {
        List<SeedBoard> boards = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        boolean headerSeen = false;
        int lineNumber = 0;
        try (BufferedReader in = new BufferedReader(reader)) {
            String line;
            while ((line = in.readLine()) != null) {
                lineNumber++;
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (!headerSeen) {
                    if (!line.equalsIgnoreCase("source,token,company")) {
                        throw new IllegalArgumentException("line " + lineNumber + ": expected header source,token,company");
                    }
                    headerSeen = true;
                    continue;
                }
                String[] parts = line.split(",", 3);
                if (parts.length != 3) {
                    throw new IllegalArgumentException("line " + lineNumber + ": expected source,token,company");
                }
                Source source;
                try {
                    source = Source.valueOf(parts[0].strip().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("line " + lineNumber + ": unknown source '" + parts[0] + "'");
                }
                String token = parts[1].strip();
                String company = parts[2].strip();
                if (!TOKEN.matcher(token).matches()) {
                    throw new IllegalArgumentException("line " + lineNumber + ": invalid token '" + token + "'");
                }
                if (company.isEmpty()) {
                    throw new IllegalArgumentException("line " + lineNumber + ": company is empty");
                }
                if (!seen.add(source + "/" + token.toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("line " + lineNumber + ": duplicate board " + source + "/" + token);
                }
                boards.add(new SeedBoard(source, token, company));
            }
        }
        return boards;
    }
}
