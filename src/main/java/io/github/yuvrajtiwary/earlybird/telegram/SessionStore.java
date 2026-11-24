package io.github.yuvrajtiwary.earlybird.telegram;

import io.github.yuvrajtiwary.earlybird.persistence.BotSessionRepository;
import java.time.Clock;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Loads and saves {@link Session}s as {@code bot_sessions} rows (state + draft as JSON). */
public class SessionStore {

    private final BotSessionRepository sessions;
    private final ObjectMapper mapper;
    private final Clock clock;

    public SessionStore(BotSessionRepository sessions, ObjectMapper mapper, Clock clock) {
        this.sessions = sessions;
        this.mapper = mapper;
        this.clock = clock;
    }

    public Session load(long userId) {
        return sessions.find(userId).map(row -> {
            try {
                return new Session(Session.State.valueOf(row.state()),
                        mapper.readValue(row.dataJson(), Session.Draft.class));
            } catch (IllegalArgumentException | JacksonException e) {
                return Session.idle(); // a state from an older version: start clean rather than fail
            }
        }).orElse(Session.idle());
    }

    public void save(long userId, Session session) {
        sessions.save(userId, session.state().name(), mapper.writeValueAsString(session.draft()), clock.instant());
    }
}
