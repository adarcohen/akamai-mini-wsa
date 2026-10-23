package com.akamai.miniwsa.api;

import com.akamai.miniwsa.domain.StatsSummary;
import com.akamai.miniwsa.domain.SummaryQuery;
import com.akamai.miniwsa.domain.TimeInterval;
import com.akamai.miniwsa.domain.TimeSeries;
import com.akamai.miniwsa.domain.TimeSeriesQuery;
import com.akamai.miniwsa.service.StatsService;
import com.akamai.miniwsa.service.TimeSeriesService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
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

    private final TimeSeriesService timeSeries;

    public StatsController(StatsService stats, TimeSeriesService timeSeries) {
        this.stats = stats;
        this.timeSeries = timeSeries;
    }

    @Operation(summary = "Count security events over time",
            description = "UTC-aligned buckets in ascending order, including empty buckets. "
                    + "Event timestamps use inclusive from/to bounds. Edge buckets may be partial. "
                    + "At most 2000 buckets; timestamps must be within years 0001 through 9999.")
    @GetMapping("/timeseries")
    public TimeSeries timeseries(
            @Parameter(example = "14227") @RequestParam(required = false) Long configId,
            @Parameter(example = "2026-05-20T00:00:00Z") @RequestParam Instant from,
            @Parameter(example = "2026-05-20T05:00:00Z") @RequestParam Instant to,
            @Parameter(schema = @Schema(type = "string", allowableValues = {"1m", "5m", "1h"}))
            @RequestParam String interval) {
        return timeSeries.series(new TimeSeriesQuery(configId, from, to, TimeInterval.parse(interval)));
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
