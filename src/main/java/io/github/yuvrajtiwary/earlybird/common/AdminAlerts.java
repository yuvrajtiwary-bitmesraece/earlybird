package io.github.yuvrajtiwary.earlybird.common;

/**
 * "Tell the admin": the notices of spec 3.11 (Kafka down, dead letters, Gemini budget used up, …). Implemented by
 * {@code ops.AdminNotifier}, which sends them on Telegram; modules depend on this interface, not on ops.
 */
@FunctionalInterface
public interface AdminAlerts {

    /** Sends {@code html} (Telegram HTML, already escaped) unless a notice of this {@code kind} went out today. */
    void notifyOncePerDay(String kind, String html);
}
