package com.akamai.miniwsa.domain;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.time.Instant;

public record EnrichedEvent(
        @JsonUnwrapped SecurityEvent event,
        String attackType,
        int threatScore,
        Instant receivedAt) {}
