package io.github.yuvrajtiwary.earlybird.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Telegram re-delivers a webhook update if we don't answer in time; this makes handling it twice impossible. */
@Repository
public class TelegramUpdateRepository {

    private final JdbcClient jdbc;

    public TelegramUpdateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true the first time this update id is seen */
    public boolean markReceived(long updateId) {
        return jdbc.sql("insert into telegram_updates (update_id) values (:id) on conflict do nothing")
                .param("id", updateId)
                .update() == 1;
    }
}
