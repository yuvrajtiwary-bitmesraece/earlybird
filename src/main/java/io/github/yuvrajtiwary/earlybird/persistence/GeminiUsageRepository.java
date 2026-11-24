package io.github.yuvrajtiwary.earlybird.persistence;

import java.time.LocalDate;
import java.util.OptionalInt;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class GeminiUsageRepository {

    private final JdbcClient jdbc;

    public GeminiUsageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Counts one call for {@code day} unless the day already reached {@code max}. One atomic statement: two
     * callers racing for the last call can't both get it.
     *
     * @return the new count, or empty if the budget was already used up
     */
    public OptionalInt tryIncrement(LocalDate day, int max) {
        if (max <= 0) {
            return OptionalInt.empty();
        }
        return jdbc.sql("""
                        insert into gemini_usage (day, calls) values (:day, 1)
                        on conflict (day) do update set calls = gemini_usage.calls + 1
                            where gemini_usage.calls < :max
                        returning calls
                        """)
                .param("day", day)
                .param("max", max)
                .query(Integer.class)
                .optional()
                .map(OptionalInt::of)
                .orElse(OptionalInt.empty());
    }

    public int used(LocalDate day) {
        return jdbc.sql("select coalesce(max(calls), 0) from gemini_usage where day = :day")
                .param("day", day)
                .query(Integer.class)
                .single();
    }
}
