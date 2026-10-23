package com.akamai.miniwsa.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Shared timestamp range accepted by ingestion and analytics APIs. */
public final class EventTime {
    private static final Instant MIN = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant MAX = Instant.parse("9999-12-31T23:59:59.999999Z");

    private EventTime() {}

    public static boolean isSupported(Instant value) {
        return value == null || (!value.isBefore(MIN) && !value.isAfter(MAX));
    }

    /** Ingested timestamps must survive TIMESTAMPTZ storage without rounding. */
    public static boolean isStorable(Instant value) {
        return isSupported(value) && (value == null || value.equals(value.truncatedTo(ChronoUnit.MICROS)));
    }

    public static void requireSupported(Instant value, String field) {
        if (!isSupported(value)) {
            throw new IllegalArgumentException(field + " must be within years 0001 through 9999");
        }
    }
}
