package com.akamai.miniwsa.api;

import com.akamai.miniwsa.domain.SecurityEvent;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.time.Instant;
import java.util.List;

public record IngestionResponse(int accepted, List<ReceivedEvent> events) {

    public record ReceivedEvent(@JsonUnwrapped SecurityEvent event, Instant receivedAt) {}
}
