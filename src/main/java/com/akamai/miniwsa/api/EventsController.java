package com.akamai.miniwsa.api;

import com.akamai.miniwsa.service.IngestionService;
import com.fasterxml.jackson.databind.JsonNode;
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
    private final IngestionService ingestion;

    public EventsController(IngestionRequestParser parser, IngestionService ingestion) {
        this.parser = parser;
        this.ingestion = ingestion;
    }

    @PostMapping("/ingest")
    @ResponseStatus(HttpStatus.CREATED)
    public IngestionResponse ingest(@RequestBody JsonNode body) {
        var received = ingestion.ingest(parser.parse(body));
        return new IngestionResponse(received.size(), received);
    }
}
