package com.akamai.miniwsa.domain;

import java.time.Instant;

public record SummaryQuery(Long configId, Instant from, Instant to) {
    public SummaryQuery {
        if (from == null || to == null) {
            throw new IllegalArgumentException("from and to are required");
        }
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from must be before or equal to to");
        }
        if (configId != null && configId <= 0) {
            throw new IllegalArgumentException("configId must be positive");
        }
    }
}
