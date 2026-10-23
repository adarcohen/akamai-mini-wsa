package com.akamai.miniwsa.domain;

import java.time.Instant;
import org.springframework.stereotype.Component;

/** Calculates enrichment from explicit inputs; does not query storage or read the clock. */
@Component
public class EnrichmentPolicy {
    private static final int MAX_SCORE = 100;
    private static final int SENSITIVE_PATH_BONUS = 15;
    private static final int REPEAT_OFFENDER_BONUS = 15;
    private static final int PRIOR_EVENTS_THRESHOLD = 5;

    public EnrichedEvent enrich(SecurityEvent event, long priorRecentEvents, Instant receivedAt) {
        int score = event.rule().severity().baseScore() + event.action().scoreBonus();
        if (event.path().contains("/admin") || event.path().contains("/login")) {
            score += SENSITIVE_PATH_BONUS;
        }
        // Include the current event: five prior arrivals make this the sixth.
        if (priorRecentEvents >= PRIOR_EVENTS_THRESHOLD) {
            score += REPEAT_OFFENDER_BONUS;
        }
        return new EnrichedEvent(event, event.rule().category().attackType(),
                Math.min(MAX_SCORE, score), receivedAt);
    }
}
