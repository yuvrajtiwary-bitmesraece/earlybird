package io.github.yuvrajtiwary.earlybird.matching;

/**
 * "Recent matches" (spec 1.1 and 3.9): right after onboarding or a profile change, up to 3 good matches from
 * the last 48 hours, so a new user sees value at once.
 */
public interface RecentMatches {

    /** @return how many were sent */
    int sendTo(long userId);
}
