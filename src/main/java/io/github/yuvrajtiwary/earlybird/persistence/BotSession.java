package io.github.yuvrajtiwary.earlybird.persistence;

import java.time.Instant;

/** A row of {@code bot_sessions}: where a user is in a multi-step bot flow, plus that flow's data as JSON. */
public record BotSession(long userId, String state, String dataJson, Instant updatedAt) {}
