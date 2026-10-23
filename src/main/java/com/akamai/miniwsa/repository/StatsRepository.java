package com.akamai.miniwsa.repository;

import com.akamai.miniwsa.domain.Action;
import com.akamai.miniwsa.domain.Category;
import com.akamai.miniwsa.domain.StatsSummary.Attacker;
import com.akamai.miniwsa.domain.StatsSummary.CategoryStats;
import com.akamai.miniwsa.domain.StatsSummary.TargetedPath;
import com.akamai.miniwsa.domain.SummaryQuery;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class StatsRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public StatsRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<Category, CategoryStats> categoryStats(SummaryQuery query) {
        Filter filter = filter(query);
        Map<Category, CategoryStats> result = new EnumMap<>(Category.class);
        jdbc.query("""
                SELECT category, count(*) AS event_count, round(avg(threat_score), 1) AS avg_score
                FROM security_events
                """ + filter.sql() + " GROUP BY category", filter.parameters(), rs -> {
                    result.put(Category.valueOf(rs.getString("category")),
                            new CategoryStats(rs.getLong("event_count"), rs.getBigDecimal("avg_score")));
                });
        return result;
    }

    public Map<Action, Long> actionStats(SummaryQuery query) {
        Filter filter = filter(query);
        Map<Action, Long> result = new EnumMap<>(Action.class);
        jdbc.query("SELECT action, count(*) AS event_count FROM security_events"
                + filter.sql() + " GROUP BY action", filter.parameters(), rs -> {
                    result.put(Action.valueOf(rs.getString("action")), rs.getLong("event_count"));
                });
        return result;
    }

    public List<Attacker> topAttackers(SummaryQuery query) {
        Filter filter = filter(query);
        return jdbc.query("""
                SELECT host(client_ip) AS client_ip, count(*) AS event_count,
                       round(avg(threat_score), 1) AS avg_score
                FROM security_events
                """ + filter.sql() + " GROUP BY security_events.client_ip ORDER BY event_count DESC, security_events.client_ip ASC LIMIT 10",
                filter.parameters(), (rs, row) -> new Attacker(rs.getString("client_ip"),
                        rs.getLong("event_count"), rs.getBigDecimal("avg_score")));
    }

    public List<TargetedPath> topTargetedPaths(SummaryQuery query) {
        Filter filter = filter(query);
        return jdbc.query("SELECT path, count(*) AS event_count FROM security_events"
                + filter.sql() + " GROUP BY path ORDER BY event_count DESC, path ASC LIMIT 10",
                filter.parameters(), (rs, row) -> new TargetedPath(rs.getString("path"), rs.getLong("event_count")));
    }

    private Filter filter(SummaryQuery query) {
        String sql = " WHERE event_time >= :from AND event_time <= :to";
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("from", query.from().atOffset(ZoneOffset.UTC))
                .addValue("to", query.to().atOffset(ZoneOffset.UTC));
        if (query.configId() != null) {
            sql += " AND config_id = :configId";
            parameters.addValue("configId", query.configId());
        }
        return new Filter(sql, parameters);
    }

    private record Filter(String sql, MapSqlParameterSource parameters) {}
}
