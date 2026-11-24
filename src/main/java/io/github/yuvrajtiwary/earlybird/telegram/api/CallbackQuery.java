package io.github.yuvrajtiwary.earlybird.telegram.api;

/** A tap on an inline button. {@code data} is the button's callback data (at most 64 bytes). */
public record CallbackQuery(String id, TgUser from, Message message, String data) {}
