package com.akamai.miniwsa.domain;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.time.Instant;

public record ReceivedEvent(@JsonUnwrapped SecurityEvent event, Instant receivedAt) {}
