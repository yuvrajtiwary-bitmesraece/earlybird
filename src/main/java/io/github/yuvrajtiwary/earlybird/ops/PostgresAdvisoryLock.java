package io.github.yuvrajtiwary.earlybird.ops;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A lock held by a Postgres session: {@code pg_try_advisory_lock} on a dedicated pooled connection, kept until
 * release. It needs no lease: if the process dies, its session ends and Postgres frees the lock at once, so the
 * {@code ttl} is ignored. The cost is one of the pool's 5 connections while a job runs. Needs a session-mode
 * connection, which is why Supabase's session pooler was chosen (Block 0).
 *
 * <p>Names include the schema: dev ({@code earlybird_dev}) and prod ({@code earlybird}) share one database.
 */
public final class PostgresAdvisoryLock implements DistributedLock {

    private static final Logger log = LoggerFactory.getLogger(PostgresAdvisoryLock.class);

    private final DataSource dataSource;
    private final String namespace;

    public PostgresAdvisoryLock(DataSource dataSource, String namespace) {
        this.dataSource = dataSource;
        this.namespace = namespace;
    }

    @Override
    public Optional<Handle> tryAcquire(String name, Duration ttl) {
        String key = namespace + ":" + name;
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            connection.setAutoCommit(true);
            if (!call(connection, "select pg_try_advisory_lock(hashtextextended(?, 0))", key)) {
                connection.close();
                return Optional.empty();
            }
            Connection held = connection;
            return Optional.of(() -> release(held, key));
        } catch (SQLException e) {
            closeQuietly(connection);
            throw new IllegalStateException("Could not take the database lock " + name, e);
        }
    }

    private static void release(Connection connection, String key) {
        try {
            call(connection, "select pg_advisory_unlock(hashtextextended(?, 0))", key);
        } catch (SQLException e) {
            // Closing the connection ends nothing here (it goes back to the pool), so log loudly.
            log.error("Could not release the database lock {}: {}", key, e.getMessage());
        } finally {
            closeQuietly(connection);
        }
    }

    private static boolean call(Connection connection, String sql, String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // nothing more to do
            }
        }
    }
}
