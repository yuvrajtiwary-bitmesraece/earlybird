package io.github.yuvrajtiwary.earlybird;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yuvrajtiwary.earlybird.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@IntegrationTest
class MigrationIT {

    @Autowired
    JdbcClient jdbc;

    @Test
    void v1CreatesEveryTableInTheConfiguredSchema() {
        var tables = jdbc.sql("select table_name from information_schema.tables where table_schema = 'earlybird'")
                .query(String.class)
                .list();
        assertThat(tables).contains(
                "boards", "postings", "posting_tags", "users", "profiles", "bot_sessions", "alerts",
                "processed_events", "telegram_updates", "ticks", "flyway_schema_history");
        assertThat(jdbc.sql("select count(*) from information_schema.tables where table_schema = 'public'")
                .query(Integer.class).single()).isZero();
    }

    @Test
    void migrationIsRecordedAsSuccessful() {
        Boolean success = jdbc.sql("select success from earlybird.flyway_schema_history where version = '1'")
                .query(Boolean.class)
                .single();
        assertThat(success).isTrue();
    }

    @Test
    void pooledConnectionsUseTheConfiguredSchema() {
        assertThat(jdbc.sql("select current_schema()").query(String.class).single()).isEqualTo("earlybird");
    }
}
