package com.akamai.miniwsa.service;

import com.akamai.miniwsa.domain.TimeSeries.Bucket;
import com.akamai.miniwsa.domain.TimeSeries.TimeRange;
import com.akamai.miniwsa.domain.TimeSeries;
import com.akamai.miniwsa.domain.TimeSeriesQuery;
import com.akamai.miniwsa.repository.TimeSeriesRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TimeSeriesService {
    private final TimeSeriesRepository repository;

    public TimeSeriesService(TimeSeriesRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public TimeSeries series(TimeSeriesQuery query) {
        // One aggregate query supplies both bucket counts and the total from one database snapshot.
        Map<Instant, Long> counts = repository.counts(query);
        List<Bucket> buckets = new ArrayList<>();
        for (Instant start = query.firstBucket(); !start.isAfter(query.to());
                start = start.plusSeconds(query.interval().seconds())) {
            buckets.add(new Bucket(start, counts.getOrDefault(start, 0L)));
        }
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        return new TimeSeries(query.configId(), new TimeRange(query.from(), query.to()),
                query.interval().value(), total, List.copyOf(buckets));
    }
}
