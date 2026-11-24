package io.github.yuvrajtiwary.earlybird.telegram;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Where a user is in a multi-step conversation, stored in {@code bot_sessions} (state + JSON data), so a
 * restart or a redeploy in the middle of onboarding loses nothing.
 *
 * @param draft answers collected so far (role and location codes), before they are saved to the profile
 */
public record Session(State state, Draft draft) {

    public enum State {
        ONBOARDING_ROLES,
        ONBOARDING_LOCATIONS,
        ONBOARDING_EXPERIENCE,
        ONBOARDING_INTERNSHIPS,
        ONBOARDING_RESUME,
        /** After /upload: the next PDF is a resume. */
        AWAITING_RESUME,
        /** /prefs → Roles: toggling roles on an existing profile. */
        EDIT_ROLES,
        /** /prefs → Locations. */
        EDIT_LOCATIONS,
        IDLE;

        public boolean onboarding() {
            return name().startsWith("ONBOARDING_");
        }
    }

    public record Draft(List<String> roles, List<String> locations, Double years, Boolean internships) {

        public Draft {
            roles = roles == null ? List.of() : List.copyOf(roles);
            locations = locations == null ? List.of() : List.copyOf(locations);
        }

        public static Draft empty() {
            return new Draft(List.of(), List.of(), null, null);
        }

        public Draft toggleRole(String role) {
            return new Draft(toggle(roles, role), locations, years, internships);
        }

        public Draft toggleLocation(String code) {
            return new Draft(roles, toggle(locations, code), years, internships);
        }

        public Draft withYears(double newYears) {
            return new Draft(roles, locations, newYears, internships);
        }

        public Draft withInternships(boolean include) {
            return new Draft(roles, locations, years, include);
        }

        private static List<String> toggle(List<String> list, String value) {
            Set<String> set = new LinkedHashSet<>(list);
            if (!set.remove(value)) {
                set.add(value);
            }
            return new ArrayList<>(set);
        }
    }

    public static Session idle() {
        return new Session(State.IDLE, Draft.empty());
    }

    public Session with(State newState) {
        return new Session(newState, draft);
    }

    public Session with(Draft newDraft) {
        return new Session(state, newDraft);
    }
}
