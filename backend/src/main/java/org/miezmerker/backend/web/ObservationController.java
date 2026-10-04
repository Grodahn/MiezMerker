package org.miezmerker.backend.web;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.miezmerker.backend.domain.Cat;
import org.miezmerker.backend.domain.RawObservation;
import org.miezmerker.backend.security.AppUserDetails;
import org.miezmerker.backend.security.TenantService;
import org.miezmerker.backend.service.ObservationIngestService;
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

/**
 * Raw observation ingest and listing (#9).
 *
 * <p>Ingest is idempotent per {@code (node_id, sequence)}: identical retries
 * are accepted, conflicting retries are rejected without overwriting, and the
 * database unique constraint guards concurrent duplicates. Unknown nodes are
 * rejected (claim via #18 first); {@code UNKNOWN}-clock rows are stored
 * without deployment attribution rather than guessed.
 *
 * <p>Listing is always organization-scoped server-side; cross-tenant access by
 * guessed ids returns 403/404 and never data.
 */
@RestController
@RequestMapping("/api/v1")
public class ObservationController {
    private static final int MAX_BATCH = 5000;
    private static final int MAX_LIMIT = 1000;

    private final ObservationIngestService ingest;
    private final TenantService tenants;

    @PersistenceContext
    private EntityManager entities;

    public ObservationController(ObservationIngestService ingest, TenantService tenants) {
        this.ingest = ingest;
        this.tenants = tenants;
    }

    @Schema(name = "IngestObservationRequest")
    public record IngestObservationRequest(
            @NotNull UUID nodeId,
            @NotNull @JsonFormat(shape = JsonFormat.Shape.STRING)
            @Schema(type = "string", pattern = "^[0-9]+$", example = "9007199254740993") Long sequence,
            String chipId,
            @JsonFormat(shape = JsonFormat.Shape.STRING)
            @Schema(type = "string", pattern = "^[0-9]+$") Long observedAtMillis,
            String clockStatus,
            String incarnation,
            @JsonFormat(shape = JsonFormat.Shape.STRING)
            @Schema(type = "string", pattern = "^[0-9]+$") Long monotonicMs,
            Integer bootCounter) {}

    @Schema(name = "IngestBatchRequest")
    public record IngestBatchRequest(
            @NotNull UUID organizationId,
            List<IngestObservationRequest> observations) {}

    @Schema(name = "IngestItemResult")
    public record IngestItemResult(UUID nodeId,
            @JsonFormat(shape = JsonFormat.Shape.STRING)
            @Schema(type = "string", pattern = "^-?[0-9]+$") Long sequence, String status,
            String message) {}

    @Schema(name = "IngestBatchResponse")
    public record IngestBatchResponse(int inserted, int duplicates, int conflicts,
            int rejected, List<IngestItemResult> results) {}

    @Schema(name = "RawObservationView")
    public record RawObservationView(UUID id, String organizationId, String nodeId,
            @JsonFormat(shape = JsonFormat.Shape.STRING)
            @Schema(type = "string", pattern = "^[0-9]+$") long sequence, String chipId,
            @JsonFormat(shape = JsonFormat.Shape.STRING)
            @Schema(type = "string", pattern = "^[0-9]+$") Long observedAtMillis, String clockStatus,
            String incarnation, @JsonFormat(shape = JsonFormat.Shape.STRING)
            @Schema(type = "string", pattern = "^[0-9]+$") Long monotonicMs,
            Integer bootCounter, String feedingSiteId,
            String deploymentId, String receivedAt) {}

    private static RawObservationView toView(RawObservation o) {
        return new RawObservationView(o.getId(), o.getOrganization().getId().toString(),
                o.getNode().getNodeId().toString(), o.getSequence(), o.getChipId(),
                o.getObservedAtMs(), o.getClockStatus(), o.getIncarnation(),
                o.getMonotonicMs(), o.getBootCounter(),
                o.getFeedingSite() == null ? null : o.getFeedingSite().getId().toString(),
                o.getDeployment() == null ? null : o.getDeployment().getId().toString(),
                o.getReceivedAt().toString());
    }

    @PostMapping(value = "/observations/ingest", consumes = "application/json",
            produces = "application/json")
    @Operation(operationId = "ingestObservations",
            summary = "Idempotent batch ingest of raw observations "
                    + "(ACTIVE membership; per-item results)")
    public IngestBatchResponse ingest(@Valid @RequestBody IngestBatchRequest request,
            @AuthenticationPrincipal AppUserDetails principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        if (request == null || request.organizationId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "organizationId is required");
        }
        List<IngestObservationRequest> items =
                request.observations() == null ? List.of() : request.observations();
        if (items.size() > MAX_BATCH) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "batch exceeds " + MAX_BATCH + " observations");
        }
        if (items.stream().anyMatch(item -> item == null)) {
            // Never silently drop a null entry: the client must resend the batch.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "observations must not contain null entries");
        }
        List<ObservationIngestService.IngestItem> parsed = new ArrayList<>();
        for (IngestObservationRequest item : items) {
            parsed.add(new ObservationIngestService.IngestItem(item.nodeId(),
                    item.sequence() == null ? -1 : item.sequence(), item.chipId(),
                    item.observedAtMillis(), item.clockStatus(), blankToNull(item.incarnation()),
                    item.monotonicMs(), item.bootCounter()));
        }
        var result = ingest.ingestBatch(principal.getId(), request.organizationId(), parsed);
        return new IngestBatchResponse(result.inserted(), result.duplicates(),
                result.conflicts(), result.rejected(),
                result.results().stream()
                        .map(r -> new IngestItemResult(r.nodeId(), r.sequence(), r.status(),
                                r.message()))
                        .toList());
    }

    @GetMapping(value = "/observations", produces = "application/json")
    @Operation(operationId = "listObservations",
            summary = "List raw observations with organization, node, site, chip and time filters")
    @Transactional(readOnly = true)
    public List<RawObservationView> list(
            @RequestParam UUID organizationId,
            @RequestParam(required = false) UUID nodeId,
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
                + (normalizedChip != null ? " and o.chipId = :chip" : "")
                + (fromMillis != null ? " and o.observedAtMs >= :from" : "")
                + (toMillis != null ? " and o.observedAtMs < :to" : "")
                + " order by o.receivedAt asc, o.sequence asc, o.id asc";
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
        return query.getResultList().stream().map(ObservationController::toView).toList();
    }

    @GetMapping(value = "/observations/{observationId}", produces = "application/json")
    @Operation(operationId = "getObservation",
            summary = "Raw observation details; never readable across organizations")
    @Transactional(readOnly = true)
    public RawObservationView get(@PathVariable UUID observationId,
            @AuthenticationPrincipal AppUserDetails principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        RawObservation observation = entities.find(RawObservation.class, observationId);
        if (observation == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        // Tenant gate on the stored organization, not on any client parameter.
        try {
            tenants.requireActive(principal.getId(), observation.getOrganization().getId());
        } catch (ResponseStatusException e) {
            if (e.getStatusCode() != HttpStatus.FORBIDDEN) throw e;
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        // Re-fetch with joins for the view (find does not fetch associations).
        var full = entities.createQuery(
                "select o from RawObservation o join fetch o.node n "
                        + "left join fetch o.feedingSite where o.id = :id",
                RawObservation.class).setParameter("id", observationId).getResultList();
        if (full.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return toView(full.get(0));
    }

    @GetMapping(value = "/organizations/{organizationId}/nodes/{nodeId}/observations",
            produces = "application/json")
    @Operation(operationId = "listNodeObservations",
            summary = "List one node's observations within an explicit organization context")
    @Transactional(readOnly = true)
    public List<RawObservationView> listByNode(@PathVariable UUID organizationId,
            @PathVariable UUID nodeId,
            @RequestParam(required = false, defaultValue = "100") int limit,
            @RequestParam(required = false, defaultValue = "0") int offset,
            @AuthenticationPrincipal AppUserDetails principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        tenants.requireActive(principal.getId(), organizationId);
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "limit must be 1.." + MAX_LIMIT);
        }
        if (offset < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "offset must be >= 0");
        }
        rejectForeignNodeFilter(nodeId, organizationId);
        var rows = entities.createQuery(
                "select o from RawObservation o join fetch o.node n "
                        + "left join fetch o.feedingSite "
                        + "where o.organization.id = :org and n.nodeId = :node "
                        + "order by o.sequence asc",
                RawObservation.class).setParameter("org", organizationId)
                .setParameter("node", nodeId).setFirstResult(offset).setMaxResults(limit)
                .getResultList();
        return rows.stream().map(ObservationController::toView).toList();
    }

    /**
     * Unknown and foreign node filters have identical visibility.
     */
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

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
