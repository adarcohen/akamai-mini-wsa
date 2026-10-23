package com.akamai.miniwsa.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record StatsSummary(
        Long configId,
        TimeRange timeRange,
        long totalEvents,
        Map<Category, CategoryStats> byCategory,
        Map<Action, Long> byAction,
        List<Attacker> topAttackers,
        List<TargetedPath> topTargetedPaths) {

    public record TimeRange(Instant from, Instant to) {}
    public record CategoryStats(long count, BigDecimal avgThreatScore) {}
    public record Attacker(String clientIp, long count, BigDecimal avgThreatScore) {}
    public record TargetedPath(String path, long count) {}
}
