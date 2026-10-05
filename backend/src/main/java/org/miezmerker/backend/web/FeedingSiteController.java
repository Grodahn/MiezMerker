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
import org.miezmerker.backend.repo.OrganizationRepository;
import org.miezmerker.backend.security.AppUserDetails;
import org.miezmerker.backend.security.TenantService;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Organization-scoped feeding sites (#9). Reads require an ACTIVE membership;
 * normal care requires ACTIVE membership; deletion requires ADMIN.
 * The organization is always taken from the server-side
 * membership check, never trusted from the client alone.
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/feeding-sites")
public class FeedingSiteController {
    private final FeedingSiteRepository sites;
    private final OrganizationRepository organizations;
    private final TenantService tenants;

    public FeedingSiteController(FeedingSiteRepository sites,
            OrganizationRepository organizations, TenantService tenants) {
        this.sites = sites;
        this.organizations = organizations;
        this.tenants = tenants;
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

    private static void checkLocation(Double lat, Double lng) {
        if ((lat == null) != (lng == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "locationLat and locationLng must be set together");
        }
        if (lat != null && (!Double.isFinite(lat) || lat < -90 || lat > 90)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "locationLat out of range");
        }
        if (lng != null && (!Double.isFinite(lng) || lng < -180 || lng > 180)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "locationLng out of range");
        }
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
        tenants.requireActive(principal.getId(), organizationId);
        return sites.findByOrganizationId(organizationId).stream()
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
        tenants.requireActive(principal.getId(), organizationId);
        return toView(sites.findByIdAndOrganizationId(siteId, organizationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
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
        tenants.requireActive(principal.getId(), organizationId);
        var org = organizations.findById(organizationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        checkLocation(request.locationLat(), request.locationLng());
        FeedingSite site = new FeedingSite(org, request.name().trim(),
                blankToNull(request.description()), request.locationLat(),
                request.locationLng(), blankToNull(request.locationLabel()));
        return toView(sites.save(site));
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
        tenants.requireActive(principal.getId(), organizationId);
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
        }
        FeedingSite site = sites.findByIdAndOrganizationId(siteId, organizationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (request.name() != null) {
            if (request.name().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name must not be blank");
            }
            site.setName(request.name().trim());
        }
        if (request.description() != null) {
            site.setDescription(blankToNull(request.description()));
        }
        if (Boolean.TRUE.equals(request.clearLocation())) {
            site.setLocation(null, null, null);
        } else if (request.locationLat() != null || request.locationLng() != null
                || request.locationLabel() != null) {
            Double lat = request.locationLat() != null ? request.locationLat()
                    : site.getLocationLat();
            Double lng = request.locationLng() != null ? request.locationLng()
                    : site.getLocationLng();
            checkLocation(lat, lng);
            site.setLocation(lat, lng, request.locationLabel() != null
                    ? blankToNull(request.locationLabel()) : site.getLocationLabel());
        }
        return toView(sites.save(site));
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

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
