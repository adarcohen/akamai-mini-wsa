package com.akamai.miniwsa.domain;

import java.time.Instant;

public record TimeSeriesQuery(Long configId, Instant from, Instant to, TimeInterval interval) {
    public static final int MAX_BUCKETS = 2000;

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
        EventTime.requireSupported(from, "from");
        EventTime.requireSupported(to, "to");
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
