package io.github.yuvrajtiwary.earlybird.persistence;

import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Retention deletes (spec 3.4). Each returns how many rows went. */
@Repository
public class MaintenanceRepository {

    private final JdbcClient jdbc;

    public MaintenanceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Postings closed before {@code before}; their tags and alerts go with them (foreign keys cascade). */
    public int deleteClosedPostings(Instant before) {
        return delete("delete from postings where closed_at < :t", before);
    }

    /** A description is kept only until tagging; one still untagged after this long never will be. */
    public int clearStaleDescriptions(Instant before) {
        return jdbc.sql("update postings set description_text = null where description_text is not null and first_seen_at < :t")
                .param("t", Sql.ts(before)).update();
    }

    public int deleteAlerts(Instant before) {
        return delete("delete from alerts where created_at < :t", before);
    }

    public int deleteProcessedEvents(Instant before) {
        return delete("delete from processed_events where processed_at < :t", before);
    }

    public int deleteTelegramUpdates(Instant before) {
        return delete("delete from telegram_updates where received_at < :t", before);
    }

    public int deleteTicks(Instant before) {
        return delete("delete from ticks where started_at < :t", before);
    }

    private int delete(String sql, Instant before) {
        return jdbc.sql(sql).param("t", Sql.ts(before)).update();
    }
}
