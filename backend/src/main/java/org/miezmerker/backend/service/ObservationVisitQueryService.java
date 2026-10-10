package org.miezmerker.backend.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.miezmerker.backend.domain.Cat;
import org.miezmerker.backend.domain.RawObservation;
import org.miezmerker.backend.domain.DerivedVisit;
import org.miezmerker.backend.security.TenantService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Shared bounded tenant-scoped queries for REST and Admin; uses frozen site attribution. */
@Service
@Transactional(readOnly = true, noRollbackFor = ResponseStatusException.class)
public class ObservationVisitQueryService {
    private static final int MAX_LIMIT = 1000;
    @PersistenceContext private EntityManager entities;
    private final TenantService tenants;
    public ObservationVisitQueryService(TenantService tenants) { this.tenants = tenants; }

    public List<RawObservation> observations(UUID userId, UUID organizationId, UUID nodeId,
            UUID feedingSiteId, String chipId, Long fromMillis, Long toMillis,
            int limit, int offset, boolean newestFirst, Long sequence) {
        tenants.requireActive(userId, organizationId);
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "limit must be 1.." + MAX_LIMIT);
        }
        if (offset < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "offset must be >= 0");
        }
        rejectForeignNodeFilter(nodeId, organizationId);
        String normalizedChip =
                chipId == null || chipId.isBlank() ? null : Cat.normalizeChipId(chipId);
        String jpql = "select o from RawObservation o join fetch o.node n "
                + "left join fetch o.feedingSite "
                + "where o.organization.id = :org"
                + (nodeId != null ? " and n.nodeId = :node" : "")
                + (feedingSiteId != null ? " and o.feedingSite.id = :site" : "")
                + (sequence != null ? " and o.sequence = :sequence" : "")
                + (normalizedChip != null ? " and o.chipId = :chip" : "")
                + (fromMillis != null ? " and o.observedAtMs >= :from" : "")
                + (toMillis != null ? " and o.observedAtMs < :to" : "")
                + (newestFirst ? " order by o.receivedAt desc, o.sequence desc, o.id desc"
                        : " order by o.receivedAt asc, o.sequence asc, o.id asc");
        var query = entities.createQuery(jpql, RawObservation.class)
                .setParameter("org", organizationId)
                .setFirstResult(offset)
                .setMaxResults(limit);
        if (nodeId != null) {
            query.setParameter("node", nodeId);
        }
        if (feedingSiteId != null) {
            query.setParameter("site", feedingSiteId);
        }
        final String chipParam = normalizedChip;
        if (chipParam != null) {
            query.setParameter("chip", chipParam);
        }
        if (fromMillis != null) {
            query.setParameter("from", fromMillis);
        }
        if (toMillis != null) {
            query.setParameter("to", toMillis);
        }
        if (sequence != null) query.setParameter("sequence", sequence);
        return query.getResultList();
    }

    public List<DerivedVisit> visits(UUID userId, UUID organizationId, UUID feedingSiteId,
            String chipId, Long fromMillis, Long toMillis, int limit, int offset,
            boolean newestFirst) {
        tenants.requireActive(userId, organizationId);
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "limit must be 1.." + MAX_LIMIT);
        }
        if (offset < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "offset must be >= 0");
        }
        if (feedingSiteId != null) {
            var visible = entities.createQuery(
                    "select s.id from FeedingSite s where s.id = :site "
                            + "and s.organization.id = :org",
                    UUID.class).setParameter("site", feedingSiteId)
                    .setParameter("org", organizationId).getResultList();
            if (visible.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
        }
        String normalizedChip =
                chipId == null || chipId.isBlank() ? null : Cat.normalizeChipId(chipId);
        String jpql = "select v from DerivedVisit v join fetch v.feedingSite "
                + "left join fetch v.cat where v.organization.id = :org"
                + (feedingSiteId != null ? " and v.feedingSite.id = :site" : "")
                + (normalizedChip != null ? " and v.chipId = :chip" : "")
                + (fromMillis != null ? " and v.startAt >= :from" : "")
                + (toMillis != null ? " and v.startAt < :to" : "")
                + (newestFirst
                        ? " order by v.startAt desc, v.chipId desc, v.feedingSite.id desc, v.id desc"
                        : " order by v.startAt asc, v.chipId asc, v.feedingSite.id asc, v.id asc");
        var query = entities.createQuery(jpql, DerivedVisit.class)
                .setParameter("org", organizationId)
                .setFirstResult(offset).setMaxResults(limit);
        if (feedingSiteId != null) {
            query.setParameter("site", feedingSiteId);
        }
        final String chipParam = normalizedChip;
        if (chipParam != null) {
            query.setParameter("chip", chipParam);
        }
        if (fromMillis != null) {
            query.setParameter("from", java.time.Instant.ofEpochMilli(fromMillis));
        }
        if (toMillis != null) {
            query.setParameter("to", java.time.Instant.ofEpochMilli(toMillis));
        }
        return query.getResultList();
    }

    /**
     * Latest visit start per feeding site for one chip (#100). Grouped
     * server-side; frozen {@link DerivedVisit#getFeedingSite() feeding-site}
     * attribution keeps historical node moves correct. Every visit is derived
     * from reliable-clock observations only, so the latest start is always a
     * reliable time; no {@code received_at} is involved.
     */
    public record LatestSiteVisit(UUID feedingSiteId, String feedingSiteName,
            Instant latestVisitStart) {}

    public List<LatestSiteVisit> latestVisitsBySite(UUID userId, UUID organizationId,
            String chipId) {
        tenants.requireActive(userId, organizationId);
        String normalizedChip =
                chipId == null || chipId.isBlank() ? null : Cat.normalizeChipId(chipId);
        if (normalizedChip == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "chipId is required");
        }
        return entities.createQuery("select v.feedingSite.id, s.name, max(v.startAt) "
                + "from DerivedVisit v join v.feedingSite s "
                + "where v.organization.id = :org and v.chipId = :chip "
                + "group by v.feedingSite.id, s.name order by s.name", Object[].class)
                .setParameter("org", organizationId).setParameter("chip", normalizedChip)
                .getResultList().stream()
                .map(row -> new LatestSiteVisit((UUID) row[0], (String) row[1],
                        (Instant) row[2]))
                .toList();
    }

    private void rejectForeignNodeFilter(UUID nodeId, UUID organizationId) {
        if (nodeId == null) {
            return;
        }
        var visible = entities.createQuery(
                "select n.nodeId from NodeDevice n where n.nodeId = :node and n.organization.id = :org",
                UUID.class).setParameter("node", nodeId).setParameter("org", organizationId)
                .getResultList();
        if (visible.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

}
