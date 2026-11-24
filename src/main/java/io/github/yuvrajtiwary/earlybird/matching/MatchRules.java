package io.github.yuvrajtiwary.earlybird.matching;

import io.github.yuvrajtiwary.earlybird.common.Cities;
import io.github.yuvrajtiwary.earlybird.persistence.PostingTags;
import io.github.yuvrajtiwary.earlybird.persistence.PostingTags.RemoteScope;
import io.github.yuvrajtiwary.earlybird.persistence.PostingTags.Seniority;
import io.github.yuvrajtiwary.earlybird.persistence.PostingTags.WorkMode;
import io.github.yuvrajtiwary.earlybird.persistence.Profile;
import io.github.yuvrajtiwary.earlybird.profiles.LocationChoice;
import io.github.yuvrajtiwary.earlybird.profiles.ProfileService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Whether a posting fits a user, and how well (spec 3.9). Pure functions over a posting's tags and a profile:
 * no database, no clock, so every rule is pinned by table-driven tests.
 */
public final class MatchRules {

    /** Delhi NCR and Mumbai are regions; Bengaluru/Bangalore etc. are already one name via {@link Cities}. */
    static final Map<LocationChoice, Set<String>> CITY_GROUPS = Map.of(
            LocationChoice.BENGALURU, Set.of("Bengaluru"),
            LocationChoice.HYDERABAD, Set.of("Hyderabad"),
            LocationChoice.PUNE, Set.of("Pune"),
            LocationChoice.CHENNAI, Set.of("Chennai"),
            LocationChoice.DELHI_NCR, Set.of("Delhi", "Noida", "Gurugram", "Ghaziabad", "Faridabad"),
            LocationChoice.MUMBAI, Set.of("Mumbai", "Navi Mumbai", "Thane"),
            LocationChoice.KOLKATA, Set.of("Kolkata"),
            LocationChoice.AHMEDABAD, Set.of("Ahmedabad"));

    /** The city an option is named after; other cities in its group are a "group match". */
    static final Map<LocationChoice, String> PRIMARY_CITY = Map.of(
            LocationChoice.DELHI_NCR, "Delhi", LocationChoice.MUMBAI, "Mumbai");

    public static final int DIGEST_FLOOR = 40;
    static final int TOP_JOB_SKILLS = 12;
    static final int SHOWN_MATCHED = 6;
    static final int SHOWN_MISSING = 4;

    public record Match(int score, List<String> matchedSkills, List<String> missingSkills,
            double skillRatio, double experienceFit, double locationFit) {}

    public enum Route { INSTANT, DIGEST, NONE }

    private MatchRules() {}

    /** Empty if the posting doesn't fit at all (wrong role, place, seniority or employment type). */
    public static Optional<Match> evaluate(PostingTags job, Profile user) {
        if (!user.roles().contains(job.roleCategory()) || !employmentFits(job, user)) {
            return Optional.empty();
        }
        Double location = locationFit(job, user);
        Double experience = experienceFit(job, user.yearsExperience());
        if (location == null || experience == null) {
            return Optional.empty();
        }
        List<String> jobSkills = job.skills().size() > TOP_JOB_SKILLS ? job.skills().subList(0, TOP_JOB_SKILLS) : job.skills();
        Set<String> mine = user.skills().stream().map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        List<String> matched = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String skill : jobSkills) {
            (mine.contains(skill.toLowerCase(Locale.ROOT)) ? matched : missing).add(skill);
        }
        double skillRatio = jobSkills.isEmpty() ? 0.5 : (double) matched.size() / jobSkills.size();
        // round(100 × (0.65 × skills + 0.20 × experience + 0.15 × location)), with whole-number weights and a tiny
        // epsilon: 0.20 × 0.7 is 0.13999999999999999 in binary, which would round a true 58.5 down to 58.
        int score = (int) Math.round(65 * skillRatio + 20 * experience + 15 * location + 1e-9);
        return Optional.of(new Match(score,
                matched.subList(0, Math.min(SHOWN_MATCHED, matched.size())),
                missing.subList(0, Math.min(SHOWN_MISSING, missing.size())),
                skillRatio, experience, location));
    }

    /**
     * Instant if the score reaches the user's threshold; digest from 40; nothing below. Jobs from a board's
     * baseline (already up when we started watching) never go out as instant "new job" alerts.
     */
    public static Route route(int score, int minScore, boolean baseline) {
        if (score < DIGEST_FLOOR) {
            return Route.NONE;
        }
        return !baseline && score >= minScore ? Route.INSTANT : Route.DIGEST;
    }

    /** Full-time (or unstated) always; internships only if the user opted in; contract and part-time never. */
    static boolean employmentFits(PostingTags job, Profile user) {
        return switch (job.employmentType()) {
            case FULL_TIME, UNKNOWN -> true;
            case INTERNSHIP -> user.includeInternships();
            case CONTRACT, PART_TIME -> false;
        };
    }

    /**
     * Remote jobs: "Remote (India)" users if the job is open to India (worldwide, unknown, a region or country
     * that includes India); "Remote (worldwide)" users if it is worldwide. Everything else: by city, 1.0 for the
     * chosen city itself, 0.8 for another city in the group or for a hybrid role.
     *
     * @return the location fit, or null if the posting's place doesn't fit
     */
    static Double locationFit(PostingTags job, Profile user) {
        if (job.workMode() == WorkMode.REMOTE) {
            if (user.remoteIndia() && remoteOpenToIndia(job)) {
                return 1.0;
            }
            if (user.remoteWorldwide() && job.remoteScope() == RemoteScope.WORLDWIDE) {
                return 1.0;
            }
            return null;
        }
        Double best = null;
        for (LocationChoice choice : ProfileService.locations(user)) {
            Set<String> group = CITY_GROUPS.get(choice);
            if (group == null) {
                continue; // a remote option
            }
            String primary = PRIMARY_CITY.getOrDefault(choice, choice.label());
            for (String raw : job.cities()) {
                String city = Cities.normalize(raw);
                double fit = city.equals(primary) ? 1.0 : group.contains(city) ? 0.8 : 0;
                if (fit > 0 && job.workMode() == WorkMode.HYBRID) {
                    fit = 0.8;
                }
                if (fit > 0 && (best == null || fit > best)) {
                    best = fit;
                }
            }
        }
        return best;
    }

    private static boolean remoteOpenToIndia(PostingTags job) {
        return switch (job.remoteScope()) {
            case WORLDWIDE, UNKNOWN -> true;
            case COUNTRY -> job.countries().contains("IN");
            case REGION -> job.countries().isEmpty() || job.countries().contains("IN");
        };
    }

    /**
     * Fails for senior / staff+ roles or if the job asks for 2+ years more than the user has. Passes if it is
     * fresher-friendly, asks for at most 1 year more, or states no years and isn't above junior.
     *
     * @return 1.0 (fresher-friendly or within the user's years), 0.7 (unstated, or one year more), or null
     */
    static Double experienceFit(PostingTags job, double userYears) {
        if (job.seniority() == Seniority.SENIOR || job.seniority() == Seniority.STAFF_PLUS) {
            return null;
        }
        Double min = job.minYears();
        if (min != null && min >= userYears + 2) {
            return null;
        }
        boolean entryLevel = switch (job.seniority()) {
            case INTERN, NEW_GRAD, JUNIOR, UNKNOWN -> true;
            default -> false;
        };
        boolean passes = job.fresherFriendly() || (min != null && min <= userYears + 1) || (min == null && entryLevel);
        if (!passes) {
            return null;
        }
        return job.fresherFriendly() || (min != null && min <= userYears) ? 1.0 : 0.7;
    }
}
