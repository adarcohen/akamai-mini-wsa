package com.akamai.miniwsa.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(EventsApiTest.ClockConfiguration.class)
class EventsApiTest {
    private static final Instant NOW = Instant.parse("2026-05-20T15:00:00Z");

    @Autowired private TestRestTemplate http;
    @Autowired private ObjectMapper mapper;

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
    }

    @Test
    void acceptsBatchAndPreservesInputOrder() throws Exception {
        var response = ingest(List.of(event("first"), event("second")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().path("accepted").asInt()).isEqualTo(2);
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
