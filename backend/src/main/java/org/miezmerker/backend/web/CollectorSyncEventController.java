package org.miezmerker.backend.web;

import java.util.UUID;
import org.miezmerker.backend.security.AppUserDetails;
import org.miezmerker.backend.security.TenantService;
import org.miezmerker.backend.service.CollectorSyncEventService;
import org.miezmerker.backend.service.CollectorSyncEventService.*;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
public class CollectorSyncEventController {
    private final CollectorSyncEventService events;
    public CollectorSyncEventController(CollectorSyncEventService events) { this.events = events; }
    @PostMapping(value="/collector-sync-events", consumes="application/json", produces="application/json")
    @Operation(operationId="reportCollectorSync", summary="Idempotent authorized collector-reported Node sync; no physical attendance proof")
    public Receipt report(@AuthenticationPrincipal AppUserDetails principal, @RequestBody Event event) {
        UUID actor = TenantService.currentUserId(principal);
        try { return events.ingest(actor, event); }
        catch (DataIntegrityViolationException conflict) {
            // Concurrent conflicting event ID on different node rows; transaction already rolled back.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "event identity conflict");
        }
    }
    @GetMapping(value="/organizations/{organizationId}/feeding-sites/{siteId}/sync-recipients", produces="application/json")
    @Operation(operationId="listCollectorSyncRecipients", summary="ADMIN only: last 1..20 distinct currently authorized users for missing-cat notices",
        description="Collector reports only. Conservative time windows, never verified attendance; overlapping windows have no definite chronological order. No SYSADMIN bypass.")
    public Recipients recipients(@AuthenticationPrincipal AppUserDetails principal, @PathVariable UUID organizationId,
            @PathVariable UUID siteId, @RequestParam(defaultValue="5") int limit) {
        return events.recipients(TenantService.currentUserId(principal), organizationId, siteId, limit);
    }
}
