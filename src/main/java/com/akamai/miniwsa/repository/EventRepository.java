package com.akamai.miniwsa.repository;

import com.akamai.miniwsa.domain.ClientIp;
import com.akamai.miniwsa.domain.EnrichedEvent;
import com.akamai.miniwsa.domain.SecurityEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;

@Repository
public class EventRepository {
    private static final String INSERT_EVENT = """
            INSERT INTO security_events
                (event_id, event_time, config_id, category, action, client_ip, path,
                 received_at, attack_type, threat_score, payload)
            VALUES
                (:id, :time, :config, :category, :action, CAST(:ip AS inet), :path,
                 :received, :attackType, :threatScore, CAST(:payload AS jsonb))
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public EventRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** Sorted database-wide transaction locks coordinate IP counts across application instances. */
    public void lockClients(List<String> clients) {
        jdbc.queryForList("""
                SELECT pg_advisory_xact_lock(lock_key)
                FROM (
                    SELECT DISTINCT hashtextextended(ip, 0) AS lock_key
                    FROM unnest(CAST(:clients AS text[])) AS clients(ip)
                    ORDER BY lock_key
                ) ordered_keys
                """, new MapSqlParameterSource("clients", new SqlArrayValue("text", clients.toArray())));
    }

    /** One database call for all clients; only five prior arrivals per IP affect the policy. */
    public Map<String, Long> recentCounts(List<String> clients, Instant cutoff, Instant now) {
        Map<String, Long> counts = new HashMap<>();
        jdbc.query("""
                SELECT clients.ip, (
                    SELECT count(*) FROM (
                        SELECT 1 FROM security_events history
                        WHERE history.client_ip = CAST(clients.ip AS inet)
                          AND history.received_at >= :cutoff
                          AND history.received_at <= :now
                        LIMIT 5
                    ) recent
                ) AS recent_count
                FROM unnest(CAST(:clients AS text[])) AS clients(ip)
                """, new MapSqlParameterSource("clients", new SqlArrayValue("text", clients.toArray()))
                        .addValue("cutoff", cutoff.atOffset(ZoneOffset.UTC))
                        .addValue("now", now.atOffset(ZoneOffset.UTC)),
                rs -> { counts.put(rs.getString("ip"), rs.getLong("recent_count")); });
        return counts;
    }

    public void saveAll(List<EnrichedEvent> events) {
        MapSqlParameterSource[] batch = events.stream()
                .map(this::parameters)
                .toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate(INSERT_EVENT, batch);
    }

    private MapSqlParameterSource parameters(EnrichedEvent received) {
        SecurityEvent event = received.event();
        return new MapSqlParameterSource("id", event.eventId())
                .addValue("time", event.timestamp().atOffset(ZoneOffset.UTC))
                .addValue("config", event.configId())
                .addValue("category", event.rule().category().name())
                .addValue("action", event.action().name())
                .addValue("ip", ClientIp.canonical(event.clientIp()))
                .addValue("path", event.path())
                .addValue("received", received.receivedAt().atOffset(ZoneOffset.UTC))
                .addValue("attackType", received.attackType())
                .addValue("threatScore", received.threatScore())
                .addValue("payload", serialize(event));
    }

    private String serialize(SecurityEvent event) {
        try {
            return mapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize a validated event", exception);
        }
    }
}
