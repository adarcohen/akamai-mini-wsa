package com.akamai.miniwsa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.akamai.miniwsa.domain.Action;
import com.akamai.miniwsa.domain.Category;
import com.akamai.miniwsa.domain.SecurityEvent;
import com.akamai.miniwsa.domain.Severity;
import com.akamai.miniwsa.domain.SummaryQuery;
import com.akamai.miniwsa.repository.StatsRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(StatsApiIT.ClockConfiguration.class)
class StatsApiIT {
    private static final Instant NOW = Instant.parse("2026-05-20T15:00:00Z");
    private static final String RANGE = "from=2026-05-20T14:00:00Z&to=2026-05-20T15:00:00Z";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired private TestRestTemplate http;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private StatsRepository repository;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void clearEvents() {
        jdbc.execute("TRUNCATE TABLE security_events");
    }

    @Test
    void aggregatesCountsScoresAndTopListsForOneConfiguration() {
        seedScenario();
        JsonNode summary = summary("configId=14227&" + RANGE);

        assertThat(summary.path("configId").asLong()).isEqualTo(14227);
        assertThat(summary.path("totalEvents").asLong()).isEqualTo(3);
        assertThat(summary.path("byCategory").path("BOT").path("count").asLong()).isEqualTo(2);
        assertThat(summary.path("byCategory").path("BOT").path("avgThreatScore").asDouble()).isEqualTo(32.5);
        assertThat(summary.path("byCategory").path("XSS").path("avgThreatScore").asDouble()).isEqualTo(75.0);
        assertThat(summary.path("byAction").path("MONITOR").asLong()).isEqualTo(1);
        assertThat(summary.path("byAction").path("ALERT").asLong()).isEqualTo(1);
        assertThat(summary.path("byAction").path("DENY").asLong()).isEqualTo(1);
        assertThat(summary.path("topAttackers").get(0).path("clientIp").asText()).isEqualTo("203.0.113.1");
        assertThat(summary.path("topAttackers").get(0).path("count").asLong()).isEqualTo(2);
        assertThat(summary.path("topAttackers").get(0).path("avgThreatScore").asDouble()).isEqualTo(32.5);
        assertThat(summary.path("topTargetedPaths").get(0).path("path").asText()).isEqualTo("/admin");
    }

    @Test
    void omittedConfigAggregatesAllConfigurationsByEventTime() {
        seedScenario();
        JsonNode summary = summary(RANGE);

        assertThat(summary.path("configId").isNull()).isTrue();
        assertThat(summary.path("totalEvents").asLong()).isEqualTo(4);
        assertThat(summary.path("byCategory").path("INJECTION").path("count").asLong()).isEqualTo(1);
        assertThat(summary.path("topTargetedPaths").get(0).path("path").asText()).isEqualTo("/public");
        assertThat(summary.path("topTargetedPaths").get(0).path("count").asLong()).isEqualTo(2);
        assertThat(summary.path("timeRange").path("from").asText()).isEqualTo("2026-05-20T14:00:00Z");
        // Six events arrived at the same server time; only four event timestamps match the range.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM security_events", Long.class)).isEqualTo(6);
    }

    @Test
    void returnsStableEmptyResultForNoMatchingConfiguration() {
        seedScenario();
        JsonNode summary = summary("configId=123&" + RANGE);

        assertThat(summary.path("totalEvents").asLong()).isZero();
        assertThat(summary.path("byCategory").isEmpty()).isTrue();
        assertThat(summary.path("byAction").isEmpty()).isTrue();
        assertThat(summary.path("topAttackers").isEmpty()).isTrue();
        assertThat(summary.path("topTargetedPaths").isEmpty()).isTrue();
    }

    @Test
    void includesBothBoundariesAndAllowsAOneInstantRange() {
        ingest(List.of(
                lowEvent("start", "203.0.113.1", "2026-05-20T14:00:00Z", "/public"),
                lowEvent("end", "203.0.113.2", "2026-05-20T15:00:00Z", "/public"),
                lowEvent("before", "203.0.113.3", "2026-05-20T13:59:59.999999Z", "/public"),
                lowEvent("after", "203.0.113.4", "2026-05-20T15:00:00.000001Z", "/public")));

        assertThat(summary(RANGE).path("totalEvents").asLong()).isEqualTo(2);
        assertThat(summary("from=2026-05-20T14:00:00Z&to=2026-05-20T14:00:00Z")
                .path("totalEvents").asLong()).isEqualTo(1);
    }

    @Test
    void limitsTopListsToTenAndBreaksTiesDeterministically() {
        List<SecurityEvent> events = new ArrayList<>();
        for (int index = 1; index <= 12; index++) {
            events.add(lowEvent("top-" + index, "203.0.113." + index, NOW.toString(), "/p/%02d".formatted(index)));
        }
        events.add(lowEvent("extra-1", "203.0.113.1", NOW.toString(), "/p/01"));
        events.add(lowEvent("extra-2", "203.0.113.1", NOW.toString(), "/p/01"));
        ingest(events);
        JsonNode summary = summary(RANGE);
        JsonNode attackers = summary.path("topAttackers");
        JsonNode paths = summary.path("topTargetedPaths");

        assertThat(attackers.size()).isEqualTo(10);
        assertThat(paths.size()).isEqualTo(10);
        assertThat(attackers.get(0).path("count").asLong()).isEqualTo(3);
        assertThat(paths.get(0).path("count").asLong()).isEqualTo(3);
        assertThat(attackers.get(1).path("clientIp").asText()).isEqualTo("203.0.113.2");
        assertThat(paths.get(1).path("path").asText()).isEqualTo("/p/02");
        assertThat(attackers.get(9).path("clientIp").asText()).isEqualTo("203.0.113.10");
        assertThat(paths.get(9).path("path").asText()).isEqualTo("/p/10");
    }

    @Test
    void roundsAverageScoresToOneDecimal() {
        ingest(List.of(
                lowEvent("low-1", "203.0.113.1", NOW.toString(), "/public"),
                lowEvent("low-2", "203.0.113.2", NOW.toString(), "/public"),
                EventFixtures.event("medium", "203.0.113.3", NOW, 14227,
                        Severity.MEDIUM, Action.MONITOR, Category.BOT, "/public")));
        JsonNode summary = summary(RANGE);
        assertThat(summary.path("byCategory").path("BOT").path("avgThreatScore").asDouble()).isEqualTo(13.3);
    }

    @Test
    void combinesEquivalentIpv6Attackers() {
        ingest(List.of(
                lowEvent("v6-1", "2001:db8::1", NOW.toString(), "/public"),
                lowEvent("v6-2", "2001:0db8:0:0:0:0:0:1", NOW.toString(), "/public")));
        JsonNode attackers = summary(RANGE).path("topAttackers");
        assertThat(attackers.size()).isEqualTo(1);
        assertThat(attackers.get(0).path("clientIp").asText()).isEqualTo("2001:db8::1");
        assertThat(attackers.get(0).path("count").asLong()).isEqualTo(2);
    }

    @Test
    void acceptsExplicitTimezoneOffsets() {
        seedScenario();
        URI uri = URI.create(http.getRootUri() + "/v1/stats/summary?"
                + "from=2026-05-20T17:00:00%2B03:00&to=2026-05-20T18:00:00%2B03:00");
        var response = http.getForEntity(uri, JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("totalEvents").asLong()).isEqualTo(4);
        assertThat(response.getBody().path("timeRange").path("from").asText()).isEqualTo("2026-05-20T14:00:00Z");
    }

    static Stream<String> invalidQueries() {
        return Stream.of(
                "", "from=2026-05-20T14:00:00Z", "to=2026-05-20T15:00:00Z",
                "from=invalid&to=2026-05-20T15:00:00Z",
                "from=2026-05-20T14:00:00Z&to=invalid",
                "from=2026-05-21T00:00:00Z&to=2026-05-20T00:00:00Z",
                "configId=0&" + RANGE, "configId=-1&" + RANGE,
                "configId=abc&" + RANGE, "configId=1.5&" + RANGE);
    }

    @ParameterizedTest
    @MethodSource("invalidQueries")
    void rejectsInvalidOrMissingQueryParameters(String query) {
        var response = http.getForEntity("/v1/stats/summary?" + query, JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody().path("detail").asText()).isNotBlank();
    }

    @Test
    void keepsAllBreakdownsInOneSnapshotDuringConcurrentIngestion() throws Exception {
        ingest(List.of(lowEvent("before", "203.0.113.1", NOW.toString(), "/public")));
        CountDownLatch snapshotRead = new CountDownLatch(1);
        CountDownLatch continueSummary = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            snapshotRead.countDown();
            if (!continueSummary.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for concurrent ingestion");
            }
            return result;
        }).when(repository).categoryStats(any(SummaryQuery.class));

        CompletableFuture<JsonNode> pending = CompletableFuture.supplyAsync(() -> summary(RANGE));
        try {
            assertThat(snapshotRead.await(10, TimeUnit.SECONDS)).isTrue();
            ingest(List.of(EventFixtures.event("after", "203.0.113.2", NOW, 14227,
                    Severity.HIGH, Action.DENY, Category.XSS, "/admin")));
            continueSummary.countDown();
            JsonNode response = pending.get(20, TimeUnit.SECONDS);

            assertThat(response.path("totalEvents").asLong()).isEqualTo(1);
            assertThat(response.path("byCategory").has("XSS")).isFalse();
            assertThat(response.path("byAction").has("DENY")).isFalse();
            assertThat(response.path("topAttackers").size()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM security_events", Long.class)).isEqualTo(2);
        } finally {
            continueSummary.countDown();
        }
    }

    @Test
    void publishesSummaryParametersAndResponseInSwagger() {
        JsonNode document = http.getForObject("/v3/api-docs", JsonNode.class);
        JsonNode operation = document.path("paths").path("/v1/stats/summary").path("get");
        assertThat(operation.path("parameters").size()).isEqualTo(3);
        assertThat(operation.path("responses").has("200")).isTrue();
        assertThat(operation.path("responses").path("200").toString()).contains("StatsSummary");
        assertThat(operation.path("parameters").toString()).contains("configId", "from", "to");
    }

    private JsonNode summary(String query) {
        var response = http.getForEntity("/v1/stats/summary?" + query, JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private void ingest(List<SecurityEvent> events) {
        var response = http.postForEntity("/v1/events/ingest", events, JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().path("accepted").asInt()).isEqualTo(events.size());
    }

    private SecurityEvent lowEvent(String id, String ip, String timestamp, String path) {
        return EventFixtures.event(id, ip, Instant.parse(timestamp), 14227,
                Severity.LOW, Action.MONITOR, Category.BOT, path);
    }

    private void seedScenario() {
        ingest(List.of(
                lowEvent("a", "203.0.113.1", "2026-05-20T14:00:00Z", "/public"),
                EventFixtures.event("b", "203.0.113.1", Instant.parse("2026-05-20T14:10:00Z"), 14227,
                        Severity.HIGH, Action.ALERT, Category.BOT, "/login"),
                EventFixtures.event("c", "203.0.113.2", Instant.parse("2026-05-20T14:20:00Z"), 14227,
                        Severity.CRITICAL, Action.DENY, Category.XSS, "/admin"),
                EventFixtures.event("d", "203.0.113.3", Instant.parse("2026-05-20T14:30:00Z"), 99,
                        Severity.MEDIUM, Action.DENY, Category.INJECTION, "/public"),
                lowEvent("outside-before", "203.0.113.4", "2026-05-20T13:59:59Z", "/excluded"),
                lowEvent("outside-after", "203.0.113.5", "2026-05-20T15:00:01Z", "/excluded")));
    }

    @TestConfiguration
    static class ClockConfiguration {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
