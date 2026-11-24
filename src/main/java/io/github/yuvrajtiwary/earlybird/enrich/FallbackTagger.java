package io.github.yuvrajtiwary.earlybird.enrich;

import io.github.yuvrajtiwary.earlybird.common.Cities;
import io.github.yuvrajtiwary.earlybird.persistence.Posting;
import io.github.yuvrajtiwary.earlybird.persistence.PostingTags;
import io.github.yuvrajtiwary.earlybird.persistence.PostingTags.EmploymentType;
import io.github.yuvrajtiwary.earlybird.persistence.PostingTags.RemoteScope;
import io.github.yuvrajtiwary.earlybird.persistence.PostingTags.Seniority;
import io.github.yuvrajtiwary.earlybird.persistence.PostingTags.Tagger;
import io.github.yuvrajtiwary.earlybird.persistence.PostingTags.WorkMode;
import io.github.yuvrajtiwary.earlybird.persistence.RoleCategory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tags without AI (spec 3.7), used when Gemini is unavailable or the daily budget is spent. Keyword rules on
 * the title, location and description: rougher than the model, but deterministic, instant and free, so
 * alerts keep flowing. Results are marked {@code tagger = fallback}.
 */
public class FallbackTagger {

    /** First matching rule wins, so specific roles come before generic "software engineer". */
    private static final Map<RoleCategory, Pattern> ROLE_RULES = new LinkedHashMap<>();

    static {
        ROLE_RULES.put(RoleCategory.SECURITY, words("security|appsec|penetration|pentest|infosec|soc analyst"));
        ROLE_RULES.put(RoleCategory.DEVOPS_SRE_CLOUD, words("devops|sre|site reliability|platform engineer|cloud engineer|infrastructure|devsecops"));
        ROLE_RULES.put(RoleCategory.DATA_SCIENCE_ML, words("machine learning|ml engineer|data scientist|ai engineer|research scientist|nlp|computer vision|deep learning|applied scientist"));
        ROLE_RULES.put(RoleCategory.DATA_ENGINEERING, words("data engineer|analytics engineer|etl|big data|data platform"));
        ROLE_RULES.put(RoleCategory.MOBILE, words("android|ios|mobile|flutter|react native"));
        ROLE_RULES.put(RoleCategory.QA_TEST, words("qa|quality assurance|test engineer|sdet|tester|automation test"));
        ROLE_RULES.put(RoleCategory.EMBEDDED, words("embedded|firmware|fpga|asic|hardware engineer"));
        ROLE_RULES.put(RoleCategory.FULLSTACK, words("full stack|fullstack|full-stack"));
        ROLE_RULES.put(RoleCategory.FRONTEND, words("frontend|front-end|front end|ui engineer|react developer|web developer"));
        ROLE_RULES.put(RoleCategory.BACKEND, words("backend|back-end|back end|software engineer|software developer|sde|developer|programmer|engineer, software|java developer|python developer"));
    }

    private static final Pattern INTERN = words("intern|internship|trainee|apprentice");
    private static final Pattern NEW_GRAD = words("new grad|graduate|fresher|freshers|campus|entry level|entry-level|university");
    private static final Pattern JUNIOR = words("junior|jr|associate|sde i|sde 1|sde-1|engineer i|engineer 1|level 1");
    private static final Pattern STAFF_PLUS = words("staff|principal|architect|director|head of|vp|vice president|manager");
    private static final Pattern SENIOR = words("senior|sr|lead");
    private static final Pattern YEARS = Pattern.compile(
            "(\\d{1,2})\\s*(?:\\+|plus)?\\s*(?:(?:-|–|to)\\s*(\\d{1,2})\\s*)?\\+?\\s*(?:years|yrs|year)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FRESHER_TEXT = words("fresher|freshers|recent graduate|new grad|entry level|0-1 years?|0-2 years?|0 to 2 years");

    private static final Map<String, String> COUNTRIES = Map.ofEntries(
            Map.entry("india", "IN"), Map.entry("united states", "US"), Map.entry("usa", "US"), Map.entry("united kingdom", "GB"),
            Map.entry("uk", "GB"), Map.entry("canada", "CA"), Map.entry("germany", "DE"), Map.entry("singapore", "SG"),
            Map.entry("ireland", "IE"), Map.entry("netherlands", "NL"), Map.entry("france", "FR"), Map.entry("spain", "ES"),
            Map.entry("australia", "AU"), Map.entry("japan", "JP"), Map.entry("brazil", "BR"), Map.entry("poland", "PL"),
            Map.entry("united arab emirates", "AE"), Map.entry("uae", "AE"), Map.entry("israel", "IL"));

    private final SkillVocabulary vocabulary;

    public FallbackTagger(SkillVocabulary vocabulary) {
        this.vocabulary = vocabulary;
    }

    public PostingTags tag(Posting p, Instant now) {
        String title = p.title() == null ? "" : p.title();
        String location = p.locationRaw() == null ? "" : p.locationRaw();
        String description = p.descriptionText() == null ? "" : p.descriptionText();
        String text = title + "\n" + location + "\n" + description;

        Seniority seniority = seniority(title);
        Double min = null;
        Double max = null;
        Matcher years = YEARS.matcher(text);
        if (years.find()) {
            min = Math.min(30.0, Double.parseDouble(years.group(1)));
            max = years.group(2) == null ? null : Math.min(30.0, Double.parseDouble(years.group(2)));
        }
        boolean fresher = seniority == Seniority.INTERN || seniority == Seniority.NEW_GRAD
                || (min != null && min == 0.0) || FRESHER_TEXT.matcher(text).find();
        WorkMode mode = workMode(location + "\n" + title + "\n" + description);
        List<String> countries = countries(location);

        return new PostingTags(p.id(), role(title), seniority, min, max, fresher,
                employment(title + "\n" + description, seniority), mode,
                mode == WorkMode.REMOTE ? remoteScope(location + "\n" + description, countries) : RemoteScope.UNKNOWN,
                Cities.findIn(location), countries, vocabulary.findIn(title + "\n" + description, JobTagger.MAX_SKILLS),
                null, Tagger.FALLBACK, null, now);
    }

    static RoleCategory role(String title) {
        for (Map.Entry<RoleCategory, Pattern> rule : ROLE_RULES.entrySet()) {
            if (rule.getValue().matcher(title).find()) {
                return rule.getKey();
            }
        }
        return RoleCategory.OTHER;
    }

    static Seniority seniority(String title) {
        if (INTERN.matcher(title).find()) {
            return Seniority.INTERN;
        }
        if (NEW_GRAD.matcher(title).find()) {
            return Seniority.NEW_GRAD;
        }
        if (STAFF_PLUS.matcher(title).find()) {
            return Seniority.STAFF_PLUS;
        }
        if (SENIOR.matcher(title).find()) {
            return Seniority.SENIOR;
        }
        if (JUNIOR.matcher(title).find()) {
            return Seniority.JUNIOR;
        }
        return Seniority.UNKNOWN;
    }

    private static EmploymentType employment(String text, Seniority seniority) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (seniority == Seniority.INTERN) {
            return EmploymentType.INTERNSHIP;
        }
        if (lower.contains("part-time") || lower.contains("part time")) {
            return EmploymentType.PART_TIME;
        }
        if (words("contract|contractor|freelance").matcher(lower).find()) {
            return EmploymentType.CONTRACT;
        }
        if (lower.contains("full-time") || lower.contains("full time")) {
            return EmploymentType.FULL_TIME;
        }
        return EmploymentType.UNKNOWN;
    }

    private static WorkMode workMode(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("hybrid")) {
            return WorkMode.HYBRID;
        }
        if (words("remote|work from home|wfh").matcher(lower).find()) {
            return WorkMode.REMOTE;
        }
        if (words("on-site|onsite|in office|in-office").matcher(lower).find()) {
            return WorkMode.ONSITE;
        }
        return WorkMode.UNKNOWN;
    }

    private static RemoteScope remoteScope(String text, List<String> countries) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (words("worldwide|anywhere|global|globally").matcher(lower).find()) {
            return RemoteScope.WORLDWIDE;
        }
        if (words("apac|asia|emea|europe|americas|latam|north america").matcher(lower).find()) {
            return RemoteScope.REGION;
        }
        return countries.size() == 1 ? RemoteScope.COUNTRY : RemoteScope.UNKNOWN;
    }

    private static List<String> countries(String location) {
        List<String> out = new ArrayList<>();
        String lower = location.toLowerCase(Locale.ROOT);
        COUNTRIES.forEach((name, code) -> {
            if (words(name).matcher(lower).find() && !out.contains(code)) {
                out.add(code);
            }
        });
        // Lever appends the ISO code ("Pune, IN · On-site"); Indian cities imply India.
        Matcher code = Pattern.compile("\\b([A-Z]{2})\\b").matcher(location);
        while (code.find()) {
            if (COUNTRIES.containsValue(code.group(1)) && !out.contains(code.group(1))) {
                out.add(code.group(1));
            }
        }
        if (!Cities.findIn(location).isEmpty() && !out.contains("IN")) {
            out.add("IN");
        }
        return out;
    }

    private static Pattern words(String alternatives) {
        return Pattern.compile("(?<![a-z0-9])(?:" + alternatives + ")(?![a-z0-9])", Pattern.CASE_INSENSITIVE);
    }
}
