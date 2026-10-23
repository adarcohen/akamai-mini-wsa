package com.akamai.miniwsa.service;

import com.akamai.miniwsa.domain.EnrichedEvent;
import com.akamai.miniwsa.domain.SamplesPage;
import com.akamai.miniwsa.domain.SamplesQuery;
import com.akamai.miniwsa.repository.SamplesRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SamplesService {
    private final SamplesRepository repository;

    public SamplesService(SamplesRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SamplesPage samples(SamplesQuery query) {
        long total = repository.count(query);
        List<EnrichedEvent> events = query.offset() >= total ? List.of() : repository.find(query);
        return new SamplesPage(total, query.limit(), query.offset(), events);
    }
}
