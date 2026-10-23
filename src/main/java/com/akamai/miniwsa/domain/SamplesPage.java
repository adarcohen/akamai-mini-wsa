package com.akamai.miniwsa.domain;

import java.util.List;

public record SamplesPage(long total, int limit, int offset, List<EnrichedEvent> events) {}
