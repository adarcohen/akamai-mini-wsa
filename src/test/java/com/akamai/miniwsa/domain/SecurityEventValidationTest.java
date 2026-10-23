package com.akamai.miniwsa.domain;

import static org.assertj.core.api.Assertions.assertThat;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class SecurityEventValidationTest {
    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @Test
    void acceptsACompleteValidEvent() {
        assertThat(validator.validate(validEvent(validRule(), validLocation()))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "eventId", "timestamp", "configId", "policyId", "clientIp", "hostname",
            "path", "method", "statusCode", "userAgent", "rule", "action",
            "geoLocation", "requestSize", "responseSize"
    })
    void rejectsMissingRequiredFields(String field) {
        assertThat(validator.validateValue(SecurityEvent.class, field, null)).isNotEmpty();
    }

    static Stream<Arguments> invalidValues() {
        return Stream.of(
                Arguments.of("eventId", " "),
                Arguments.of("path", ""),
                Arguments.of("configId", 0L),
                Arguments.of("configId", -1L),
                Arguments.of("statusCode", 99),
                Arguments.of("statusCode", 600),
                Arguments.of("requestSize", -1L),
                Arguments.of("responseSize", -1L),
                Arguments.of("eventId", "a".repeat(129)));
    }

    @ParameterizedTest
    @MethodSource("invalidValues")
    void rejectsInvalidFieldValues(String field, Object value) {
        assertThat(validator.validateValue(SecurityEvent.class, field, value)).isNotEmpty();
    }

    @Test
    void acceptsZeroByteRequestAndResponseSizes() {
        assertThat(validator.validateValue(SecurityEvent.class, "requestSize", 0L)).isEmpty();
        assertThat(validator.validateValue(SecurityEvent.class, "responseSize", 0L)).isEmpty();
    }

    @Test
    void validatesNestedRuleFields() {
        var rule = new SecurityEvent.Rule("950001", "SQL_INJECTION", "Attack detected", null, null);
        var violations = validator.validate(validEvent(rule, validLocation()));

        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactlyInAnyOrder("rule.severity", "rule.category");
    }

    @Test
    void validatesNestedLocationFields() {
        var location = new SecurityEvent.GeoLocation("USA", " ");
        var violations = validator.validate(validEvent(validRule(), location));

        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactlyInAnyOrder("geoLocation.country", "geoLocation.city");
    }

    private static SecurityEvent validEvent(SecurityEvent.Rule rule, SecurityEvent.GeoLocation location) {
        return new SecurityEvent(
                "evt-00132",
                Instant.parse("2026-05-20T14:32:10Z"),
                14227L,
                "pol_web1",
                "203.0.113.42",
                "www.example.com",
                "/api/v1/login",
                "POST",
                403,
                "Mozilla/5.0",
                rule,
                Action.DENY,
                location,
                1024L,
                256L);
    }

    private static SecurityEvent.Rule validRule() {
        return new SecurityEvent.Rule(
                "950001", "SQL_INJECTION", "Attack detected", Severity.CRITICAL, Category.INJECTION);
    }

    private static SecurityEvent.GeoLocation validLocation() {
        return new SecurityEvent.GeoLocation("CN", "Beijing");
    }
}
