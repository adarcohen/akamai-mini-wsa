package com.akamai.miniwsa.service;

import com.akamai.miniwsa.domain.ClientIp;
import com.akamai.miniwsa.domain.EnrichedEvent;
import com.akamai.miniwsa.domain.EnrichmentPolicy;
import com.akamai.miniwsa.domain.SecurityEvent;
import com.akamai.miniwsa.repository.EventRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IngestionService {
    private static final Duration REPEAT_WINDOW = Duration.ofMinutes(10);

    private final EventRepository repository;
    private final EnrichmentPolicy enrichment;
    private final Clock clock;

    public IngestionService(EventRepository repository, EnrichmentPolicy enrichment, Clock clock) {
        this.repository = repository;
        this.enrichment = enrichment;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<EnrichedEvent> ingest(List<SecurityEvent> events) {
        Map<String, String> canonicalIps = new HashMap<>();
        events.forEach(event -> canonicalIps.computeIfAbsent(event.clientIp(), ClientIp::canonical));
        List<String> clients = canonicalIps.values().stream().distinct().toList();

        repository.lockClients(clients);
        Instant receivedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Map<String, Long> recentCounts = repository.recentCounts(
                clients, receivedAt.minus(REPEAT_WINDOW), receivedAt);

        List<EnrichedEvent> enriched = new ArrayList<>(events.size());
        for (SecurityEvent event : events) {
            String clientIp = canonicalIps.get(event.clientIp());
            long priorCount = recentCounts.get(clientIp);
            enriched.add(enrichment.enrich(event, priorCount, receivedAt));
            recentCounts.put(clientIp, priorCount + 1);
        }
        repository.saveAll(enriched);
        return List.copyOf(enriched);
    }
}
