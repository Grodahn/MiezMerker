package org.miezmerker.backend.web;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(name = "FeedingSiteCatActivityView", description = "One chip's activity at its frozen feeding site")
public record FeedingSiteCatActivityView(
        String chipId,
        @Schema(nullable = true, description = "Current organization-scoped Cat ID; null for unknown chips") UUID catId,
        @Schema(nullable = true, description = "Current Cat name; may be null even for a registered Cat") String catName,
        @Schema(nullable = true, description = "Latest persisted visit-gap-v1 end time (UTC); null when no reliable visit exists. Never server receipt time.") Instant lastReliableSightingAt,
        @Schema(nullable = true, description = "Latest server receipt of a raw observation with this frozen site (UTC); not a sighting timestamp") Instant lastReceivedAt,
        @Schema(description = "Number of persisted visit-gap-v1 visits at this site") long visitCount) {}
