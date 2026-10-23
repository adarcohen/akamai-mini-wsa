package com.akamai.miniwsa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.akamai.miniwsa.domain.Action;
import com.akamai.miniwsa.domain.Category;
import com.akamai.miniwsa.domain.SamplesQuery;
import com.akamai.miniwsa.domain.SecurityEvent;
import com.akamai.miniwsa.domain.Severity;
import com.akamai.miniwsa.repository.SamplesRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
@Import(SamplesApiIT.ClockConfiguration.class)
class SamplesApiIT {
    private static final Instant NOW = Instant.parse("2026-05-20T15:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired private TestRestTemplate http;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private SamplesRepository repository;

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
    void defaultsToTwentyEventsAndReturnsTotalBeforePagination() {
        List<SecurityEvent> events = new ArrayList<>();
        for (int index = 0; index < 25; index++) {
            events.add(event("event-%02d".formatted(index), NOW.plusSeconds(index).toString(),
                    14227, Category.BOT, Action.MONITOR, "/public"));
        }
        ingest(events);
        JsonNode page = samples("");

        assertThat(page.path("total").asLong()).isEqualTo(25);
        assertThat(page.path("limit").asInt()).isEqualTo(20);
        assertThat(page.path("offset").asInt()).isZero();
        assertThat(page.path("events").size()).isEqualTo(20);
        assertThat(page.path("events").get(0).path("eventId").asText()).isEqualTo("event-24");
        assertThat(page.path("events").get(19).path("eventId").asText()).isEqualTo("event-05");
    }

    @Test
    void supportsTheMaximumPageSize() {
        List<SecurityEvent> events = new ArrayList<>();
        for (int index = 0; index < 105; index++) {
            events.add(event("max-" + index, NOW.toString(), 14227, Category.BOT, Action.MONITOR, "/public"));
        }
        ingest(events);
        JsonNode page = samples("limit=100");
        assertThat(page.path("total").asLong()).isEqualTo(105);
        assertThat(page.path("events").size()).isEqualTo(100);
    }

    @Test
    void paginatesAndBreaksTimestampTiesByDescendingEventId() {
        seedScenario();
        JsonNode first = samples("limit=2");
        JsonNode second = samples("limit=2&offset=2");

        assertThat(ids(first)).containsExactly("d", "c");
        assertThat(ids(second)).containsExactly("b", "a");
        assertThat(first.path("total").asLong()).isEqualTo(4);
        assertThat(second.path("total").asLong()).isEqualTo(4);
    }

    @Test
    void ordersByEventTimeRatherThanArrivalTimeOrIdAlone() {
        ingest(List.of(
                event("z-old", "2026-05-20T14:00:00Z", 14227, Category.BOT, Action.MONITOR, "/public"),
                event("a-new", "2026-05-20T14:20:00Z", 14227, Category.BOT, Action.MONITOR, "/public")));
        assertThat(ids(samples(""))).containsExactly("a-new", "z-old");
    }

    @Test
    void combinesAllFilters() {
        seedScenario();
        JsonNode page = samples("configId=14227&category=INJECTION&action=DENY"
                + "&from=2026-05-20T14:05:00Z&to=2026-05-20T14:20:00Z");
        assertThat(page.path("total").asLong()).isEqualTo(2);
        assertThat(ids(page)).containsExactly("d", "b");
    }

    @ParameterizedTest
    @CsvSource({"configId=99,c", "category=XSS,c", "action=MONITOR,a"})
    void supportsIndividualFilters(String filter, String expectedId) {
        seedScenario();
        JsonNode page = samples(filter);
        assertThat(page.path("total").asLong()).isEqualTo(1);
        assertThat(ids(page)).containsExactly(expectedId);
    }

    @Test
    void supportsEitherTimeBoundAndInclusiveEqualBounds() {
        seedScenario();
        assertThat(ids(samples("from=2026-05-20T14:10:00Z"))).containsExactly("d", "c", "b");
        assertThat(ids(samples("to=2026-05-20T14:10:00Z"))).containsExactly("b", "a");
        assertThat(ids(samples("from=2026-05-20T14:20:00Z&to=2026-05-20T14:20:00Z")))
                .containsExactly("d", "c");
    }

    @Test
    void returnsEmptyPagesWithoutLosingTheMatchingTotal() {
        assertThat(samples("").path("total").asLong()).isZero();
        seedScenario();
        JsonNode noMatch = samples("configId=123");
        assertThat(noMatch.path("total").asLong()).isZero();
        assertThat(noMatch.path("events").isEmpty()).isTrue();
        JsonNode beyond = samples("limit=2&offset=100");
        assertThat(beyond.path("total").asLong()).isEqualTo(4);
        assertThat(beyond.path("offset").asInt()).isEqualTo(100);
        assertThat(beyond.path("events").isEmpty()).isTrue();
    }

    @Test
    void returnsTheOriginalFieldsAndStoredEnrichmentWithoutRescoring() {
        var response = http.postForEntity("/v1/events/ingest", EventFixtures.event("original"), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode original = response.getBody().path("events").get(0);
        List<SecurityEvent> more = new ArrayList<>();
        for (int index = 0; index < 5; index++) { more.add(EventFixtures.event("later-" + index)); }
        ingest(more);

        JsonNode page = samples("");
        JsonNode storedOriginal = StreamSupport.stream(page.path("events").spliterator(), false)
                .filter(item -> item.path("eventId").asText().equals("original"))
                .findFirst().orElseThrow();
        assertThat(storedOriginal).isEqualTo(original);
        assertThat(storedOriginal.path("threatScore").asInt()).isEqualTo(75);
        assertThat(StreamSupport.stream(page.path("events").spliterator(), false)
                .anyMatch(item -> item.path("threatScore").asInt() == 90)).isTrue();
    }

    static Stream<String> invalidQueries() {
        return Stream.of("limit=0", "limit=-1", "limit=101", "limit=abc", "offset=-1", "offset=abc",
                "configId=0", "configId=-1", "configId=abc", "category=INVALID", "category=0", "action=INVALID",
                "from=invalid", "to=invalid", "from=2026-05-21T00:00:00Z&to=2026-05-20T00:00:00Z");
    }

    @ParameterizedTest
    @MethodSource("invalidQueries")
    void rejectsInvalidFiltersAndPagination(String query) {
        var response = http.getForEntity("/v1/events/samples?" + query, JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody().path("detail").asText()).isNotBlank();
    }

    @Test
    void usesOneSnapshotForCountAndRowsDuringConcurrentIngestion() throws Exception {
        ingest(List.of(EventFixtures.event("before")));
        CountDownLatch countRead = new CountDownLatch(1);
        CountDownLatch continuePage = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object count = invocation.callRealMethod();
            countRead.countDown();
            if (!continuePage.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for concurrent ingestion");
            }
            return count;
        }).when(repository).count(any(SamplesQuery.class));

        var pending = CompletableFuture.supplyAsync(() -> samples(""));
        try {
            assertThat(countRead.await(10, TimeUnit.SECONDS)).isTrue();
            ingest(List.of(EventFixtures.event("after")));
            continuePage.countDown();
            JsonNode page = pending.get(20, TimeUnit.SECONDS);
            assertThat(page.path("total").asLong()).isEqualTo(1);
            assertThat(ids(page)).containsExactly("before");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM security_events", Long.class)).isEqualTo(2);
        } finally {
            continuePage.countDown();
        }
    }

    @Test
    void documentsAllOptionalFiltersAndThePageResponse() {
        JsonNode document = http.getForObject("/v3/api-docs", JsonNode.class);
        JsonNode operation = document.path("paths").path("/v1/events/samples").path("get");
        assertThat(operation.path("parameters").size()).isEqualTo(7);
        for (JsonNode parameter : operation.path("parameters")) {
            assertThat(parameter.path("required").asBoolean()).isFalse();
        }
        assertThat(operation.path("responses").path("200").toString()).contains("SamplesPage");
        JsonNode properties = document.path("components").path("schemas").path("SamplesPage").path("properties");
        assertThat(properties.has("total")).isTrue();
        assertThat(properties.path("events").path("items").toString()).contains("EnrichedEvent");
    }

    private JsonNode samples(String query) {
        var response = http.getForEntity("/v1/events/samples?" + query, JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private List<String> ids(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.path("events").forEach(event -> ids.add(event.path("eventId").asText()));
        return ids;
    }

    private void ingest(List<SecurityEvent> events) {
        assertThat(http.postForEntity("/v1/events/ingest", events, JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    private SecurityEvent event(String id, String time, long config, Category category, Action action, String path) {
        return EventFixtures.event(id, "203.0.113.42", Instant.parse(time), config,
                Severity.CRITICAL, action, category, path);
    }

    private void seedScenario() {
        ingest(List.of(
                event("a", "2026-05-20T14:00:00Z", 14227, Category.BOT, Action.MONITOR, "/public"),
                event("b", "2026-05-20T14:10:00Z", 14227, Category.INJECTION, Action.DENY, "/login"),
                event("c", "2026-05-20T14:20:00Z", 99, Category.XSS, Action.ALERT, "/public"),
                event("d", "2026-05-20T14:20:00Z", 14227, Category.INJECTION, Action.DENY, "/admin")));
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
