package com.akamai.miniwsa.domain;

import java.time.Instant;
import java.util.List;

public record TimeSeries(
        Long configId, TimeRange timeRange, String interval, long totalEvents, List<Bucket> buckets) {
    public record TimeRange(Instant from, Instant to) {}
    public record Bucket(Instant timestamp, long count) {}
}
