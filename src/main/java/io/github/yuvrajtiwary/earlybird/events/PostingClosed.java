package io.github.yuvrajtiwary.earlybird.events;

import java.time.Instant;
import java.util.UUID;

/** A posting disappeared from its board (topic {@code eb.jobs.closed.v1}); queued alerts for it are cancelled. */
public record PostingClosed(UUID postingId, Instant closedAt) {}
