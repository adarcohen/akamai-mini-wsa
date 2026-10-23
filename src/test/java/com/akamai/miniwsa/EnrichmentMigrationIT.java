package com.akamai.miniwsa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.akamai.miniwsa.domain.Action;
import com.akamai.miniwsa.domain.Category;
import com.akamai.miniwsa.domain.SecurityEvent;
import com.akamai.miniwsa.domain.Severity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class EnrichmentMigrationIT {
    private static final Instant NOW = Instant.parse("2026-05-20T15:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void upgradesExistingV1EventsWithoutDroppingData() throws Exception {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target("1").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));

        insert(jdbc, EventFixtures.event("expired-legacy"), NOW.minusSeconds(601));
        for (int index = 0; index < 6; index++) {
            insert(jdbc, EventFixtures.event("legacy-" + index), NOW);
        }
        Map<Category, String> expectedTypes = Map.of(
                Category.INJECTION, "SQL/Command Injection",
                Category.XSS, "Cross-Site Scripting",
                Category.PROTOCOL_VIOLATION, "Protocol Anomaly",
                Category.DATA_LEAKAGE, "Data Exfiltration",
                Category.BOT, "Bot Activity",
                Category.DOS, "Denial of Service",
                Category.RATE_LIMIT, "Rate Limiting");
        int address = 1;
        for (Category category : Category.values()) {
            var event = EventFixtures.event("category-" + category, "198.51.100." + address++, NOW,
                    14227L, Severity.MEDIUM, Action.MONITOR, category, "/public");
            insert(jdbc, event, NOW);
        }

        var result = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target("2").load().migrate();

        assertThat(result.migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM security_events", Long.class)).isEqualTo(14);
        assertThat(score(jdbc, "legacy-4")).isEqualTo(75);
        assertThat(score(jdbc, "legacy-5")).isEqualTo(90);
        assertThat(score(jdbc, "expired-legacy")).isEqualTo(75);
        for (var entry : expectedTypes.entrySet()) {
            String id = "category-" + entry.getKey();
            assertThat(jdbc.queryForObject(
                    "SELECT attack_type FROM security_events WHERE event_id = ?", String.class, id))
                    .isEqualTo(entry.getValue());
            assertThat(score(jdbc, id)).isEqualTo(20);
        }
        String payload = jdbc.queryForObject(
                "SELECT payload::text FROM security_events WHERE event_id = ?", String.class, "legacy-0");
        assertThat(mapper.readValue(payload, SecurityEvent.class)).isEqualTo(EventFixtures.event("legacy-0"));
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE security_events SET threat_score = 101 WHERE event_id = ?", "legacy-0"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private int score(JdbcTemplate jdbc, String id) {
        return jdbc.queryForObject("SELECT threat_score FROM security_events WHERE event_id = ?", Integer.class, id);
    }

    private void insert(JdbcTemplate jdbc, SecurityEvent event, Instant receivedAt) throws Exception {
        jdbc.update("""
                INSERT INTO security_events
                    (event_id, event_time, config_id, category, action, client_ip, path, received_at, payload)
                VALUES (?, ?, ?, ?, ?, CAST(? AS inet), ?, ?, CAST(? AS jsonb))
                """, event.eventId(), event.timestamp().atOffset(ZoneOffset.UTC), event.configId(),
                event.rule().category().name(), event.action().name(), event.clientIp(), event.path(),
                receivedAt.atOffset(ZoneOffset.UTC), mapper.writeValueAsString(event));
    }
}
