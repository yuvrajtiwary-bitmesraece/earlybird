package io.github.yuvrajtiwary.earlybird.persistence;

import java.time.Instant;
import java.util.List;

/**
 * A row of {@code profiles}: a user's preferences plus the facts extracted from their resume. The resume
 * file and its text are never stored, only the derived fields and the file's SHA-256 (to skip re-parsing).
 */
public record Profile(
        long userId,
        List<RoleCategory> roles,
        List<String> cities,
        boolean remoteIndia,
        boolean remoteWorldwide,
        double yearsExperience,
        boolean includeInternships,
        List<String> skills,
        List<RoleCategory> suggestedRoles,
        Integer graduationYear,
        int minScore,
        int dailyCap,
        String resumeSha256,
        Instant resumeUploadedAt,
        Instant updatedAt) {

    public Profile {
        roles = roles == null ? List.of() : List.copyOf(roles);
        cities = cities == null ? List.of() : List.copyOf(cities);
        skills = skills == null ? List.of() : List.copyOf(skills);
        suggestedRoles = suggestedRoles == null ? List.of() : List.copyOf(suggestedRoles);
    }
}
