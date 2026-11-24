package io.github.yuvrajtiwary.earlybird.telegram.api;

public record Message(long messageId, TgUser from, Chat chat, Long date, String text, Document document) {}
