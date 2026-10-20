package org.miezmerker.backend.service;

import java.time.Instant;
import java.time.Duration;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.miezmerker.backend.domain.NodeState;
import org.miezmerker.backend.repo.NodeRepository;
import org.miezmerker.backend.repo.NodeDeploymentRepository;
import org.miezmerker.backend.repo.FeedingSiteRepository;
import org.miezmerker.backend.security.TenantService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Collector-reported success, never a cryptographic or physical attendance proof. */
@Service
public class CollectorSyncEventService {
    @io.swagger.v3.oas.annotations.media.Schema(name="CollectorSyncEvent")
    public record Event(UUID eventId, UUID organizationId, UUID ownerUserId, UUID nodeId,
            UUID incarnation, Long occurredAt, String timeQuality) {}
    @io.swagger.v3.oas.annotations.media.Schema(name="CollectorSyncEventReceipt")
    public record Receipt(UUID eventId, String status, String resolution) {}
    @io.swagger.v3.oas.annotations.media.Schema(name="CollectorSyncRecipient")
    public record Recipient(UUID userId, Instant earliestPossibleAt, Instant latestPossibleAt) {}
    @io.swagger.v3.oas.annotations.media.Schema(name="CollectorSyncRecipients")
    public record Recipients(String provenance, String ordering, List<Recipient> recipients) {}
    private final TenantService tenants;
    private final NodeRepository nodes;
    private final NodeDeploymentRepository deployments;
    private final FeedingSiteRepository sites;
    private final JdbcTemplate jdbc;
    private final Duration retention;
    public CollectorSyncEventService(TenantService tenants, NodeRepository nodes,
            NodeDeploymentRepository deployments, FeedingSiteRepository sites, JdbcTemplate jdbc,
            @Value("${miezmerker.sync-events.retention-days:30}") int retentionDays) {
        if (retentionDays < 1 || retentionDays > 30) throw new IllegalArgumentException("sync event retention must be 1..30 days");
        this.tenants = tenants; this.nodes = nodes; this.deployments = deployments;
        this.sites = sites; this.jdbc = jdbc; this.retention = Duration.ofDays(retentionDays);
    }
    @Transactional
    public Receipt ingest(UUID actor, Event event) {
        if (event == null || event.eventId() == null || event.organizationId() == null ||
                event.ownerUserId() == null || event.nodeId() == null || event.incarnation() == null ||
                event.occurredAt() == null || event.occurredAt() <= 0 ||
                !List.of("PHONE_ESTIMATE", "OFFLINE_ESTIMATE", "UNKNOWN").contains(event.timeQuality() == null ? "" : event.timeQuality())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid collector event");
        }
        // Expected local owner is a binding, never authority. Also closes cross-tab cookie races.
        if (!actor.equals(event.ownerUserId())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        tenants.requireActive(actor, event.organizationId());
        // Same serialization as deployment edits/ingest; history cannot move during resolution.
        var node = nodes.findByIdLocked(event.nodeId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (node.getState() != NodeState.CLAIMED || node.getOrganization() == null ||
                !node.getOrganization().getId().equals(event.organizationId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        var existing = jdbc.query("SELECT * FROM collector_sync_events WHERE event_id = ?", (rs, i) -> {
            boolean same = event.organizationId().equals(rs.getObject("organization_id", UUID.class)) &&
                actor.equals(rs.getObject("owner_user_id", UUID.class)) &&
                event.nodeId().equals(rs.getObject("node_id", UUID.class)) &&
                event.incarnation().equals(rs.getObject("incarnation", UUID.class)) &&
                event.occurredAt() == rs.getLong("occurred_at") && event.timeQuality().equals(rs.getString("time_quality"));
            if (!same) throw new ResponseStatusException(HttpStatus.CONFLICT, "event identity conflict");
            return new Receipt(event.eventId(), "DUPLICATE_IDENTICAL", rs.getString("resolution"));
        }, event.eventId());
        if (!existing.isEmpty()) return existing.getFirst();
        Instant received = Instant.now();
        Instant occurred = Instant.ofEpochMilli(event.occurredAt());
        if (occurred.isBefore(received.minus(retention))) throw new ResponseStatusException(HttpStatus.GONE, "event outside retention");
        Instant start = null, end = null;
        UUID site = null;
        String resolution = "UNRESOLVED";
        if (!"UNKNOWN".equals(event.timeQuality()) && !occurred.isAfter(received.plusSeconds(300)) &&
                !occurred.isBefore(node.getClaimedAt().minusSeconds(300))) {
            // Recent reports: server receipt brackets the reported success with a 5-minute
            // plausibility budget. Delayed/offline reports: do NOT trust phone UTC to choose
            // one side of a move; widen to the complete retained ownership window.
            start = "OFFLINE_ESTIMATE".equals(event.timeQuality()) || occurred.isBefore(received.minusSeconds(300)) ? received.minus(retention) : received.minusSeconds(300);
            if (start.isBefore(node.getClaimedAt())) start = node.getClaimedAt();
            end = received;
            final Instant lower = start, upper = end;
            var history = deployments.findByOrganizationIdAndNodeNodeId(event.organizationId(), event.nodeId());
            var intersecting = history.stream().filter(d -> !d.getValidFrom().isAfter(upper) &&
                (d.getValidUntil() == null || d.getValidUntil().isAfter(lower))).toList();
            if (intersecting.size() == 1 && intersecting.getFirst().covers(lower) && intersecting.getFirst().covers(upper)) {
                site = intersecting.getFirst().getFeedingSite().getId();
                resolution = "RESOLVED_ESTIMATE";
            } else if (intersecting.size() > 1) resolution = "AMBIGUOUS";
        }
        jdbc.update("INSERT INTO collector_sync_events (event_id, organization_id, owner_user_id, node_id, incarnation, occurred_at, time_quality, received_at, window_start, window_end, feeding_site_id, resolution) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            event.eventId(), event.organizationId(), actor, event.nodeId(), event.incarnation(), event.occurredAt(), event.timeQuality(),
            Timestamp.from(received), start == null ? null : Timestamp.from(start), end == null ? null : Timestamp.from(end), site, resolution);
        return new Receipt(event.eventId(), "CREATED", resolution);
    }
    @Transactional(readOnly = true)
    public Recipients recipients(UUID actor, UUID org, UUID site, int limit) {
        tenants.requireAdmin(actor, org); // No SYSADMIN bypass and no general employee-history API.
        if (limit < 1 || limit > 20) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be 1..20");
        sites.findByIdAndOrganizationId(site, org).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Instant cutoff = Instant.now().minus(retention);
        var result = jdbc.query("""
            SELECT e.owner_user_id, MAX(e.window_start) AS earliest, MAX(e.window_end) AS latest
            FROM collector_sync_events e
            JOIN organization_memberships m ON m.organization_id=e.organization_id AND m.user_id=e.owner_user_id
            JOIN app_users u ON u.id=e.owner_user_id
            WHERE e.organization_id=? AND e.feeding_site_id=? AND e.resolution='RESOLVED_ESTIMATE'
              AND e.received_at>=? AND e.occurred_at>=? AND e.window_end>=?
              AND m.status='ACTIVE' AND u.status='ACTIVE'
              AND EXISTS (SELECT 1 FROM node_deployments d WHERE d.node_id=e.node_id
                  AND d.organization_id=e.organization_id AND d.feeding_site_id=e.feeding_site_id
                  AND d.valid_from<=e.window_start AND (d.valid_until IS NULL OR d.valid_until>e.window_end))
              AND (SELECT COUNT(*) FROM node_deployments d WHERE d.node_id=e.node_id
                  AND d.valid_from<=e.window_end AND (d.valid_until IS NULL OR d.valid_until>e.window_start))=1
            GROUP BY e.owner_user_id
            ORDER BY earliest DESC, e.owner_user_id LIMIT ?
            """, (rs, i) -> new Recipient(rs.getObject("owner_user_id", UUID.class),
                rs.getTimestamp("earliest").toInstant(), rs.getTimestamp("latest").toInstant()),
                org, site, Timestamp.from(cutoff), cutoff.toEpochMilli(), Timestamp.from(cutoff), limit);
        return new Recipients("AUTHORIZED_COLLECTOR_REPORTED_SYNC_NOT_ATTENDANCE_PROOF",
            "CONSERVATIVE_LOWER_BOUND_DESC_UUID_TIEBREAK_OVERLAPPING_WINDOWS_UNORDERED", result);
    }
    @Transactional
    public int purge() {
        Instant cutoff = Instant.now().minus(retention);
        return jdbc.update("DELETE FROM collector_sync_events WHERE received_at < ? OR occurred_at < ?",
            Timestamp.from(cutoff), cutoff.toEpochMilli());
    }
}
