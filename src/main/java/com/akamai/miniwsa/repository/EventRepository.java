package com.akamai.miniwsa.repository;

import com.akamai.miniwsa.domain.ClientIp;
import com.akamai.miniwsa.domain.ReceivedEvent;
import com.akamai.miniwsa.domain.SecurityEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class EventRepository {
    private static final String INSERT_EVENT = """
            INSERT INTO security_events
                (event_id, event_time, config_id, category, action, client_ip, path, received_at, payload)
            VALUES
                (:id, :time, :config, :category, :action, CAST(:ip AS inet), :path, :received, CAST(:payload AS jsonb))
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public EventRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void saveAll(List<ReceivedEvent> events) {
        MapSqlParameterSource[] batch = events.stream()
                .map(this::parameters)
                .toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate(INSERT_EVENT, batch);
    }

    private MapSqlParameterSource parameters(ReceivedEvent received) {
        SecurityEvent event = received.event();
        return new MapSqlParameterSource("id", event.eventId())
                .addValue("time", event.timestamp().atOffset(ZoneOffset.UTC))
                .addValue("config", event.configId())
                .addValue("category", event.rule().category().name())
                .addValue("action", event.action().name())
                .addValue("ip", ClientIp.canonical(event.clientIp()))
                .addValue("path", event.path())
                .addValue("received", received.receivedAt().atOffset(ZoneOffset.UTC))
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
