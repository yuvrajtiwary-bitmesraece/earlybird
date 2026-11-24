package io.github.yuvrajtiwary.earlybird.telegram.api;

/** @param type "private", "group", "supergroup" or "channel"; EarlyBird only talks in private chats */
public record Chat(long id, String type) {

    public boolean isPrivate() {
        return "private".equals(type);
    }
}
