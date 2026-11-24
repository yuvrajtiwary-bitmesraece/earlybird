package io.github.yuvrajtiwary.earlybird.persistence;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Consumer idempotency. Kafka delivers at least once, so a consumer can see the same event twice. Calling
 * {@link #markProcessed} inside the consumer's own database transaction makes the second delivery a no-op.
 */
@Repository
public class ProcessedEventRepository {

    private final JdbcClient jdbc;

    public ProcessedEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true the first time this consumer sees the event, false for a duplicate delivery */
    public boolean markProcessed(UUID eventId, String consumer) {
        return jdbc.sql("""
                        insert into processed_events (event_id, consumer) values (:eventId, :consumer)
                        on conflict do nothing
                        """)
                .param("eventId", eventId)
                .param("consumer", consumer)
                .update() == 1;
    }
}
