package com.akamai.miniwsa.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class EventRepositoryLockTest {
    @Test
    void acquiresDistinctDatabaseKeysSequentiallyInSignedNumericOrder() {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                .thenReturn(List.of(7L, -4L, 7L, 2L));
        List<Long> acquired = new ArrayList<>();
        when(jdbc.queryForList(eq("SELECT pg_advisory_xact_lock(:key)"), any(MapSqlParameterSource.class)))
                .thenAnswer(call -> {
                    MapSqlParameterSource parameters = call.getArgument(1);
                    acquired.add((Long) parameters.getValue("key"));
                    return List.of();
                });
        new EventRepository(jdbc, new ObjectMapper()).lockClients(List.of("203.0.113.1", "203.0.113.2"));
        assertThat(acquired).containsExactly(-4L, 2L, 7L);
    }
}
