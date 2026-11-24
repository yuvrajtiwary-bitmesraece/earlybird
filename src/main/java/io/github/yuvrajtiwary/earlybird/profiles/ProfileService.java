package io.github.yuvrajtiwary.earlybird.profiles;

import io.github.yuvrajtiwary.earlybird.config.EarlyBirdProperties;
import io.github.yuvrajtiwary.earlybird.enrich.SkillVocabulary;
import io.github.yuvrajtiwary.earlybird.persistence.Profile;
import io.github.yuvrajtiwary.earlybird.persistence.ProfileRepository;
import io.github.yuvrajtiwary.earlybird.persistence.RoleCategory;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/** Reads and changes profiles. Updates are read-modify-write; the bot handles one user's updates at a time. */
public class ProfileService {

    static final int MAX_PROFILE_SKILLS = 60;

    private final ProfileRepository profiles;
    private final SkillVocabulary vocabulary;
    private final Clock clock;
    private final EarlyBirdProperties.Alerts defaults;

    public ProfileService(ProfileRepository profiles, SkillVocabulary vocabulary, Clock clock,
            EarlyBirdProperties.Alerts defaults) {
        this.profiles = profiles;
        this.vocabulary = vocabulary;
        this.clock = clock;
        this.defaults = defaults;
    }

    public Optional<Profile> find(long userId) {
        return profiles.findByUserId(userId);
    }

    /** Saves the onboarding / prefs answers; keeps skills and resume facts already there. */
    public Profile saveAnswers(long userId, Collection<RoleCategory> roles, Collection<LocationChoice> locations,
            double yearsExperience, boolean includeInternships) {
        Profile current = profiles.findByUserId(userId).orElseGet(() -> empty(userId));
        Profile updated = new Profile(userId, List.copyOf(roles), cities(locations),
                locations.contains(LocationChoice.REMOTE_INDIA), locations.contains(LocationChoice.REMOTE_WORLDWIDE),
                yearsExperience, includeInternships, current.skills(), current.suggestedRoles(),
                current.graduationYear(), current.minScore(), current.dailyCap(), current.resumeSha256(),
                current.resumeUploadedAt(), clock.instant());
        profiles.upsert(updated);
        return updated;
    }

    /** Replaces skills and resume facts with what the new resume says. Years stay as the user chose them. */
    public Profile applyResume(long userId, ResumeFacts facts, String sha256) {
        return update(userId, p -> new Profile(userId, p.roles(), p.cities(), p.remoteIndia(), p.remoteWorldwide(),
                p.yearsExperience(), p.includeInternships(), facts.skills(), facts.suggestedRoles(),
                facts.graduationYear(), p.minScore(), p.dailyCap(), sha256, clock.instant(), clock.instant()));
    }

    public Profile addSkills(long userId, Collection<String> rawSkills) {
        return update(userId, p -> {
            List<String> merged = new ArrayList<>(p.skills());
            merged.addAll(rawSkills);
            return withSkills(p, vocabulary.normalize(merged, MAX_PROFILE_SKILLS));
        });
    }

    public Profile removeSkills(long userId, Collection<String> rawSkills) {
        Set<String> remove = rawSkills.stream()
                .map(s -> vocabulary.canonical(s).orElse(s.strip()).toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        return update(userId, p -> withSkills(p,
                p.skills().stream().filter(s -> !remove.contains(s.toLowerCase(Locale.ROOT))).toList()));
    }

    public Profile setMinScore(long userId, int minScore) {
        return update(userId, p -> new Profile(userId, p.roles(), p.cities(), p.remoteIndia(), p.remoteWorldwide(),
                p.yearsExperience(), p.includeInternships(), p.skills(), p.suggestedRoles(), p.graduationYear(),
                minScore, p.dailyCap(), p.resumeSha256(), p.resumeUploadedAt(), clock.instant()));
    }

    public Profile setDailyCap(long userId, int dailyCap) {
        return update(userId, p -> new Profile(userId, p.roles(), p.cities(), p.remoteIndia(), p.remoteWorldwide(),
                p.yearsExperience(), p.includeInternships(), p.skills(), p.suggestedRoles(), p.graduationYear(),
                p.minScore(), dailyCap, p.resumeSha256(), p.resumeUploadedAt(), clock.instant()));
    }

    /** The user's location choices, rebuilt from the stored cities and remote flags. */
    public static List<LocationChoice> locations(Profile p) {
        List<LocationChoice> out = new ArrayList<>();
        p.cities().forEach(c -> LocationChoice.byCity(c).ifPresent(out::add));
        if (p.remoteIndia()) {
            out.add(LocationChoice.REMOTE_INDIA);
        }
        if (p.remoteWorldwide()) {
            out.add(LocationChoice.REMOTE_WORLDWIDE);
        }
        return out;
    }

    private Profile update(long userId, UnaryOperator<Profile> change) {
        Profile current = profiles.findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("no profile for user " + userId));
        Profile updated = change.apply(current);
        profiles.upsert(updated);
        return updated;
    }

    private Profile withSkills(Profile p, List<String> skills) {
        return new Profile(p.userId(), p.roles(), p.cities(), p.remoteIndia(), p.remoteWorldwide(),
                p.yearsExperience(), p.includeInternships(), skills, p.suggestedRoles(), p.graduationYear(),
                p.minScore(), p.dailyCap(), p.resumeSha256(), p.resumeUploadedAt(), clock.instant());
    }

    private Profile empty(long userId) {
        return new Profile(userId, List.of(), List.of(), false, false, 0, false, List.of(), List.of(), null,
                defaults.defaultMinScore(), defaults.defaultDailyCap(), null, null, clock.instant());
    }

    private static List<String> cities(Collection<LocationChoice> locations) {
        return locations.stream().filter(l -> !l.isRemote()).map(LocationChoice::label).toList();
    }
}
