package com.akamai.miniwsa.repository;

import com.akamai.miniwsa.domain.TimeSeriesQuery;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TimeSeriesRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public TimeSeriesRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<Instant, Long> counts(TimeSeriesQuery query) {
        String sql = """
                SELECT date_bin(CAST(:stride AS interval), event_time,
                                TIMESTAMPTZ '1970-01-01 00:00:00+00') AS bucket,
                       count(*) AS event_count
                FROM security_events
                WHERE event_time >= :from AND event_time <= :to
                """;
        // PostgreSQL stores microseconds. Round inward so driver rounding cannot widen the range.
        Instant lowerBound = query.from().truncatedTo(ChronoUnit.MICROS);
        if (lowerBound.isBefore(query.from())) {
            lowerBound = lowerBound.plus(1, ChronoUnit.MICROS);
        }
        Instant upperBound = query.to().truncatedTo(ChronoUnit.MICROS);
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("stride", query.interval().seconds() + " seconds")
                .addValue("from", lowerBound.atOffset(ZoneOffset.UTC))
                .addValue("to", upperBound.atOffset(ZoneOffset.UTC));
        if (query.configId() != null) {
            sql += " AND config_id = :configId";
            parameters.addValue("configId", query.configId());
        }
        Map<Instant, Long> counts = new HashMap<>();
        jdbc.query(sql + " GROUP BY bucket", parameters, row -> {
            counts.put(row.getObject("bucket", OffsetDateTime.class).toInstant(), row.getLong("event_count"));
        });
        return counts;
    }
}
