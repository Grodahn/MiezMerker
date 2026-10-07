package org.miezmerker.backend.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.miezmerker.backend.domain.FeedingSite;
import org.miezmerker.backend.repo.FeedingSiteRepository;
import org.miezmerker.backend.security.AppUserDetails;
import org.miezmerker.backend.security.TenantService;
import org.miezmerker.backend.service.FeedingSiteService;
import org.miezmerker.backend.service.FeedingSiteCatActivityService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Organization-scoped feeding sites (#9). Reads require an ACTIVE membership;
 * normal care requires ACTIVE membership; deletion requires ADMIN.
 * The organization is always taken from the server-side
 * membership check, never trusted from the client alone.
 *
 * <p>Master-data logic lives in {@link FeedingSiteService} so the
 * server-rendered Admin backend (#35) reuses the same domain semantics
 * without a second implementation.
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/feeding-sites")
public class FeedingSiteController {
    private final FeedingSiteRepository sites;
    private final TenantService tenants;
    private final FeedingSiteService feedingSites;
    private final FeedingSiteCatActivityService activity;

    public FeedingSiteController(FeedingSiteRepository sites,
            TenantService tenants, FeedingSiteService feedingSites,
            FeedingSiteCatActivityService activity) {
        this.sites = sites;
        this.tenants = tenants;
        this.feedingSites = feedingSites;
        this.activity = activity;
    }

    @Schema(name = "FeedingSiteView")
    public record FeedingSiteView(UUID id, String organizationId, String name,
            String description, Double locationLat, Double locationLng, String locationLabel,
            String createdAt, String updatedAt) {}

    @Schema(name = "CreateFeedingSiteRequest")
    public record CreateFeedingSiteRequest(
            @NotBlank @Size(max = 255) String name,
            @Size(max = 2000) String description,
            Double locationLat,
            Double locationLng,
            @Size(max = 255) String locationLabel) {}

    @Schema(name = "UpdateFeedingSiteRequest")
    public record UpdateFeedingSiteRequest(
            @Size(max = 255) String name,
            @Size(max = 2000) String description,
            Double locationLat,
            Double locationLng,
            @Size(max = 255) String locationLabel,
            Boolean clearLocation) {}

    private static FeedingSiteView toView(FeedingSite s) {
        return new FeedingSiteView(s.getId(), s.getOrganization().getId().toString(),
                s.getName(), s.getDescription(), s.getLocationLat(), s.getLocationLng(),
                s.getLocationLabel(), s.getCreatedAt().toString(), s.getUpdatedAt().toString());
    }

    @GetMapping(produces = "application/json")
    @Operation(operationId = "listFeedingSites",
            summary = "List feeding sites of an organization (requires ACTIVE membership)")
    @Transactional(readOnly = true)
    public List<FeedingSiteView> list(@PathVariable UUID organizationId,
            @AuthenticationPrincipal AppUserDetails principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return feedingSites.list(principal.getId(), organizationId).stream()
                .map(FeedingSiteController::toView).toList();
    }

    @GetMapping(value = "/{siteId}", produces = "application/json")
    @Operation(operationId = "getFeedingSite",
            summary = "Feeding site details; never readable across organizations")
    @Transactional(readOnly = true)
    public FeedingSiteView get(@PathVariable UUID organizationId, @PathVariable UUID siteId,
            @AuthenticationPrincipal AppUserDetails principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return toView(feedingSites.get(principal.getId(), organizationId, siteId));
    }

    @GetMapping(value = "/{siteId}/cat-activity", produces = "application/json")
    @Operation(operationId = "listFeedingSiteCatActivity",
            summary = "Compact feeding-site cat/chip activity (ACTIVE membership)",
            description = "Latest reliable sighting is max end_at of persisted visit-gap-v1 visits; "
                    + "null until a reliable visit exists. Raw activity contributes chip identity and "
                    + "separate server receipt time only. Uses frozen site attribution, never current "
                    + "deployments. UNKNOWN/unattributed observations are not assigned to a site. "
                    + "Ordered by reliable sighting descending (nulls last), then chipId ascending. "
                    + "limit 1..1000 (default 100), offset >= 0. Foreign/missing sites return 404.")
    public List<FeedingSiteCatActivityView> catActivity(@PathVariable UUID organizationId,
            @PathVariable UUID siteId, @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset,
            @AuthenticationPrincipal AppUserDetails principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return activity.list(principal.getId(), organizationId, siteId, limit, offset);
    }

    @PostMapping(consumes = "application/json", produces = "application/json")
    @Operation(operationId = "createFeedingSite",
            summary = "ACTIVE member creates a feeding site in their own organization")
    @Transactional
    public FeedingSiteView create(@PathVariable UUID organizationId,
            @Valid @RequestBody CreateFeedingSiteRequest request,
            @AuthenticationPrincipal AppUserDetails principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return toView(feedingSites.create(principal.getId(), organizationId,
                request.name(), request.description(), request.locationLat(),
                request.locationLng(), request.locationLabel()));
    }

    @PatchMapping(value = "/{siteId}", consumes = "application/json",
            produces = "application/json")
    @Operation(operationId = "updateFeedingSite",
            summary = "ACTIVE member updates a feeding site of their own organization")
    @Transactional
    public FeedingSiteView update(@PathVariable UUID organizationId, @PathVariable UUID siteId,
            @Valid @RequestBody UpdateFeedingSiteRequest request,
            @AuthenticationPrincipal AppUserDetails principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
        }
        return toView(feedingSites.update(principal.getId(), organizationId, siteId,
                request.name(), request.description(), request.locationLat(),
                request.locationLng(), request.locationLabel(), request.clearLocation()));
    }

    @DeleteMapping("/{siteId}")
    @Operation(operationId = "deleteFeedingSite",
            summary = "ADMIN deletes a feeding site (blocked while deployments reference it)")
    @Transactional
    public void delete(@PathVariable UUID organizationId, @PathVariable UUID siteId,
            @AuthenticationPrincipal AppUserDetails principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        tenants.requireAdmin(principal.getId(), organizationId);
        FeedingSite site = sites.findByIdAndOrganizationId(siteId, organizationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        try {
            sites.delete(site);
            sites.flush();
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "feeding site is still referenced by deployments or observations");
        }
    }
}
