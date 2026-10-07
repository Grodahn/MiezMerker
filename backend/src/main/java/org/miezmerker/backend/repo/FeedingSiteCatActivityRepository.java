package org.miezmerker.backend.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.miezmerker.backend.service.VisitAggregationService;
import org.miezmerker.backend.web.FeedingSiteCatActivityView;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** One bounded aggregate query, using only frozen attribution; PostgreSQL and H2. */
@Repository
public class FeedingSiteCatActivityRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public FeedingSiteCatActivityRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<FeedingSiteCatActivityView> list(UUID org, UUID site, int limit, int offset) {
        return jdbc.query("""
                with visits as (
                    select chip_id, max(end_at) as last_seen, count(*) as visit_count
                    from derived_visits
                    where organization_id = :org and feeding_site_id = :site
                        and algorithm_version = :algorithm
                    group by chip_id
                ), receipts as (
                    select chip_id, max(received_at) as last_received
                    from raw_observations
                    where organization_id = :org and feeding_site_id = :site
                    group by chip_id
                ), chips as (
                    select chip_id from visits union select chip_id from receipts
                )
                select chips.chip_id, c.id as cat_id, c.name as cat_name,
                    v.last_seen, r.last_received, coalesce(v.visit_count, 0) as visit_count
                from chips
                left join visits v on v.chip_id = chips.chip_id
                left join receipts r on r.chip_id = chips.chip_id
                left join cats c on c.organization_id = :org and c.chip_id = chips.chip_id
                order by v.last_seen desc nulls last, chips.chip_id asc
                limit :limit offset :offset
                """, Map.of("org", org, "site", site, "algorithm",
                        VisitAggregationService.ALGORITHM_VISIT_GAP_V1,
                        "limit", limit, "offset", offset),
                (row, index) -> new FeedingSiteCatActivityView(row.getString("chip_id"),
                        row.getObject("cat_id", UUID.class), row.getString("cat_name"),
                        instant(row, "last_seen"), instant(row, "last_received"),
                        row.getLong("visit_count")));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        var timestamp = row.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
