package com.akamai.miniwsa.domain;

import java.time.Instant;

public record SamplesQuery(
        Long configId, Instant from, Instant to, Category category, Action action, int limit, int offset) {
    public SamplesQuery {
        if (configId != null && configId <= 0) {
            throw new IllegalArgumentException("configId must be positive");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("from must be before or equal to to");
        }
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be nonnegative");
        }
    }
}
