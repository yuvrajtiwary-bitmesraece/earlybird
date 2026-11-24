package io.github.yuvrajtiwary.earlybird.telegram.api;

/** A Telegram account (Telegram calls this type "User"). */
public record TgUser(long id, boolean isBot, String firstName, String username) {}
