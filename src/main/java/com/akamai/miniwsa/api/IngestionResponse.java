package com.akamai.miniwsa.api;

import com.akamai.miniwsa.domain.EnrichedEvent;
import java.util.List;

public record IngestionResponse(int accepted, List<EnrichedEvent> events) {}
