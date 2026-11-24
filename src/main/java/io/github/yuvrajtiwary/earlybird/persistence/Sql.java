package io.github.yuvrajtiwary.earlybird.persistence;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Small JDBC conversions shared by the repositories.
 *
 * <p>Times: the PostgreSQL driver maps {@code timestamptz} to {@link OffsetDateTime}, not {@link Instant},
 * so Instants are converted at the edge (always UTC). Enums: stored as lowercase text that matches the
 * CHECK constraints in V1.
 */
final class Sql {

    private Sql() {}

    static OffsetDateTime ts(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    static String[] textArray(Collection<String> values) {
        return values == null ? new String[0] : values.toArray(String[]::new);
    }

    static String[] enumArray(Collection<? extends Enum<?>> values) {
        return values == null ? new String[0] : values.stream().map(Sql::db).toArray(String[]::new);
    }

    static List<String> list(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        if (array == null) {
            return List.of();
        }
        try {
            return List.of((String[]) array.getArray());
        } finally {
            array.free();
        }
    }

    static <E extends Enum<E>> List<E> enumList(ResultSet rs, String column, Class<E> type) throws SQLException {
        return list(rs, column).stream().map(v -> enumOf(type, v)).toList();
    }

    static Double dbl(ResultSet rs, String column) throws SQLException {
        BigDecimal value = rs.getBigDecimal(column);
        return value == null ? null : value.doubleValue();
    }

    static Long longOrNull(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    static Integer intOrNull(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    static String db(Enum<?> value) {
        return value == null ? null : value.name().toLowerCase(Locale.ROOT);
    }

    static <E extends Enum<E>> E enumOf(Class<E> type, String value) {
        return value == null ? null : Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
    }

    static <E extends Enum<E>> List<String> dbValues(Class<E> type) {
        return Arrays.stream(type.getEnumConstants()).map(Sql::db).toList();
    }
}
