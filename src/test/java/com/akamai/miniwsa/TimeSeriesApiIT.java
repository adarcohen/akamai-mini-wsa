package com.akamai.miniwsa;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TimeSeriesApiIT {
    private static final String FROM = "from=2026-05-20T00:00:00Z";
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    @Autowired private TestRestTemplate http;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;

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
    void countsInclusiveBoundariesAndFillsGapsWithoutCountingOutsideRange() throws Exception {
        ingest("before", 14227, "2026-05-19T23:59:59.999999Z");
        ingest("start", 14227, "2026-05-20T00:00:00Z");
        ingest("second", 14228, "2026-05-20T00:00:59.999999Z");
        ingest("end", 14227, "2026-05-20T00:02:00Z");
        ingest("after", 14227, "2026-05-20T00:02:00.000001Z");
        JsonNode result = series(FROM + "&to=2026-05-20T00:02:00Z&interval=1m");
        assertThat(result.path("totalEvents").asLong()).isEqualTo(3);
        assertThat(result.path("configId").isNull()).isTrue();
        assertThat(result.path("interval").asText()).isEqualTo("1m");
        assertThat(result.path("buckets").size()).isEqualTo(3);
        assertBucket(result, 0, "2026-05-20T00:00:00Z", 2);
        assertBucket(result, 1, "2026-05-20T00:01:00Z", 0);
        assertBucket(result, 2, "2026-05-20T00:02:00Z", 1);
        JsonNode filtered = series("configId=14228&" + FROM + "&to=2026-05-20T00:02:00Z&interval=1m");
        assertThat(filtered.path("totalEvents").asLong()).isEqualTo(1);
        assertThat(filtered.path("configId").asLong()).isEqualTo(14228);
        assertBucket(filtered, 0, "2026-05-20T00:00:00Z", 1);
    }

    @Test
    void clipsPartialEdgeBucketsInsteadOfIncludingWholeBucketHistory() throws Exception {
        ingest("before", 14227, "2026-05-20T00:01:09Z");
        ingest("start", 14227, "2026-05-20T00:01:10Z");
        ingest("end", 14227, "2026-05-20T00:06:20Z");
        ingest("after", 14227, "2026-05-20T00:06:21Z");
        JsonNode result = series("from=2026-05-20T00:01:10Z&to=2026-05-20T00:06:20Z&interval=5m");
        assertThat(result.path("totalEvents").asLong()).isEqualTo(2);
        assertBucket(result, 0, "2026-05-20T00:00:00Z", 1);
        assertBucket(result, 1, "2026-05-20T00:05:00Z", 1);
        assertThat(result.path("buckets").size()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1m", "5m", "1h"})
    void returnsOneZeroBucketForAnEmptySingleInstantRange(String interval) {
        JsonNode result = series(FROM + "&to=2026-05-20T00:00:00Z&interval=" + interval);
        assertThat(result.path("totalEvents").asLong()).isZero();
        assertThat(result.path("buckets").size()).isEqualTo(1);
        assertBucket(result, 0, "2026-05-20T00:00:00Z", 0);
    }

    @Test
    void alignsOffsetsInUtcAndPreservesEmptyHoursAcrossDstChange() throws Exception {
        ingest("offset", 14227, "2026-03-29T03:15:00+02:00");
        JsonNode result = series("from=2026-03-29T00:00:00Z&to=2026-03-29T03:00:00Z&interval=1h");
        assertThat(result.path("buckets").size()).isEqualTo(4);
        assertBucket(result, 1, "2026-03-29T01:00:00Z", 1);
        assertBucket(result, 2, "2026-03-29T02:00:00Z", 0);
    }

    @Test
    void doesNotWidenSubMicrosecondBoundsThroughDriverRounding() throws Exception {
        ingest("before", 14227, "2026-05-20T00:00:00Z");
        ingest("middle", 14227, "2026-05-20T00:00:00.000001Z");
        ingest("after", 14227, "2026-05-20T00:00:00.000002Z");
        JsonNode result = series("from=2026-05-20T00:00:00.0000001Z"
                + "&to=2026-05-20T00:00:00.0000019Z&interval=1m");
        assertThat(result.path("totalEvents").asLong()).isEqualTo(1);
    }

    @Test
    void supportsDatesBeforeEpochWithFloorAlignment() throws Exception {
        ingest("historical", 14227, "1969-12-31T23:59:59Z");
        JsonNode result = series("from=1969-12-31T23:59:59Z&to=1970-01-01T00:00:00Z&interval=5m");
        assertBucket(result, 0, "1969-12-31T23:55:00Z", 1);
        assertBucket(result, 1, "1970-01-01T00:00:00Z", 0);
    }

    @Test
    void supportsExactlyTwoThousandBuckets() {
        JsonNode result = series(FROM + "&to=2026-05-21T09:19:00Z&interval=1m");
        assertThat(result.path("buckets").size()).isEqualTo(2000);
    }

    @Test
    void countsAttackWaveWithoutReadingIndividualEventPayloads() throws Exception {
        List<ObjectNode> wave = new java.util.ArrayList<>();
        for (int index = 0; index < 50; index++) {
            wave.add(event("wave-" + index, 14227, "2026-05-20T00:00:04Z"));
        }
        assertThat(http.postForEntity("/v1/events/ingest", wave, JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        JsonNode result = series(FROM + "&to=2026-05-20T00:02:00Z&interval=1m");
        assertBucket(result, 0, "2026-05-20T00:00:00Z", 50);
        assertBucket(result, 1, "2026-05-20T00:01:00Z", 0);
    }

    @ParameterizedTest
    @MethodSource("invalidQueries")
    void rejectsInvalidQueriesWithProblemDetails(String query) {
        var result = http.getForEntity("/v1/stats/timeseries?" + query, JsonNode.class);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(result.getBody().path("status").asInt()).isEqualTo(400);
        assertThat(result.getBody().path("detail").asText()).isNotBlank();
    }

    static Stream<String> invalidQueries() {
        String range = FROM + "&to=2026-05-20T00:02:00Z";
        return Stream.of(range, range + "&interval=2m", range + "&interval=1M",
                range + "&interval=1m&configId=0", range + "&interval=1m&configId=abc",
                "interval=1m", FROM + "&interval=1m", "to=2026-05-20T00:00:00Z&interval=1m",
                "from=invalid&to=2026-05-20T00:00:00Z&interval=1m",
                "from=2026-05-21T00:00:00Z&to=2026-05-20T00:00:00Z&interval=1m",
                FROM + "&to=2026-05-21T09:20:00Z&interval=1m",
                "from=0000-01-01T00:00:00Z&to=0000-01-01T00:01:00Z&interval=1m");
    }

    @Test
    void documentsTheEndpointAndAllowedIntervals() {
        JsonNode document = http.getForObject("/v3/api-docs", JsonNode.class);
        JsonNode operation = document.path("paths").path("/v1/stats/timeseries").path("get");
        assertThat(operation.path("parameters").size()).isEqualTo(4);
        assertThat(operation.path("parameters").toString()).contains("1m", "5m", "1h");
        assertThat(operation.path("responses").path("200").toString()).contains("TimeSeries");
    }

    private JsonNode series(String query) {
        var response = http.getForEntity("/v1/stats/timeseries?" + query, JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private void assertBucket(JsonNode result, int index, String time, long count) {
        JsonNode bucket = result.path("buckets").get(index);
        assertThat(bucket.path("timestamp").asText()).isEqualTo(time);
        assertThat(bucket.path("count").asLong()).isEqualTo(count);
    }

    private ObjectNode event(String id, long config, String timestamp) throws Exception {
        ObjectNode event = (ObjectNode) mapper.readTree(Files.readString(Path.of("examples/event.json")));
        event.put("eventId", id).put("configId", config).put("timestamp", timestamp);
        return event;
    }

    private void ingest(String id, long config, String timestamp) throws Exception {
        var response = http.postForEntity("/v1/events/ingest", event(id, config, timestamp), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }
}
