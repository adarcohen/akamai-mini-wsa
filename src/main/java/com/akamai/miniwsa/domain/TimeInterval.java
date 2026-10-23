package com.akamai.miniwsa.domain;

import java.util.Arrays;

public enum TimeInterval {
    ONE_MINUTE("1m", 60), FIVE_MINUTES("5m", 300), ONE_HOUR("1h", 3600);

    private final String value;
    private final int seconds;

    TimeInterval(String value, int seconds) {
        this.value = value;
        this.seconds = seconds;
    }

    public String value() {
        return value;
    }

    public int seconds() {
        return seconds;
    }

    public static TimeInterval parse(String value) {
        return Arrays.stream(values()).filter(interval -> interval.value.equals(value)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("interval must be one of: 1m, 5m, 1h"));
    }
}
