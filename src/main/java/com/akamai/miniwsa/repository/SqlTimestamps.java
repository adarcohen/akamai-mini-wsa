package com.akamai.miniwsa.repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/** Round inclusive bounds inward to PostgreSQL microseconds, never widening a query. */
final class SqlTimestamps {
    private SqlTimestamps() {}

    static OffsetDateTime lowerBound(Instant value) {
        Instant rounded = value.truncatedTo(ChronoUnit.MICROS);
        if (rounded.isBefore(value)) {
            rounded = rounded.plus(1, ChronoUnit.MICROS);
        }
        return rounded.atOffset(ZoneOffset.UTC);
    }

    static OffsetDateTime upperBound(Instant value) {
        return value.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }
}
