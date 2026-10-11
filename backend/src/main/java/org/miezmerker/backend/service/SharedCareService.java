package org.miezmerker.backend.service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.miezmerker.backend.security.TenantService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Observation-backed online reads. All pages repeat tenant, proof and scope checks. */
@Service
@Transactional(readOnly = true)
public class SharedCareService {
    public static final int MAX_REFS = 50;
    public static final int MAX_LIMIT = 100;
    public static final int MAX_OFFSET = 10000;
    private final TenantService tenants;
    private final CatService cats;
    private final CatPhotoService photos;
    private final ObservationVisitQueryService queries;
    private final SharedCareRateLimiter limiter;
    public SharedCareService(TenantService tenants, CatService cats,
            CatPhotoService photos,
            ObservationVisitQueryService queries, SharedCareRateLimiter limiter) {
        this.tenants = tenants; this.cats = cats; this.photos = photos;
        this.queries = queries; this.limiter = limiter;
    }

    public SharedCareViews.ProfilePage resolve(UUID user, UUID org, List<UUID> refs, int limit, int offset) {
        Set<String> chips = new HashSet<>(authorize(user, org, refs, limit, offset).values());
        var rows = cats.sharedProfiles(org, chips, offset, limit + 1);
        return new SharedCareViews.ProfilePage(rows.stream().limit(limit).toList(), next(rows.size(), limit, offset));
    }

    public SharedCareViews.VisitPage visits(UUID user, UUID org, UUID observation, UUID source,
            int limit, int offset, boolean newestFirst) {
        if (observation == null || source == null) throw badRequest();
        var chips = authorize(user, org, List.of(observation), limit, offset);
        var rows = queries.sharedVisits(org, source, chips.get(observation), offset, limit + 1, newestFirst);
        return new SharedCareViews.VisitPage(rows.stream().limit(limit).toList(), next(rows.size(), limit, offset));
    }

    /**
     * Observation-backed foreign photo read (#95). Repeats the same tenant,
     * rate-limit and proof checks as every other shared page, then serves
     * the owner's photo only for effective CARE plus PHOTO. Each read
     * re-evaluates authorization, so revocation blocks the next read.
     */
    public CatPhotoService.PhotoData photo(UUID user, UUID org, UUID observation,
            UUID source) {
        if (observation == null || source == null) throw badRequest();
        // Single-element proof: limit/offset bounds are irrelevant here, but
        // the shared authorize path keeps tenant, limiter and proof semantics
        // identical to resolve/visits.
        var chips = authorize(user, org, List.of(observation), 1, 0);
        return photos.readSharedForChip(org, source, chips.get(observation));
    }

    private java.util.Map<UUID, String> authorize(UUID user, UUID org, List<UUID> refs, int limit, int offset) {
        tenants.requireActive(user, org);
        limiter.acquire(user);
        if (refs == null || refs.isEmpty() || refs.size() > MAX_REFS || refs.stream().anyMatch(java.util.Objects::isNull)
                || new HashSet<>(refs).size() != refs.size() || limit < 1 || limit > MAX_LIMIT
                || offset < 0 || offset > MAX_OFFSET) throw badRequest();
        var chips = queries.ownObservationChips(org, new HashSet<>(refs));
        if (chips.size() != refs.size()) {
            limiter.invalidProof(user);
            // Foreign, fabricated and Node/organization-mismatched references are identical.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid observation references");
        }
        return chips;
    }

    private static Integer next(int size, int limit, int offset) {
        return size > limit && offset + limit <= MAX_OFFSET ? offset + limit : null;
    }
    private static ResponseStatusException badRequest() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid shared-care request");
    }
}
