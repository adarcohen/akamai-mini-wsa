package com.akamai.miniwsa;

import com.akamai.miniwsa.domain.Action;
import com.akamai.miniwsa.domain.Category;
import com.akamai.miniwsa.domain.SecurityEvent;
import com.akamai.miniwsa.domain.Severity;
import java.time.Instant;

public final class EventFixtures {
    private EventFixtures() {}
    public static SecurityEvent event(String id) {
        return event(id, "203.0.113.42", Instant.parse("2026-05-20T14:32:10Z"), 14227L,
                Severity.CRITICAL, Action.DENY, Category.INJECTION, "/api/v1/login");
    }

    public static SecurityEvent event(String id, String ip, Instant timestamp, long config,
                               Severity severity, Action action, Category category, String path) {
        return new SecurityEvent(id, timestamp, config, "pol_web1", ip, "www.example.com", path,
                "POST", 403, "Mozilla/5.0", new SecurityEvent.Rule("950001", category.name(),
                "Attack detected", severity, category), action,
                new SecurityEvent.GeoLocation("CN", "Beijing"), 1024L, 256L);
    }
}
