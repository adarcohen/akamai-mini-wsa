package com.akamai.miniwsa.api;

import com.akamai.miniwsa.domain.ReceivedEvent;
import java.util.List;

public record IngestionResponse(int accepted, List<ReceivedEvent> events) {}
