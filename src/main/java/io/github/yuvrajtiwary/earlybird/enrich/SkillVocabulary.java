package io.github.yuvrajtiwary.earlybird.enrich;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Canonical skill names from {@code skills.yaml}. Skills from resumes and job postings go through
 * {@link #normalize} so that "k8s", "K8S" and "Kubernetes" are the same skill when matching.
 *
 * <p>Lookup ignores case, spaces, dots, dashes and underscores ("Node.js" = "node js" = "nodejs"), then tries
 * again without a trailing version ("Python 3.11" → Python). Unknown skills are kept, tidied, so a real but
 * unlisted skill isn't lost; soft skills in the {@code _ignore} list are dropped.
 */
public final class SkillVocabulary {

    private static final Pattern NOT_KEY = Pattern.compile("[\\s._\\-]+");
    private static final Pattern TRAILING_VERSION = Pattern.compile("[\\s._\\-]*v?\\d+(\\.\\d+)*\\+?$");
    private static final Pattern HAS_LETTER = Pattern.compile("\\p{L}");

    private final Map<String, String> canonicalByKey;
    private final Set<String> ignoredKeys;
    private final List<String> canonicalNames;

    private SkillVocabulary(Map<String, String> canonicalByKey, Set<String> ignoredKeys, List<String> canonicalNames) {
        this.canonicalByKey = Map.copyOf(canonicalByKey);
        this.ignoredKeys = Set.copyOf(ignoredKeys);
        this.canonicalNames = List.copyOf(canonicalNames);
    }

    public static SkillVocabulary fromClasspath() {
        try (InputStream in = SkillVocabulary.class.getResourceAsStream("/skills.yaml")) {
            if (in == null) {
                throw new IllegalStateException("skills.yaml not found on the classpath");
            }
            return load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Parses the YAML. Fails if one alias would point at two different skills. */
    @SuppressWarnings("unchecked")
    public static SkillVocabulary load(InputStream yaml) {
        Map<String, Object> raw = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
        Map<String, String> byKey = new HashMap<>();
        Set<String> ignored = new HashSet<>();
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            List<Object> aliases = entry.getValue() == null ? List.of() : (List<Object>) entry.getValue();
            if (entry.getKey().equals("_ignore")) {
                aliases.forEach(a -> ignored.add(key(String.valueOf(a))));
                continue;
            }
            String canonical = entry.getKey().strip();
            names.add(canonical);
            register(byKey, canonical, canonical);
            for (Object alias : aliases) {
                register(byKey, String.valueOf(alias), canonical);
            }
        }
        return new SkillVocabulary(byKey, ignored, names);
    }

    private static void register(Map<String, String> byKey, String name, String canonical) {
        String previous = byKey.putIfAbsent(key(name), canonical);
        if (previous != null && !previous.equals(canonical)) {
            throw new IllegalStateException("skills.yaml: '" + name + "' is listed for both " + previous + " and " + canonical);
        }
    }

    static String key(String name) {
        return NOT_KEY.matcher(name.strip().toLowerCase(Locale.ROOT)).replaceAll("");
    }

    /** All canonical names, in file order (used in prompts as the preferred vocabulary). */
    public List<String> canonicalNames() {
        return canonicalNames;
    }

    /** The canonical name for a known skill. */
    public Optional<String> canonical(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String hit = canonicalByKey.get(key(raw));
        if (hit == null) {
            String withoutVersion = TRAILING_VERSION.matcher(raw.strip()).replaceFirst("");
            if (!withoutVersion.isBlank()) {
                hit = canonicalByKey.get(key(withoutVersion));
            }
        }
        return Optional.ofNullable(hit);
    }

    public boolean isIgnored(String raw) {
        return raw != null && ignoredKeys.contains(key(raw));
    }

    /**
     * Canonical names for known skills, tidied text for unknown ones; soft skills and junk dropped; duplicates
     * (after normalizing) removed, first occurrence wins; at most {@code max} results.
     */
    public List<String> normalize(Collection<String> rawSkills, int max) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String raw : rawSkills) {
            if (out.size() >= max) {
                break;
            }
            if (raw == null || isIgnored(raw)) {
                continue;
            }
            String name = canonical(raw).orElseGet(() -> tidyUnknown(raw));
            if (name != null) {
                out.putIfAbsent(key(name), name);
            }
        }
        return List.copyOf(out.values());
    }

    /** Keeps plausible skill names (2-40 characters, at most 5 words, has a letter); drops the rest. */
    private static String tidyUnknown(String raw) {
        String s = raw.strip().replaceAll("\\s+", " ").replaceAll("^[-•*·,;:]+|[,;:.]+$", "").strip();
        if (s.length() < 2 || s.length() > 40 || s.split(" ").length > 5 || !HAS_LETTER.matcher(s).find()) {
            return null;
        }
        return s;
    }
}
