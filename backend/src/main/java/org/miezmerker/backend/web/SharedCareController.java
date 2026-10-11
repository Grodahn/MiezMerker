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
    @Schema(name = "SharedCarePhotoRequest")
    public record PhotoRequest(@NotNull UUID ownObservationRef,
            @NotNull UUID sourceOrganizationId) {}

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

    @PostMapping(value = "/photo", consumes = "application/json", produces = "image/jpeg")
    @Operation(operationId = "getSharedCarePhoto",
            summary = "Read an explicitly shared foreign cat photo with fresh observation proof (requires CARE plus PHOTO)")
    public org.springframework.http.ResponseEntity<byte[]> photo(
            @PathVariable UUID organizationId,
            @AuthenticationPrincipal AppUserDetails principal,
            @Valid @RequestBody PhotoRequest request) {
        var data = shared.photo(TenantService.currentUserId(principal), organizationId,
                request.ownObservationRef(), request.sourceOrganizationId());
        var headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.parseMediaType(
                data.contentType()));
        headers.setContentLength(data.bytes().length);
        // Foreign shared photos are online-only: never stored by caches, and
        // every read re-evaluates authorization so revocation takes effect
        // on the next read.
        headers.setCacheControl("no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Content-Security-Policy", "default-src 'none'");
        headers.setContentDispositionFormData("inline", "shared-cat-photo.jpg");
        return new org.springframework.http.ResponseEntity<>(data.bytes(), headers,
                org.springframework.http.HttpStatus.OK);
    }
    private static int limit(Integer value) { return value == null ? 50 : value; }
    private static int offset(Integer value) { return value == null ? 0 : value; }
}
