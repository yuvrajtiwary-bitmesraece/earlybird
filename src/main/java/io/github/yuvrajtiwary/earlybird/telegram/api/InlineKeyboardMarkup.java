package io.github.yuvrajtiwary.earlybird.telegram.api;

import java.util.List;

public record InlineKeyboardMarkup(List<List<InlineKeyboardButton>> inlineKeyboard) {

    public InlineKeyboardMarkup {
        inlineKeyboard = inlineKeyboard.stream().map(List::copyOf).toList();
    }
}
