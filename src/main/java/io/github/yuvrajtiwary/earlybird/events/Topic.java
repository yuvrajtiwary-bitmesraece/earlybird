package io.github.yuvrajtiwary.earlybird.events;

/**
 * The five event streams (spec section 3.2). The full Kafka topic name is {@code KAFKA_TOPIC_PREFIX} plus
 * the suffix, e.g. {@code eb.jobs.discovered.v1}. The {@code .v1} suffix lets a breaking payload change ship
 * as a new topic while old consumers drain the old one.
 */
public enum Topic {
    /** Key: postingId. Poller → enricher. */
    JOBS_DISCOVERED("jobs.discovered.v1"),
    /** Key: postingId. Enricher → matcher. */
    JOBS_ENRICHED("jobs.enriched.v1"),
    /** Key: postingId. Poller → matcher (cancels queued alerts). */
    JOBS_CLOSED("jobs.closed.v1"),
    /** Key: userId, so one user's alerts stay in order. Matcher → notifier. */
    ALERTS_REQUESTED("alerts.requested.v1"),
    /** Key: the original key. Any consumer → admin notification. */
    DLQ("dlq.v1");

    private final String suffix;

    Topic(String suffix) {
        this.suffix = suffix;
    }

    public String topicName(String prefix) {
        return prefix + suffix;
    }
}
