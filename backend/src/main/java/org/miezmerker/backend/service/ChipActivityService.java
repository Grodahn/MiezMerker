package org.miezmerker.backend.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.miezmerker.backend.security.TenantService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Shared batched #11 summaries. Sites are frozen observation associations, never current deployments. */
@Service
public class ChipActivityService {
    public static String reliableCondition(String alias) {
        return alias + ".clockStatus in ('SYNCED', 'RTC_ONLY', 'KNOWN') "
                + "and " + alias + ".observedAtMs > 0 and "
                + alias + ".observedAtMs < 9224318016000000";
    }

    private final TenantService tenants;
    @PersistenceContext private EntityManager entities;
    public ChipActivityService(TenantService tenants) { this.tenants = tenants; }
    public record Activity(String chipId, Long lastSeenAtMillis, String lastReceivedAt,
            long observationCount, long uncertainClockCount, List<String> feedingSiteIds) {}
    @Transactional(readOnly = true)
    public List<Activity> list(UUID user, UUID organizationId) {
        tenants.requireActive(user, organizationId);
        String reliable = reliableCondition("o");
        var rows = entities.createQuery("select o.chipId, "
                + "max(case when " + reliable + " then o.observedAtMs else null end), "
                + "max(o.receivedAt), count(o), "
                + "sum(case when " + reliable + " then 0 else 1 end) "
                + "from RawObservation o where o.organization.id = :org "
                + "group by o.chipId order by o.chipId", Object[].class)
                .setParameter("org", organizationId).getResultList();
        Map<String, List<String>> sites = new HashMap<>();
        entities.createQuery("select distinct o.chipId, o.feedingSite.id "
                + "from RawObservation o where o.organization.id = :org "
                + "and o.feedingSite is not null order by o.chipId, o.feedingSite.id", Object[].class)
                .setParameter("org", organizationId).getResultList().forEach(row ->
                    sites.computeIfAbsent((String) row[0], ignored -> new ArrayList<>())
                            .add(row[1].toString()));
        return rows.stream().map(row -> new Activity((String) row[0],
                (Long) row[1], ((Instant) row[2]).toString(), (Long) row[3], (Long) row[4],
                sites.getOrDefault((String) row[0], List.of()))).toList();
    }
}
