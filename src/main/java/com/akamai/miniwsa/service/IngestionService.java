package com.akamai.miniwsa.service;

import com.akamai.miniwsa.domain.ReceivedEvent;
import com.akamai.miniwsa.domain.SecurityEvent;
import com.akamai.miniwsa.repository.EventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IngestionService {
    private final EventRepository repository;
    private final Clock clock;

    public IngestionService(EventRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public List<ReceivedEvent> ingest(List<SecurityEvent> events) {
        Instant receivedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        List<ReceivedEvent> received = events.stream()
                .map(event -> new ReceivedEvent(event, receivedAt))
                .toList();
        repository.saveAll(received);
        return received;
    }
}
