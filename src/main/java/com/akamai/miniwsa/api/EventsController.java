package com.akamai.miniwsa.api;

import com.akamai.miniwsa.service.IngestionService;
import com.akamai.miniwsa.domain.SecurityEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Events", description = "Security event ingestion")
@RestController
@RequestMapping("/v1/events")
public class EventsController {
    private final IngestionRequestParser parser;
    private final IngestionService ingestion;

    public EventsController(IngestionRequestParser parser, IngestionService ingestion) {
        this.parser = parser;
        this.ingestion = ingestion;
    }

    @Operation(
            summary = "Ingest security events",
            description = "Accepts one event or a batch of 1-1000 events. The entire request is stored "
                    + "in one transaction. Change eventId before repeating a successful example.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(oneOf = {SecurityEvent.class, SecurityEvent[].class}),
                            examples = {
                                    @ExampleObject(name = "Single event", value = IngestionExamples.SINGLE_EVENT),
                                    @ExampleObject(name = "Batch of events", value = IngestionExamples.BATCH)
                            })))
    @PostMapping("/ingest")
    @ResponseStatus(HttpStatus.CREATED)
    public IngestionResponse ingest(@RequestBody JsonNode body) {
        var received = ingestion.ingest(parser.parse(body));
        return new IngestionResponse(received.size(), received);
    }
}
