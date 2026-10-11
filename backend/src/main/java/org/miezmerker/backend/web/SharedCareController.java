package org.miezmerker.backend.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.miezmerker.backend.security.AppUserDetails;
import org.miezmerker.backend.security.TenantService;
import org.miezmerker.backend.service.SharedCareService;
import org.miezmerker.backend.service.SharedCareViews;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/shared-care")
public class SharedCareController {
    private final SharedCareService shared;
    public SharedCareController(SharedCareService shared) { this.shared = shared; }
    @Schema(name = "ResolveSharedCareRequest")
    public record ResolveRequest(
            @NotNull @Size(min = 1, max = SharedCareService.MAX_REFS) List<@NotNull UUID> ownObservationRefs,
            Integer limit, Integer offset) {}
    @Schema(name = "SharedCareVisitsRequest")
    public record VisitsRequest(@NotNull UUID ownObservationRef, @NotNull UUID sourceOrganizationId,
            Integer limit, Integer offset, Boolean newestFirst) {}

    @PostMapping(value = "/resolve", consumes = "application/json", produces = "application/json")
    @Operation(operationId = "resolveSharedCare", summary = "Resolve CARE projections using owned persisted observations")
    public SharedCareViews.ProfilePage resolve(@PathVariable UUID organizationId,
            @AuthenticationPrincipal AppUserDetails principal, @Valid @RequestBody ResolveRequest request) {
        return shared.resolve(TenantService.currentUserId(principal), organizationId,
                request.ownObservationRefs(), limit(request.limit()), offset(request.offset()));
    }

    @PostMapping(value = "/visits", consumes = "application/json", produces = "application/json")
    @Operation(operationId = "listSharedCareVisits", summary = "Page explicitly shared visit summaries with fresh observation proof")
    public SharedCareViews.VisitPage visits(@PathVariable UUID organizationId,
            @AuthenticationPrincipal AppUserDetails principal, @Valid @RequestBody VisitsRequest request) {
        return shared.visits(TenantService.currentUserId(principal), organizationId,
                request.ownObservationRef(), request.sourceOrganizationId(), limit(request.limit()),
                offset(request.offset()), request.newestFirst() == null || request.newestFirst());
    }
    private static int limit(Integer value) { return value == null ? 50 : value; }
    private static int offset(Integer value) { return value == null ? 0 : value; }
}
