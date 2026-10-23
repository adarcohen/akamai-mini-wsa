package com.akamai.miniwsa.api;

import com.akamai.miniwsa.domain.StatsSummary;
import com.akamai.miniwsa.domain.SummaryQuery;
import com.akamai.miniwsa.service.StatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Statistics", description = "Aggregated security event statistics")
@RestController
@RequestMapping("/v1/stats")
public class StatsController {
    private final StatsService stats;

    public StatsController(StatsService stats) {
        this.stats = stats;
    }

    @Operation(summary = "Summarize security events",
            description = "Filters by event timestamp with inclusive from/to bounds. "
                    + "Omit configId to aggregate across all configurations.")
    @GetMapping("/summary")
    public StatsSummary summary(
            @Parameter(example = "14227") @RequestParam(required = false) Long configId,
            @Parameter(example = "2026-05-20T00:00:00Z") @RequestParam Instant from,
            @Parameter(example = "2026-05-21T00:00:00Z") @RequestParam Instant to) {
        return stats.summary(new SummaryQuery(configId, from, to));
    }
}
