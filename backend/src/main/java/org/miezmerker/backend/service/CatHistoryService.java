package org.miezmerker.backend.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.miezmerker.backend.security.TenantService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Bounded persisted visit history and batched historical site enrichment. No recomputation. */
@Service
@Transactional(readOnly = true)
public class CatHistoryService {
    @PersistenceContext private EntityManager entities;
    private final TenantService tenants;
    private final ObservationVisitQueryService visits;
    public CatHistoryService(TenantService tenants, ObservationVisitQueryService visits) {
        this.tenants = tenants;
        this.visits = visits;
    }
    public record LastSite(String chipId, UUID id, String name) {}
    public record Visit(UUID id, String siteName, String start, String end, int observations) {}
    public record SiteVisit(UUID id, String name, String latestVisitStart) {}
    public List<LastSite> lastSites(UUID user, UUID org) {
        tenants.requireActive(user, org);
        // Tied reliable sightings may name multiple sites; never arbitrarily choose one.
        return entities.createQuery("select distinct o.chipId, s.id, s.name from RawObservation o "
                + "join o.feedingSite s where o.organization.id = :org and s.organization.id = :org "
                + "and " + ChipActivityService.reliableCondition("o") + " "
                + "and o.observedAtMs = (select max(r.observedAtMs) from RawObservation r "
                + "where r.organization.id = :org and r.chipId = o.chipId "
                + "and " + ChipActivityService.reliableCondition("r") + ") "
                + "order by o.chipId, s.name", Object[].class).setParameter("org", org)
                .getResultList().stream().map(r -> new LastSite((String) r[0], (UUID) r[1], (String) r[2])).toList();
    }
    public long visitTotal(UUID user, UUID org, String chip) {
        tenants.requireActive(user, org);
        return entities.createQuery("select count(v) from DerivedVisit v "
                + "where v.organization.id = :org and v.chipId = :chip", Long.class)
                .setParameter("org", org).setParameter("chip", chip).getSingleResult();
    }
    public List<Visit> visits(UUID user, UUID org, String chip) {
        return visitPage(user, org, chip, 0, 10);
    }
    public List<Visit> visitPage(UUID user, UUID org, String chip, int offset, int limit) {
        tenants.requireActive(user, org);
        return entities.createQuery("select v.id, s.name, v.startAt, v.endAt, v.observationCount "
                + "from DerivedVisit v join v.feedingSite s where v.organization.id = :org "
                + "and s.organization.id = :org and v.chipId = :chip order by v.startAt desc, v.id", Object[].class)
                .setParameter("org", org).setParameter("chip", chip)
                .setFirstResult(offset).setMaxResults(limit)
                .getResultList().stream().map(r -> new Visit((UUID) r[0], (String) r[1],
                        ((Instant) r[2]).toString(), ((Instant) r[3]).toString(), (Integer) r[4])).toList();
    }
    public List<SiteVisit> latestSiteVisits(UUID user, UUID org, String chip) {
        tenants.requireActive(user, org);
        return visits.latestVisitsBySite(user, org, chip).stream()
                .map(v -> new SiteVisit(v.feedingSiteId(), v.feedingSiteName(),
                        v.latestVisitStart().toString()))
                .toList();
    }
}
