package org.miezmerker.backend.service;

import java.util.List;
import java.util.UUID;
import org.miezmerker.backend.domain.FeedingSite;
import org.miezmerker.backend.repo.FeedingSiteRepository;
import org.miezmerker.backend.repo.OrganizationRepository;
import org.miezmerker.backend.security.TenantService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Shared feeding-site master-data logic for #9/#11 REST APIs and the
 * server-rendered Admin backend (#35).
 *
 * <p>All operations are organization-scoped server-side: the caller supplies
 * the active organization id, which is authorized via an ACTIVE membership
 * (REST/PWA) — the Admin UI additionally guarantees ADMIN via
 * {@code AdminService.requireActiveOrg} before delegating here, so ADMIN-only
 * semantics hold without weakening REST permissions.
 *
 * <p>Validation mirrors the REST contract: name required (1..255, trimmed),
 * description optional (max 2000, blank becomes null), location label
 * optional (max 255, blank becomes null), coordinates optional as a pair
 * with finite range checks. Historical deployments, raw observations and
 * derived visits are never touched: only master-data columns change.
 */
@Service
public class FeedingSiteService {
    private final FeedingSiteRepository sites;
    private final OrganizationRepository organizations;
    private final TenantService tenants;

    public FeedingSiteService(FeedingSiteRepository sites,
            OrganizationRepository organizations, TenantService tenants) {
        this.sites = sites;
        this.organizations = organizations;
        this.tenants = tenants;
    }

    @Transactional(readOnly = true)
    public List<FeedingSite> list(UUID userId, UUID organizationId) {
        tenants.requireActive(userId, organizationId);
        return sites.findByOrganizationId(organizationId);
    }

    @Transactional(readOnly = true)
    public FeedingSite get(UUID userId, UUID organizationId, UUID siteId) {
        tenants.requireActive(userId, organizationId);
        return sites.findByIdAndOrganizationId(siteId, organizationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @Transactional
    public FeedingSite create(UUID userId, UUID organizationId, String name,
            String description, Double locationLat, Double locationLng,
            String locationLabel) {
        tenants.requireActive(userId, organizationId);
        var org = organizations.findById(organizationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String cleanName = validatedName(name);
        String cleanDescription = validatedOptional(description, 2000, "description");
        String cleanLabel = validatedOptional(locationLabel, 255, "locationLabel");
        checkLocation(locationLat, locationLng);
        FeedingSite site = new FeedingSite(org, cleanName, cleanDescription,
                locationLat, locationLng, cleanLabel);
        return sites.save(site);
    }

    @Transactional
    public FeedingSite update(UUID userId, UUID organizationId, UUID siteId,
            String name, String description, Double locationLat, Double locationLng,
            String locationLabel, Boolean clearLocation) {
        tenants.requireActive(userId, organizationId);
        FeedingSite site = sites.findByIdAndOrganizationId(siteId, organizationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (name != null) {
            site.setName(validatedName(name));
        }
        if (description != null) {
            site.setDescription(validatedOptional(description, 2000, "description"));
        }
        if (Boolean.TRUE.equals(clearLocation)) {
            site.setLocation(null, null, null);
        } else if (locationLat != null || locationLng != null || locationLabel != null) {
            Double lat = locationLat != null ? locationLat : site.getLocationLat();
            Double lng = locationLng != null ? locationLng : site.getLocationLng();
            checkLocation(lat, lng);
            String label = locationLabel != null
                    ? validatedOptional(locationLabel, 255, "locationLabel")
                    : site.getLocationLabel();
            site.setLocation(lat, lng, label);
        }
        return sites.save(site);
    }

    static String validatedName(String name) {
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "name must not be blank");
        }
        String trimmed = name.trim();
        if (trimmed.length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "name must be at most 255 characters");
        }
        return trimmed;
    }

    static String validatedOptional(String value, int max, String field) {
        if (value == null) {
            return null;
        }
        if (value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > max) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + " must be at most " + max + " characters");
        }
        return trimmed;
    }

    static void checkLocation(Double lat, Double lng) {
        if ((lat == null) != (lng == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "locationLat and locationLng must be set together");
        }
        if (lat != null && (!Double.isFinite(lat) || lat < -90 || lat > 90)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "locationLat out of range");
        }
        if (lng != null && (!Double.isFinite(lng) || lng < -180 || lng > 180)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "locationLng out of range");
        }
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
