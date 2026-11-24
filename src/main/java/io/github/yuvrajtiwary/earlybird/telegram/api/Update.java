package io.github.yuvrajtiwary.earlybird.telegram.api;

/** One incoming event from Telegram. We subscribe to two kinds only: messages and button taps. */
public record Update(long updateId, Message message, CallbackQuery callbackQuery) {

    /** The Telegram user behind the update, or null. */
    public TgUser sender() {
        if (message != null) {
            return message.from();
        }
        return callbackQuery != null ? callbackQuery.from() : null;
    }
}
