package org.miezmerker.backend.admin;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.miezmerker.backend.domain.RawObservation;
import org.miezmerker.backend.repo.FeedingSiteRepository;
import org.miezmerker.backend.security.TenantService;
import org.miezmerker.backend.service.TemporalProjectionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Admin-only presentation adapter; never persists or changes the projection contract. */
@Service
public class AdminTemporalPresentation {
    private final TemporalProjectionService projections;
    private final FeedingSiteRepository sites;
    private final TenantService tenants;

    public AdminTemporalPresentation(TemporalProjectionService projections,
            FeedingSiteRepository sites, TenantService tenants) {
        this.projections = projections;
        this.sites = sites;
        this.tenants = tenants;
    }

    @Transactional
    public Map<UUID, TemporalProjectionService.Projection> projectPage(UUID user, UUID org,
            List<RawObservation> rows) {
        tenants.requireAdmin(user, org);
        // At most 100 visible rows. Stable node lock order also matches the temporal visit reader.
        if (rows.size() > 100) throw new IllegalArgumentException("Admin page too large");
        var byNode = new TreeMap<UUID, List<RawObservation>>(Comparator.comparing(UUID::toString));
        for (var row : rows) byNode.computeIfAbsent(row.getNode().getNodeId(), k -> new ArrayList<>()).add(row);
        var result = new HashMap<UUID, TemporalProjectionService.Projection>();
        for (var entry : byNode.entrySet()) {
            for (var projection : projections.projectRows(user, org, entry.getKey(), entry.getValue()))
                result.put(projection.observationId(), projection);
        }
        return Map.copyOf(result);
    }

    @Transactional(readOnly = true)
    public Map<UUID, String> siteNames(UUID user, UUID org, Collection<UUID> identifiers) {
        tenants.requireAdmin(user, org);
        var ids = identifiers.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.size() > 100) throw new IllegalArgumentException("Admin page too large");
        if (ids.isEmpty()) return Map.of();
        var names = new HashMap<UUID, String>();
        for (var site : sites.findByOrganizationIdAndIdIn(org, ids)) names.put(site.getId(), site.getName());
        return Map.copyOf(names);
    }

    /** Phone estimates are displayed at minute precision, rounding bounds outwards. */
    public static String utc(Long millis, boolean estimated, boolean upper) {
        if (millis == null) return null;
        var instant = Instant.ofEpochMilli(millis);
        if (!estimated) return instant.toString();
        var minute = instant.truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        if (upper && !instant.equals(minute)) minute = minute.plusSeconds(60);
        return DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC).format(minute);
    }
}
