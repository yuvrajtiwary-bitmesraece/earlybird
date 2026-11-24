package io.github.yuvrajtiwary.earlybird.telegram.api;

/** A button that either sends {@code callbackData} back to us or opens {@code url}. */
public record InlineKeyboardButton(String text, String callbackData, String url) {

    public static InlineKeyboardButton callback(String text, String data) {
        return new InlineKeyboardButton(text, data, null);
    }

    public static InlineKeyboardButton link(String text, String url) {
        return new InlineKeyboardButton(text, null, url);
    }
}
