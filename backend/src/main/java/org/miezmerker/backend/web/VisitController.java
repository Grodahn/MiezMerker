package org.miezmerker.backend.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.UUID;
import org.miezmerker.backend.service.ObservationVisitQueryService;
import org.miezmerker.backend.domain.DerivedVisit;
import org.miezmerker.backend.security.AppUserDetails;
import org.miezmerker.backend.security.TenantService;
import org.miezmerker.backend.service.VisitAggregationService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * Derived visits (#10, {@code visit-gap-v1}).
 *
 * <p>Visits are secondary rebuildable backend data derived from immutable raw
 * observations. Listing requires an ACTIVE membership; recompute requires
 * ADMIN and replaces only the derived rows of one organization and algorithm
 * version atomically. Raw observations are never modified.
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/visits")
public class VisitController {
    private final VisitAggregationService visits;
    private final TenantService tenants;
    private final ObservationVisitQueryService queries;

    @PersistenceContext
    private EntityManager entities;

    public VisitController(VisitAggregationService visits, TenantService tenants, ObservationVisitQueryService queries) {
        this.visits = visits;
        this.tenants = tenants;
        this.queries = queries;
    }

    @Schema(name = "VisitView")
    public record VisitView(UUID id, String organizationId, String feedingSiteId,
            String chipId, String catId, String startAt, String endAt,
            @JsonFormat(shape = JsonFormat.Shape.STRING)
            @Schema(type = "string", pattern = "^[0-9]+$") Long startAtMillis,
            @JsonFormat(shape = JsonFormat.Shape.STRING)
            @Schema(type = "string", pattern = "^[0-9]+$") Long endAtMillis,
            int observationCount, String algorithmVersion, int gapSeconds,
            String firstObservationId, String lastObservationId, String createdAt) {}

    @Schema(name = "RecomputeVisitsRequest")
    public record RecomputeVisitsRequest(Integer gapSeconds, String algorithmVersion) {}

    @Schema(name = "RecomputeVisitsResponse")
    public record RecomputeVisitsResponse(String algorithmVersion, int gapSeconds,
            int visitCount, long totalObservations, long usableObservations,
            long excludedUnknownClock, long excludedImplausibleTime,
            long excludedUnattributed, List<VisitView> visits) {}

    static VisitView toView(DerivedVisit v) {
        return new VisitView(v.getId(), v.getOrganization().getId().toString(),
                v.getFeedingSite().getId().toString(), v.getChipId(),
                v.getCat() == null ? null : v.getCat().getId().toString(),
                v.getStartAt().toString(), v.getEndAt().toString(),
                v.getStartAt().toEpochMilli(), v.getEndAt().toEpochMilli(),
                v.getObservationCount(), v.getAlgorithmVersion(), v.getGapSeconds(),
                v.getFirstObservation().getId().toString(),
                v.getLastObservation().getId().toString(), v.getCreatedAt().toString());
    }

    @GetMapping(produces = "application/json")
    @Operation(operationId = "listVisits",
            summary = "List derived visits of an organization (requires ACTIVE membership)")
    @Transactional(readOnly = true)
    public List<VisitView> list(@PathVariable UUID organizationId,
            @RequestParam(required = false) UUID feedingSiteId,
            @RequestParam(required = false) String chipId,
            @RequestParam(required = false) Long fromMillis,
            @RequestParam(required = false) Long toMillis,
            @RequestParam(required = false, defaultValue = "100") int limit,
            @RequestParam(required = false, defaultValue = "0") int offset,
            @AuthenticationPrincipal AppUserDetails principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        tenants.requireActive(principal.getId(), organizationId);
        return queries.visits(principal.getId(), organizationId, feedingSiteId,
                chipId, fromMillis, toMillis, limit, offset)
                .stream().map(VisitController::toView).toList();
    }

    @GetMapping(value = "/{visitId}", produces = "application/json")
    @Operation(operationId = "getVisit",
            summary = "Visit details; never readable across organizations")
    @Transactional(readOnly = true)
    public VisitView get(@PathVariable UUID organizationId, @PathVariable UUID visitId,
            @AuthenticationPrincipal AppUserDetails principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        tenants.requireActive(principal.getId(), organizationId);
        var rows = entities.createQuery(
                "select v from DerivedVisit v join fetch v.feedingSite "
                        + "left join fetch v.cat where v.id = :id "
                        + "and v.organization.id = :org",
                DerivedVisit.class).setParameter("id", visitId)
                .setParameter("org", organizationId).getResultList();
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        // Re-fetch provenance associations for the view inside the same transaction.
        DerivedVisit full = rows.get(0);
        full.getFirstObservation().getId();
        full.getLastObservation().getId();
        return toView(full);
    }

    @PostMapping(value = "/recompute", consumes = "application/json",
            produces = "application/json")
    @Operation(operationId = "recomputeVisits",
            summary = "ADMIN rebuilds visits from stored raw observations "
                    + "(no raw re-ingest, raw rows unchanged)")
    public RecomputeVisitsResponse recompute(@PathVariable UUID organizationId,
            @RequestBody(required = false) RecomputeVisitsRequest request,
            @AuthenticationPrincipal AppUserDetails principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        var result = visits.recompute(principal.getId(), organizationId,
                request == null ? null : request.gapSeconds(),
                request == null ? null : request.algorithmVersion());
        return new RecomputeVisitsResponse(result.algorithmVersion(), result.gapSeconds(),
                result.visitCount(), result.totalObservations(), result.usableObservations(),
                result.excludedUnknownClock(), result.excludedImplausibleTime(),
                result.excludedUnattributed(),
                result.visits().stream().map(VisitController::toView).toList());
    }
}
