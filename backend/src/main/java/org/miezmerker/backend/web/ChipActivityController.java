package org.miezmerker.backend.web;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import org.miezmerker.backend.security.AppUserDetails;
import org.miezmerker.backend.security.TenantService;
import org.miezmerker.backend.service.ChipActivityService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Small #11 read model, including observed chips that have no Cat yet. */
@RestController
public class ChipActivityController {
    private final TenantService tenants;
    private final ChipActivityService activity;

    public ChipActivityController(TenantService tenants, ChipActivityService activity) {
        this.tenants = tenants;
        this.activity = activity;
    }

    @Schema(name = "ChipActivityView")
    public record ChipActivityView(String chipId,
            @JsonFormat(shape = JsonFormat.Shape.STRING)
            @Schema(type = "string", pattern = "^[0-9]+$", nullable = true) Long lastSeenAtMillis,
            String lastReceivedAt, long observationCount, long uncertainClockCount,
            List<String> feedingSiteIds) {}

    @GetMapping(value = "/api/v1/organizations/{organizationId}/chip-activity",
            produces = "application/json")
    @Operation(operationId = "listChipActivity",
            summary = "ACTIVE member lists observed chips, reliable last sightings, clock issues and frozen historical feeding sites; server receipt time is separate")
    @Transactional(readOnly = true)
    public List<ChipActivityView> list(@PathVariable UUID organizationId,
            @AuthenticationPrincipal AppUserDetails principal) {
        tenants.requireActive(TenantService.currentUserId(principal), organizationId);
        return activity.list(principal.getId(), organizationId).stream().map(a ->
                new ChipActivityView(a.chipId(), a.lastSeenAtMillis(), a.lastReceivedAt(),
                        a.observationCount(), a.uncertainClockCount(), a.feedingSiteIds())).toList();
    }
}
