package io.github.yuvrajtiwary.earlybird.telegram;

import io.github.yuvrajtiwary.earlybird.persistence.RoleCategory;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Display names and button codes for the choices in spec section 3.11. */
final class Labels {

    private Labels() {}

    /** The roles a user can pick (all categories except "other"), in menu order. */
    static final List<RoleCategory> ROLES = List.of(
            RoleCategory.BACKEND, RoleCategory.FRONTEND, RoleCategory.FULLSTACK, RoleCategory.MOBILE,
            RoleCategory.DATA_ENGINEERING, RoleCategory.DATA_SCIENCE_ML, RoleCategory.DEVOPS_SRE_CLOUD,
            RoleCategory.QA_TEST, RoleCategory.SECURITY, RoleCategory.EMBEDDED);

    record Experience(String code, double years, String label) {}

    static final List<Experience> EXPERIENCE = List.of(
            new Experience("0", 0.0, "Student / fresher"),
            new Experience("0.5", 0.5, "Under 1 year"),
            new Experience("1.5", 1.5, "1–2 years"),
            new Experience("2.5", 2.5, "2–3 years"));

    static String role(RoleCategory role) {
        return switch (role) {
            case BACKEND -> "Backend";
            case FRONTEND -> "Frontend";
            case FULLSTACK -> "Full-stack";
            case MOBILE -> "Mobile";
            case DATA_ENGINEERING -> "Data engineering";
            case DATA_SCIENCE_ML -> "Data science / ML";
            case DEVOPS_SRE_CLOUD -> "DevOps / SRE / Cloud";
            case QA_TEST -> "QA / Test";
            case SECURITY -> "Security";
            case EMBEDDED -> "Embedded";
            case OTHER -> "Other";
        };
    }

    static String code(RoleCategory role) {
        return role.name().toLowerCase(Locale.ROOT);
    }

    static Optional<RoleCategory> roleByCode(String code) {
        return ROLES.stream().filter(r -> code(r).equals(code)).findFirst();
    }

    static Optional<Experience> experienceByCode(String code) {
        return EXPERIENCE.stream().filter(e -> e.code().equals(code)).findFirst();
    }

    /** The option closest to a stored number of years. */
    static String experience(double years) {
        Experience best = EXPERIENCE.getFirst();
        for (Experience e : EXPERIENCE) {
            if (Math.abs(e.years() - years) < Math.abs(best.years() - years)) {
                best = e;
            }
        }
        return best.label();
    }
}
