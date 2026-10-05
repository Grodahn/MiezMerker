package org.miezmerker.backend.web;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.miezmerker.backend.security.AppUserDetails;
import org.miezmerker.backend.security.TenantService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Small #11 read model, including observed chips that have no Cat yet. */
@RestController
public class ChipActivityController {
    private final TenantService tenants;
    @PersistenceContext private EntityManager entities;

    public ChipActivityController(TenantService tenants) {
        this.tenants = tenants;
    }

    @Schema(name = "ChipActivityView")
    public record ChipActivityView(String chipId,
            @JsonFormat(shape = JsonFormat.Shape.STRING)
            @Schema(type = "string", pattern = "^[0-9]+$", nullable = true) Long lastSeenAtMillis,
            String lastReceivedAt, long observationCount, long uncertainClockCount,
            List<String> feedingSiteIds) {}

    @GetMapping(value = "/api/v1/organizations/{organizationId}/chip-activity",
            produces = "application/json")
    @Operation(operationId = "listChipActivity",
            summary = "ACTIVE member lists observed chips, reliable last sightings, clock issues and frozen historical feeding sites; server receipt time is separate")
    @Transactional(readOnly = true)
    public List<ChipActivityView> list(@PathVariable UUID organizationId,
            @AuthenticationPrincipal AppUserDetails principal) {
        tenants.requireActive(TenantService.currentUserId(principal), organizationId);
        String reliable = "o.clockStatus in ('SYNCED', 'RTC_ONLY', 'KNOWN') "
                + "and o.observedAtMs > 0 and o.observedAtMs < 9224318016000000";
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
        return rows.stream().map(row -> new ChipActivityView((String) row[0],
                (Long) row[1], ((Instant) row[2]).toString(), (Long) row[3], (Long) row[4],
                sites.getOrDefault((String) row[0], List.of()))).toList();
    }
}
