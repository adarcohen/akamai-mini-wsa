package com.akamai.miniwsa.api;

import com.akamai.miniwsa.domain.Action;
import com.akamai.miniwsa.domain.Category;
import com.akamai.miniwsa.domain.SamplesPage;
import com.akamai.miniwsa.domain.SamplesQuery;
import com.akamai.miniwsa.domain.SecurityEvent;
import com.akamai.miniwsa.service.IngestionService;
import com.akamai.miniwsa.service.SamplesService;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Events", description = "Security event ingestion and samples")
@RestController
@RequestMapping("/v1/events")
public class EventsController {
    private final IngestionRequestParser parser;
    private final IngestionService ingestion;
    private final SamplesService samples;

    public EventsController(IngestionRequestParser parser, IngestionService ingestion, SamplesService samples) {
        this.parser = parser;
        this.ingestion = ingestion;
        this.samples = samples;
    }

    @Operation(summary = "Retrieve enriched event samples",
            description = "All filters are optional. Results sort by event timestamp descending, "
                    + "then eventId descending. Total counts matching events before pagination.")
    @GetMapping("/samples")
    public SamplesPage samples(
            @Parameter(example = "14227") @RequestParam(required = false) Long configId,
            @Parameter(example = "2026-05-20T00:00:00Z") @RequestParam(required = false) Instant from,
            @Parameter(example = "2026-05-21T00:00:00Z") @RequestParam(required = false) Instant to,
            @RequestParam(required = false) Category category,
            @RequestParam(required = false) Action action,
            @Parameter(schema = @Schema(type = "integer", format = "int32", minimum = "1", maximum = "100")) @RequestParam(defaultValue = "20") int limit,
            @Parameter(schema = @Schema(type = "integer", format = "int32", minimum = "0")) @RequestParam(defaultValue = "0") int offset) {
        return samples.samples(new SamplesQuery(configId, from, to, category, action, limit, offset));
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
