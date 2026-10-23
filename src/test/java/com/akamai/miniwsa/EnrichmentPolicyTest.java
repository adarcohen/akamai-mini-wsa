package com.akamai.miniwsa;

import static org.assertj.core.api.Assertions.assertThat;
import com.akamai.miniwsa.domain.*;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class EnrichmentPolicyTest {
    private final EnrichmentPolicy policy = new EnrichmentPolicy();
    private static final Instant NOW = Instant.parse("2026-05-20T15:00:00Z");

    static Stream<Arguments> scoringCases() {
        return Stream.of(Severity.values()).flatMap(severity -> Stream.of(Action.values()).flatMap(action ->
                Stream.of("/public", "/admin", "/login/admin").flatMap(path -> Stream.of(4L, 5L).map(prior ->
                        Arguments.of(severity, action, path, prior)))));
    }

    @ParameterizedTest
    @MethodSource("scoringCases")
    void computesScoreForEverySeverityActionAndThreshold(Severity severity, Action action, String path, long prior) {
        SecurityEvent event = EventFixtures.event("evt", "203.0.113.1", NOW, 1L, severity, action, Category.BOT, path);
        int severityScore = switch (severity) { case CRITICAL -> 40; case HIGH -> 30; case MEDIUM -> 20; case LOW -> 10; };
        int actionScore = switch (action) { case DENY -> 20; case ALERT -> 10; case MONITOR -> 0; };
        int expected = severityScore + actionScore + (path.equals("/public") ? 0 : 15) + (prior >= 5 ? 15 : 0);
        EnrichedEvent result = policy.enrich(event, prior, NOW);
        assertThat(result.threatScore()).isEqualTo(expected).isBetween(0, 100);
        assertThat(result.receivedAt()).isEqualTo(NOW);
        assertThat(result.event()).isSameAs(event);
    }

    @ParameterizedTest
    @CsvSource({
        "INJECTION,SQL/Command Injection", "XSS,Cross-Site Scripting", "PROTOCOL_VIOLATION,Protocol Anomaly",
        "DATA_LEAKAGE,Data Exfiltration", "BOT,Bot Activity", "DOS,Denial of Service", "RATE_LIMIT,Rate Limiting"
    })
    void mapsEveryCategory(Category category, String expected) {
        var event = EventFixtures.event("evt", "203.0.113.1", NOW, 1L, Severity.LOW, Action.MONITOR, category, "/");
        assertThat(policy.enrich(event, 0, NOW).attackType()).isEqualTo(expected);
    }

    @Test
    void awardsRepeatBonusOnceEvenForVeryLargeCounts() {
        assertThat(policy.enrich(EventFixtures.event("evt"), Long.MAX_VALUE, NOW).threatScore()).isEqualTo(90);
    }
}
