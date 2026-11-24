package io.github.yuvrajtiwary.earlybird.profiles;

import io.github.yuvrajtiwary.earlybird.persistence.RoleCategory;
import java.util.List;

/**
 * What we keep from a resume (spec section 3.8). The file and its text are discarded.
 *
 * @param yearsExperience full-time work only (internships count as 0), or null if the resume doesn't say
 */
public record ResumeFacts(
        List<String> skills, Double yearsExperience, Integer graduationYear, List<RoleCategory> suggestedRoles) {

    public ResumeFacts {
        skills = List.copyOf(skills);
        suggestedRoles = List.copyOf(suggestedRoles);
    }
}
