package io.github.yuvrajtiwary.earlybird.profiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes contact details from resume text before it leaves our server for Gemini (spec section 3.8): emails,
 * phone numbers (Indian and international), links, address and PIN-code lines, and a name-like first line.
 * Skills, dates and grades are what Gemini needs; who the person is and how to reach them isn't.
 *
 * <p>The rules lean towards keeping technical text: "Node.js/Express", "ASP.NET", "2021 - 2025" and
 * "CGPA 8.4" survive, which the tests pin down.
 */
public final class ContactStripper {

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    private static final Pattern URL = Pattern.compile(
            "(?i)\\b(?:https?://|www\\.)\\S+"
                    // profile sites, with or without a scheme
                    + "|\\b(?:linkedin|github|gitlab|leetcode|codeforces|codechef|hackerrank|kaggle|medium|behance"
                    + "|dribbble|twitter|x|instagram|facebook|stackoverflow)\\.(?:com|net)(?:/\\S*)?"
                    // personal sites on common hosts
                    + "|\\b[\\w-]+\\.(?:github\\.io|vercel\\.app|netlify\\.app|herokuapp\\.com|pages\\.dev)\\S*"
                    // any domain followed by a path ("myname.dev/blog"), but only for common TLDs, so
                    // "Node.js/Express" is left alone
                    + "|\\b(?:[a-z0-9-]+\\.)+(?:com|in|io|dev|me|org|net|co|ai|app|xyz|tech|site)/\\S*");

    // A run of digits with phone-style separators; it's a phone if it holds 10 to 15 digits.
    private static final Pattern PHONE_CANDIDATE = Pattern.compile("(?<![\\w/])\\+?\\(?\\d[\\d\\s()-]{7,}\\d(?![\\w/])");
    private static final Pattern DIGIT_GROUP = Pattern.compile("\\d+");

    private static final Pattern PIN_CODE = Pattern.compile("\\b[1-9]\\d{2}\\s?\\d{3}\\b");
    private static final Pattern ADDRESS_LABEL = Pattern.compile(
            "(?i)\\b(address|residence|pin\\s*code|pincode|postal code|zip code|permanent address)\\b");
    private static final Set<String> ADDRESS_WORDS = Set.of(
            "road", "rd", "street", "st", "nagar", "sector", "colony", "apartment", "apartments", "apt", "flat",
            "block", "lane", "marg", "near", "opp", "opposite", "layout", "cross", "phase", "society", "house",
            "h.no", "village", "district", "dist", "tehsil", "taluk", "floor", "tower", "enclave", "vihar",
            "chowk", "plot", "landmark");
    // A PIN code next to a place name is an address ("Mumbai 400001"); "grew to 100000 users" is not.
    private static final Set<String> PLACE_WORDS = Set.of(
            "india", "bengaluru", "bangalore", "mumbai", "delhi", "noida", "gurugram", "gurgaon", "ghaziabad",
            "faridabad", "pune", "hyderabad", "secunderabad", "chennai", "kolkata", "ahmedabad", "jaipur", "lucknow",
            "kanpur", "patna", "ranchi", "bhubaneswar", "indore", "bhopal", "nagpur", "surat", "vadodara", "kochi",
            "coimbatore", "chandigarh", "thane", "mysuru", "mysore", "visakhapatnam", "vijayawada", "dehradun",
            "karnataka", "maharashtra", "telangana", "tamil", "nadu", "kerala", "gujarat", "rajasthan", "haryana",
            "punjab", "bihar", "jharkhand", "odisha", "bengal", "uttar", "pradesh", "madhya", "andhra", "assam",
            "uttarakhand", "goa");

    private static final Pattern NAME_TOKEN = Pattern.compile("\\p{Lu}[\\p{L}.'-]*|\\p{Lu}+");
    private static final Set<String> HEADERS = Set.of(
            "resume", "curriculum vitae", "cv", "profile", "summary", "professional summary", "education",
            "experience", "work experience", "skills", "technical skills", "objective", "career objective",
            "about me", "contact", "projects", "certifications", "achievements");
    private static final Pattern LEFTOVER_LABEL = Pattern.compile(
            "(?i)^[\\s|•·,:;/-]*(e-?mail|mail|phone|mobile|mob|tel|contact|linkedin|github|portfolio|website|web"
                    + "|blog|twitter|leetcode)?[\\s|•·,:;/-]*$");

    private ContactStripper() {}

    public static String strip(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        List<String> kept = new ArrayList<>();
        boolean firstContentLine = true;
        for (String rawLine : text.split("\\R")) {
            String line = rawLine.strip();
            if (line.isEmpty()) {
                kept.add("");
                continue;
            }
            if (firstContentLine) {
                firstContentLine = false;
                if (looksLikeName(line)) {
                    continue;
                }
            }
            if (looksLikeAddress(line)) {
                continue;
            }
            line = EMAIL.matcher(line).replaceAll("");
            line = URL.matcher(line).replaceAll("");
            line = removePhones(line);
            if (LEFTOVER_LABEL.matcher(line).matches()) {
                continue; // nothing left but "Email:" or separators
            }
            kept.add(line.replaceAll("\\s{2,}", " ").strip());
        }
        return String.join("\n", kept).replaceAll("\\n{3,}", "\n\n").strip();
    }

    static boolean looksLikeName(String line) {
        if (line.length() > 40 || line.matches(".*\\d.*") || HEADERS.contains(line.toLowerCase(Locale.ROOT))) {
            return false;
        }
        // Up to 6 words, for names with initials ("Dr. A. P. J. Kalam"). The risks are lopsided: dropping a
        // capitalized title line costs little, while sending a name to a third party is a privacy failure.
        String[] tokens = line.split("\\s+");
        if (tokens.length > 6) {
            return false;
        }
        for (String token : tokens) {
            if (!NAME_TOKEN.matcher(token).matches()) {
                return false;
            }
        }
        return true;
    }

    static boolean looksLikeAddress(String line) {
        if (ADDRESS_LABEL.matcher(line).find()) {
            return true;
        }
        String lower = line.toLowerCase(Locale.ROOT);
        int addressWords = 0;
        boolean place = false;
        for (String word : lower.split("[^a-z.]+")) {
            String w = word.replaceAll("\\.$", "");
            if (ADDRESS_WORDS.contains(w)) {
                addressWords++;
            }
            place |= PLACE_WORDS.contains(w);
        }
        boolean pin = PIN_CODE.matcher(line).find();
        return (pin && (line.contains(",") || addressWords > 0 || place)) || addressWords >= 2;
    }

    private static String removePhones(String line) {
        Matcher m = PHONE_CANDIDATE.matcher(line);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, isPhone(m.group()) ? "" : Matcher.quoteReplacement(m.group()));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static boolean isPhone(String candidate) {
        int digits = candidate.replaceAll("\\D", "").length();
        if (digits < 10 || digits > 15) {
            return false;
        }
        // "2019 2020 2021" or "2021 - 2025 2025-2027" are years, not a phone number.
        Matcher groups = DIGIT_GROUP.matcher(candidate);
        boolean allYears = true;
        while (groups.find()) {
            String g = groups.group();
            if (!(g.length() == 4 && (g.startsWith("19") || g.startsWith("20")))) {
                allYears = false;
            }
        }
        return !allYears;
    }
}
