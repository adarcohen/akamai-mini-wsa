package com.akamai.miniwsa.domain;

import java.time.Instant;

public record TimeSeriesQuery(Long configId, Instant from, Instant to, TimeInterval interval) {
    public static final int MAX_BUCKETS = 2000;
    private static final Instant MIN_TIME = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant MAX_TIME = Instant.parse("9999-12-31T23:59:59.999999Z");

    public TimeSeriesQuery {
        if (from == null || to == null || interval == null) {
            throw new IllegalArgumentException("from, to and interval are required");
        }
        if (configId != null && configId <= 0) {
            throw new IllegalArgumentException("configId must be positive");
        }
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from must be before or equal to to");
        }
        if (from.isBefore(MIN_TIME) || to.isAfter(MAX_TIME)) {
            throw new IllegalArgumentException("Time-series timestamps must be within years 0001 through 9999");
        }
        long buckets = Math.floorDiv(to.getEpochSecond(), interval.seconds())
                - Math.floorDiv(from.getEpochSecond(), interval.seconds()) + 1;
        if (buckets > MAX_BUCKETS) {
            throw new IllegalArgumentException("Time range exceeds " + MAX_BUCKETS
                    + " buckets; choose a larger interval or shorter range");
        }
    }

    public Instant firstBucket() {
        return Instant.ofEpochSecond(Math.floorDiv(from.getEpochSecond(), interval.seconds()) * interval.seconds());
    }
}
