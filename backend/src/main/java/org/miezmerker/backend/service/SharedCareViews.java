package org.miezmerker.backend.service;

import java.util.List;
import java.util.UUID;
import io.swagger.v3.oas.annotations.media.Schema;

/** Explicit read-only allowlist. No entity identifiers except directory provenance. */
public final class SharedCareViews {
    private SharedCareViews() {}
    @Schema(name = "SharedCareSource")
    public record Source(UUID organizationId, String displayName) {}
    @Schema(name = "SharedCareProfile")
    public record Profile(String chipId, Source source, @Schema(nullable = true) String catDisplayName) {}
    @Schema(name = "SharedCareVisit")
    public record Visit(Source source, String startAt, String endAt, @Schema(nullable = true) String siteDisplayName) {}
    @Schema(name = "SharedCareProfilePage")
    public record ProfilePage(List<Profile> items, @Schema(nullable = true) Integer nextOffset) {}
    @Schema(name = "SharedCareVisitPage")
    public record VisitPage(List<Visit> items, @Schema(nullable = true) Integer nextOffset) {}
}
