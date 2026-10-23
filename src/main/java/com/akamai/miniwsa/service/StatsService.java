package com.akamai.miniwsa.service;

import com.akamai.miniwsa.domain.StatsSummary.TimeRange;
import com.akamai.miniwsa.domain.StatsSummary;
import com.akamai.miniwsa.domain.SummaryQuery;
import com.akamai.miniwsa.repository.StatsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StatsService {
    private final StatsRepository repository;

    public StatsService(StatsRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public StatsSummary summary(SummaryQuery query) {
        var categories = repository.categoryStats(query);
        var actions = repository.actionStats(query);
        long totalEvents = actions.values().stream().mapToLong(Long::longValue).sum();
        return new StatsSummary(query.configId(), new TimeRange(query.from(), query.to()),
                totalEvents, categories, actions, repository.topAttackers(query), repository.topTargetedPaths(query));
    }
}
