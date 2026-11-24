package io.github.yuvrajtiwary.earlybird.persistence;

/**
 * Job families. A posting gets exactly one (from tagging); a user picks several in {@code /prefs}.
 * Matching starts with "users whose roles include this posting's category".
 */
public enum RoleCategory {
    BACKEND,
    FRONTEND,
    FULLSTACK,
    MOBILE,
    DATA_ENGINEERING,
    DATA_SCIENCE_ML,
    DEVOPS_SRE_CLOUD,
    QA_TEST,
    SECURITY,
    EMBEDDED,
    OTHER
}
