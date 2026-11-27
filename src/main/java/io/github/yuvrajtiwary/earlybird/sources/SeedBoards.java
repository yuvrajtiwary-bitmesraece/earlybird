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
 * Reads {@code boards/seed-boards.csv}: a header {@code source,token,domain,company}, then one board per line.
 * The domain (e.g. {@code stripe.com}) is the company's website, used for its logo; it may be left empty.
 * The company comes last so it may contain commas. Lines starting with {@code #} are comments. A bad line stops
 * loading with its line number, so a typo can't silently drop a board.
 */
public final class SeedBoards {

    /** @param domain the company's website, lower case, or null if unknown */
    public record SeedBoard(Source source, String token, String domain, String company) {}

    private static final String HEADER = "source,token,domain,company";
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");
    /** Same rule as the check constraint on boards.domain (V6). */
    static final Pattern DOMAIN = Pattern.compile("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+");

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
                    if (!line.equalsIgnoreCase(HEADER)) {
                        throw new IllegalArgumentException("line " + lineNumber + ": expected header " + HEADER);
                    }
                    headerSeen = true;
                    continue;
                }
                String[] parts = line.split(",", 4);
                if (parts.length != 4) {
                    throw new IllegalArgumentException("line " + lineNumber + ": expected " + HEADER);
                }
                Source source;
                try {
                    source = Source.valueOf(parts[0].strip().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("line " + lineNumber + ": unknown source '" + parts[0] + "'");
                }
                String token = parts[1].strip();
                String domain = parts[2].strip().isEmpty() ? null : parts[2].strip();
                String company = parts[3].strip();
                if (!TOKEN.matcher(token).matches()) {
                    throw new IllegalArgumentException("line " + lineNumber + ": invalid token '" + token + "'");
                }
                if (domain != null && !DOMAIN.matcher(domain).matches()) {
                    throw new IllegalArgumentException("line " + lineNumber + ": invalid domain '" + domain + "'");
                }
                if (company.isEmpty()) {
                    throw new IllegalArgumentException("line " + lineNumber + ": company is empty");
                }
                if (!seen.add(source + "/" + token.toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("line " + lineNumber + ": duplicate board " + source + "/" + token);
                }
                boards.add(new SeedBoard(source, token, domain, company));
            }
        }
        return boards;
    }
}
