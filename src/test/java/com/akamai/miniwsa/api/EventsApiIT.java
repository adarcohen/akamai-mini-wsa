package com.akamai.miniwsa.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.OffsetDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(EventsApiIT.ClockConfiguration.class)
class EventsApiIT {
    private static final Instant NOW = Instant.parse("2026-05-20T15:00:00Z");

    @Autowired private TestRestTemplate http;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

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
    void acceptsSingleEventAndAssignsServerTimestamp() throws Exception {
        ObjectNode event = event("one");
        var response = ingest(event);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().path("accepted").asInt()).isEqualTo(1);
        JsonNode received = response.getBody().path("events").get(0);
        assertThat(Instant.parse(received.path("receivedAt").asText())).isEqualTo(NOW);
        ObjectNode original = received.deepCopy();
        original.remove("receivedAt");
        assertThat(original).isEqualTo(event);
        assertThat(storedCount()).isEqualTo(1);
        String payload = jdbc.queryForObject("SELECT payload::text FROM security_events", String.class);
        assertThat(mapper.readTree(payload)).isEqualTo(event);
        OffsetDateTime receivedAt = jdbc.queryForObject(
                "SELECT received_at FROM security_events", OffsetDateTime.class);
        assertThat(receivedAt.toInstant()).isEqualTo(NOW);
    }

    @Test
    void acceptsBatchAndPreservesInputOrder() throws Exception {
        var response = ingest(List.of(event("first"), event("second")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().path("accepted").asInt()).isEqualTo(2);
        assertThat(storedCount()).isEqualTo(2);
        assertThat(response.getBody().path("events").get(0).path("eventId").asText()).isEqualTo("first");
        assertThat(response.getBody().path("events").get(1).path("eventId").asText()).isEqualTo("second");
    }

    @Test
    void rejectsWholeBatchAndReportsIndexedValidationErrors() throws Exception {
        ObjectNode invalid = event("bad");
        invalid.remove("eventId");
        invalid.put("requestSize", -1);
        ((ObjectNode) invalid.path("rule")).remove("severity");
        var response = ingest(List.of(event("valid"), invalid));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody().path("errors").toString())
                .contains("events[1].eventId", "events[1].requestSize", "events[1].rule.severity");
        assertThat(response.getBody().has("accepted")).isFalse();
        assertThat(storedCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"timestamp", "action", "configId"})
    void rejectsInvalidFieldTypesAndValues(String field) throws Exception {
        ObjectNode event = event("invalid");
        event.put(field, "invalid");
        assertThat(ingest(event).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @ParameterizedTest
    @ValueSource(strings = {"category", "severity"})
    void rejectsUnknownNestedEnums(String field) throws Exception {
        ObjectNode event = event("invalid");
        ((ObjectNode) event.path("rule")).put(field, "UNKNOWN");
        var response = ingest(event);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().path("errors").toString()).contains("rule." + field);
    }

    @Test
    void rejectsCoercionsNumericTimestampAndNumericEnum() throws Exception {
        ObjectNode numericTime = event("numeric-time");
        numericTime.put("timestamp", 1779287530);
        ObjectNode numericEnum = event("numeric-enum");
        numericEnum.put("action", 0);
        ObjectNode fractionalId = event("fractional-id");
        fractionalId.put("configId", 1.5);
        ObjectNode stringId = event("string-id");
        stringId.put("configId", "14227");
        for (ObjectNode invalid : List.of(numericTime, numericEnum, fractionalId, stringId)) {
            assertThat(ingest(invalid).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"unexpected", "receivedAt", "threatScore", "attackType"})
    void rejectsUnknownOrServerOwnedFields(String field) throws Exception {
        ObjectNode event = event("invalid");
        event.put(field, "client-supplied");
        assertThat(ingest(event).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsDuplicateIdsWithinBatch() throws Exception {
        assertThat(ingest(List.of(event("duplicate"), event("duplicate"))).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsOversizedBatch() throws Exception {
        var batch = mapper.createArrayNode();
        for (int index = 0; index < 1001; index++) {
            batch.add(event("event-" + index));
        }
        assertThat(ingest(batch).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "[null]", "[1]", "null", "1", "\"text\"", "{broken"})
    void rejectsInvalidBodyShapesAndMalformedJson(String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var response = http.postForEntity("/v1/events/ingest", new HttpEntity<>(json, headers), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsStoredDuplicateWithoutOverwritingIt() throws Exception {
        assertThat(ingest(event("existing")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ObjectNode changed = event("existing");
        changed.put("path", "/changed");
        var response = ingest(changed);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(storedCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT path FROM security_events", String.class))
                .isEqualTo("/api/v1/login");
    }

    @Test
    void rollsBackWholeBatchWhenOneEventAlreadyExists() throws Exception {
        ingest(event("existing"));
        var response = ingest(List.of(event("new-one"), event("new-two"), event("existing")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(storedCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM security_events WHERE event_id IN ('new-one', 'new-two')", Long.class)).isZero();
    }

    @Test
    void concurrentDuplicateRequestsStoreExactlyOneEvent() throws Exception {
        ObjectNode event = event("concurrent");
        var first = CompletableFuture.supplyAsync(() -> ingest(event).getStatusCode().value());
        var second = CompletableFuture.supplyAsync(() -> ingest(event).getStatusCode().value());

        assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(201, 409);
        assertThat(storedCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost", "example.com", "127.1", "1.2.3.256", "01.2.3.4", ":::", "fe80::1%en0"})
    void rejectsInvalidIpLiteralsBeforeWriting(String ip) throws Exception {
        ObjectNode event = event("invalid-ip");
        event.put("clientIp", ip);
        var response = ingest(event);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().path("errors").toString()).contains("events[0].clientIp");
        assertThat(storedCount()).isZero();
    }

    @Test
    void storesIpv6AsACanonicalAddressAndPreservesOriginalPayload() throws Exception {
        ObjectNode event = event("ipv6");
        event.put("clientIp", "2001:0db8:0:0:0:0:0:1");
        assertThat(ingest(event).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(jdbc.queryForObject("SELECT host(client_ip) FROM security_events", String.class))
                .isEqualTo("2001:db8::1");
        String payload = jdbc.queryForObject("SELECT payload::text FROM security_events", String.class);
        assertThat(mapper.readTree(payload).path("clientIp").asText()).isEqualTo("2001:0db8:0:0:0:0:0:1");
    }

    @Test
    void treatsSqlCharactersAsData() throws Exception {
        String id = "evt-'; DROP TABLE security_events; --";
        ObjectNode event = event(id);
        assertThat(ingest(event).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(jdbc.queryForObject("SELECT event_id FROM security_events", String.class)).isEqualTo(id);
        assertThat(storedCount()).isEqualTo(1);
    }

    @Test
    void servesSwaggerUiAtDocs() {
        var docs = http.getForEntity("/docs", String.class);
        assertThat(docs.getStatusCode().value()).isIn(200, 302);
        var ui = http.getForEntity("/swagger-ui/index.html", String.class);
        assertThat(ui.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ui.getBody()).contains("swagger-ui-bundle.js");
    }

    @Test
    void documentsSingleAndBatchRequestsWithUsableExamples() throws Exception {
        var response = http.getForEntity("/v3/api-docs", JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode document = response.getBody();
        assertThat(document.path("info").path("title").asText()).isEqualTo("Mini WSA API");
        JsonNode operation = document.path("paths").path("/v1/events/ingest").path("post");
        JsonNode body = operation.path("requestBody").path("content").path("application/json");
        JsonNode alternatives = body.path("schema").path("oneOf");
        assertThat(alternatives.size()).isEqualTo(2);
        assertThat(alternatives.toString()).contains("SecurityEvent", "array");
        assertThat(operation.path("responses").has("201")).isTrue();
        assertThat(operation.path("responses").has("400")).isTrue();
        assertThat(operation.path("responses").has("409")).isTrue();
        assertThat(operation.path("responses").has("503")).isTrue();
        assertThat(operation.path("responses").path("201").toString()).contains("IngestionResponse");
        assertThat(operation.path("responses").path("400").toString()).contains("ProblemDetail");
        JsonNode single = body.path("examples").path("Single event").path("value");
        JsonNode batch = body.path("examples").path("Batch of events").path("value");
        assertThat(ingest(single).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(ingest(single).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ((ObjectNode) batch.get(0)).put("eventId", "evt-docs-batch-001");
        assertThat(ingest(batch).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private long storedCount() {
        return jdbc.queryForObject("SELECT count(*) FROM security_events", Long.class);
    }

    private ResponseEntity<JsonNode> ingest(Object body) {
        return http.postForEntity("/v1/events/ingest", body, JsonNode.class);
    }

    private ObjectNode event(String id) throws Exception {
        ObjectNode event = (ObjectNode) mapper.readTree("""
                {
                  "eventId": "placeholder",
                  "timestamp": "2026-05-20T14:32:10Z",
                  "configId": 14227,
                  "policyId": "pol_web1",
                  "clientIp": "203.0.113.42",
                  "hostname": "www.example.com",
                  "path": "/api/v1/login",
                  "method": "POST",
                  "statusCode": 403,
                  "userAgent": "Mozilla/5.0",
                  "rule": {
                    "id": "950001", "name": "SQL_INJECTION", "message": "Attack detected",
                    "severity": "CRITICAL", "category": "INJECTION"
                  },
                  "action": "DENY",
                  "geoLocation": {"country": "CN", "city": "Beijing"},
                  "requestSize": 1024,
                  "responseSize": 256
                }
                """);
        event.put("eventId", id);
        return event;
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
