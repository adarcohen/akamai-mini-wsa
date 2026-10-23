package com.akamai.miniwsa.repository;

import com.akamai.miniwsa.domain.EnrichedEvent;
import com.akamai.miniwsa.domain.SamplesQuery;
import com.akamai.miniwsa.domain.SecurityEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SamplesRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public SamplesRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public long count(SamplesQuery query) {
        Filter filter = filter(query);
        return jdbc.queryForObject("SELECT count(*) FROM security_events" + filter.sql(),
                filter.parameters(), Long.class);
    }

    public List<EnrichedEvent> find(SamplesQuery query) {
        Filter filter = filter(query);
        filter.parameters().addValue("limit", query.limit()).addValue("offset", query.offset());
        return jdbc.query("""
                SELECT payload, attack_type, threat_score, received_at FROM security_events
                """ + filter.sql() + " ORDER BY event_time DESC, event_id DESC LIMIT :limit OFFSET :offset",
                filter.parameters(), this::readEvent);
    }

    private EnrichedEvent readEvent(ResultSet row, int rowNumber) throws SQLException {
        try {
            SecurityEvent event = mapper.readValue(row.getString("payload"), SecurityEvent.class);
            return new EnrichedEvent(event, row.getString("attack_type"), row.getInt("threat_score"),
                    row.getObject("received_at", OffsetDateTime.class).toInstant());
        } catch (JsonProcessingException exception) {
            throw new DataRetrievalFailureException("Stored event payload cannot be read", exception);
        }
    }

    private Filter filter(SamplesQuery query) {
        StringBuilder sql = new StringBuilder(" WHERE 1=1");
        MapSqlParameterSource parameters = new MapSqlParameterSource();
        if (query.configId() != null) {
            sql.append(" AND config_id = :configId");
            parameters.addValue("configId", query.configId());
        }
        if (query.from() != null) {
            sql.append(" AND event_time >= :from");
            parameters.addValue("from", SqlTimestamps.lowerBound(query.from()));
        }
        if (query.to() != null) {
            sql.append(" AND event_time <= :to");
            parameters.addValue("to", SqlTimestamps.upperBound(query.to()));
        }
        if (query.category() != null) {
            sql.append(" AND category = :category");
            parameters.addValue("category", query.category().name());
        }
        if (query.action() != null) {
            sql.append(" AND action = :action");
            parameters.addValue("action", query.action().name());
        }
        return new Filter(sql.toString(), parameters);
    }

    private record Filter(String sql, MapSqlParameterSource parameters) {}
}
