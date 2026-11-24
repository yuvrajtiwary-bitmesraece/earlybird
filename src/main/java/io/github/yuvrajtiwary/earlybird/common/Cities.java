package io.github.yuvrajtiwary.earlybird.common;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One name per Indian city, whatever the posting or the model wrote ("Bangalore" and "Bengaluru", "Gurgaon"
 * and "Gurugram"), so the matcher compares like with like. Non-Indian places are left as written.
 */
public final class Cities {

    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("bengaluru", "Bengaluru"), Map.entry("bangalore", "Bengaluru"),
            Map.entry("hyderabad", "Hyderabad"), Map.entry("secunderabad", "Hyderabad"),
            Map.entry("pune", "Pune"), Map.entry("chennai", "Chennai"), Map.entry("madras", "Chennai"),
            Map.entry("delhi", "Delhi"), Map.entry("new delhi", "Delhi"),
            Map.entry("noida", "Noida"), Map.entry("greater noida", "Noida"),
            Map.entry("gurugram", "Gurugram"), Map.entry("gurgaon", "Gurugram"),
            Map.entry("ghaziabad", "Ghaziabad"), Map.entry("faridabad", "Faridabad"),
            Map.entry("mumbai", "Mumbai"), Map.entry("bombay", "Mumbai"), Map.entry("navi mumbai", "Navi Mumbai"),
            Map.entry("thane", "Thane"), Map.entry("kolkata", "Kolkata"), Map.entry("calcutta", "Kolkata"),
            Map.entry("ahmedabad", "Ahmedabad"), Map.entry("jaipur", "Jaipur"), Map.entry("kochi", "Kochi"),
            Map.entry("cochin", "Kochi"), Map.entry("coimbatore", "Coimbatore"), Map.entry("chandigarh", "Chandigarh"),
            Map.entry("indore", "Indore"), Map.entry("mysuru", "Mysuru"), Map.entry("mysore", "Mysuru"),
            Map.entry("thiruvananthapuram", "Thiruvananthapuram"), Map.entry("trivandrum", "Thiruvananthapuram"),
            Map.entry("bhubaneswar", "Bhubaneswar"), Map.entry("nagpur", "Nagpur"), Map.entry("lucknow", "Lucknow"),
            Map.entry("vadodara", "Vadodara"), Map.entry("surat", "Surat"), Map.entry("visakhapatnam", "Visakhapatnam"));

    // Longest names first, so "navi mumbai" wins over "mumbai" and "new delhi" over "delhi".
    private static final Pattern ANY_CITY = Pattern.compile("\\b(" + String.join("|", ALIASES.keySet().stream()
            .sorted((a, b) -> b.length() - a.length()).map(Pattern::quote).toList()) + ")\\b");

    private Cities() {}

    /** The canonical name of an Indian city, if {@code raw} is one. */
    public static Optional<String> canonical(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(ALIASES.get(simplify(raw).replaceAll("\\s+", " ").strip()));
    }

    /** A city from a model or a posting: canonical if Indian, otherwise tidied as written. */
    public static String normalize(String raw) {
        return canonical(raw).orElse(raw == null ? "" : raw.strip());
    }

    /** Indian cities named anywhere in {@code text}, in order of appearance, without repeats. */
    public static List<String> findIn(String text) {
        List<String> found = new ArrayList<>();
        if (text == null) {
            return found;
        }
        Matcher m = ANY_CITY.matcher(simplify(text));
        while (m.find()) {
            String city = ALIASES.get(m.group(1));
            if (!found.contains(city)) {
                found.add(city);
            }
        }
        return found;
    }

    private static String simplify(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }
}
