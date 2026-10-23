package com.akamai.miniwsa.api;

import com.akamai.miniwsa.api.IngestionResponse.ReceivedEvent;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/events")
public class EventsController {
    private final IngestionRequestParser parser;
    private final Clock clock;

    public EventsController(IngestionRequestParser parser, Clock clock) {
        this.parser = parser;
        this.clock = clock;
    }

    @PostMapping("/ingest")
    @ResponseStatus(HttpStatus.CREATED)
    public IngestionResponse ingest(@RequestBody JsonNode body) {
        var events = parser.parse(body);
        Instant receivedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var received = events.stream()
                .map(event -> new ReceivedEvent(event, receivedAt))
                .toList();
        return new IngestionResponse(received.size(), received);
    }
}
