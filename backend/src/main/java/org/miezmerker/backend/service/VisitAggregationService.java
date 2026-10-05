package org.miezmerker.backend.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.miezmerker.backend.domain.Cat;
import org.miezmerker.backend.domain.DerivedVisit;
import org.miezmerker.backend.domain.FeedingSite;
import org.miezmerker.backend.domain.RawObservation;
import org.miezmerker.backend.repo.CatRepository;
import org.miezmerker.backend.repo.DerivedVisitRepository;
import org.miezmerker.backend.repo.OrganizationRepository;
import org.miezmerker.backend.repo.RawObservationRepository;
import org.miezmerker.backend.security.TenantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Deterministic backend-side visit aggregation (#10, {@code visit-gap-v1}).
 *
 * <p>Algorithm {@code visit-gap-v1}:
 * <ul>
 *   <li>Usable observations are those with a trustworthy clock
 *       ({@code SYNCED}, {@code RTC_ONLY}, {@code KNOWN}), a non-null
 *       {@code observed_at_ms} inside the finite PostgreSQL range, and a
 *       frozen non-null {@code feeding_site_id} from #9 ingest attribution.
 *       Everything else (UNKNOWN clock, implausible time, unattributed site)
 *       is excluded from time-based grouping and stays visible via the raw
 *       observation API.</li>
 *   <li>Grouping key is {@code (organization, feedingSite, chipId)} using the
 *       frozen site stored on each raw row. The node's current site is never
 *       consulted.</li>
 *   <li>Within a group, rows are ordered by {@code (observed_at_ms, node_id,
 *       sequence, id)}. Consecutive rows belong to the same visit while
 *       {@code gap <= threshold} (inclusive boundary). The threshold defaults
 *       to 60 seconds and is configurable.</li>
 *   <li>Input ingest order ({@code received_at}, DB order) never affects the
 *       result; tie-breaking is fully deterministic.</li>
 * </ul>
 *
 * <p>Derived visits are secondary rebuildable data. Recompute deletes only
 * the derived rows of one organization and algorithm version in the same
 * transaction that inserts the new result; raw observations are only read.
 */
@Service
public class VisitAggregationService {
    private static final Logger log = LoggerFactory.getLogger(VisitAggregationService.class);

    /** First and currently only algorithm version. Stored on every visit. */
    public static final String ALGORITHM_VISIT_GAP_V1 = "visit-gap-v1";

    static final Set<String> TRUSTED_CLOCKS = Set.of("SYNCED", "RTC_ONLY", "KNOWN");
    static final long MAX_OBSERVED_AT_MS_EXCLUSIVE = 9_224_318_016_000_000L;
    static final int MIN_GAP_SECONDS = 1;
    static final int MAX_GAP_SECONDS = 86_400;

    public record VisitDraft(FeedingSite feedingSite, String chipId, long startMs,
            long endMs, int count, RawObservation first, RawObservation last) {}

    public record RecomputeResult(String algorithmVersion, int gapSeconds, int visitCount,
            long totalObservations, long usableObservations, long excludedUnknownClock,
            long excludedImplausibleTime, long excludedUnattributed,
            List<DerivedVisit> visits) {}

    private final TenantService tenants;
    private final OrganizationRepository organizations;
    private final RawObservationRepository observations;
    private final DerivedVisitRepository visits;
    private final CatRepository cats;
    private final int defaultGapSeconds;

    public VisitAggregationService(TenantService tenants,
            OrganizationRepository organizations, RawObservationRepository observations,
            DerivedVisitRepository visits, CatRepository cats,
            @Value("${miezmerker.visits.default-gap-seconds:60}") int defaultGapSeconds) {
        this.tenants = tenants;
        this.organizations = organizations;
        this.observations = observations;
        this.visits = visits;
        this.cats = cats;
        if (defaultGapSeconds < MIN_GAP_SECONDS || defaultGapSeconds > MAX_GAP_SECONDS) {
            throw new IllegalStateException("miezmerker.visits.default-gap-seconds must be "
                    + MIN_GAP_SECONDS + ".." + MAX_GAP_SECONDS);
        }
        this.defaultGapSeconds = defaultGapSeconds;
    }

    public int defaultGapSeconds() {
        return defaultGapSeconds;
    }

    /** Usable for time-based aggregation under {@code visit-gap-v1}. */
    static boolean isUsable(RawObservation o) {
        if (o == null || o.getObservedAtMs() == null || o.getFeedingSite() == null) {
            return false;
        }
        String clock = o.getClockStatus() == null ? null
                : o.getClockStatus().trim().toUpperCase(java.util.Locale.ROOT);
        if (clock == null || !TRUSTED_CLOCKS.contains(clock)) {
            return false;
        }
        long at = o.getObservedAtMs();
        return at > 0 && at < MAX_OBSERVED_AT_MS_EXCLUSIVE;
    }

    /**
     * Pure deterministic aggregation. Filters, groups, orders and splits
     * without any database access so it is unit-testable and independent of
     * ingest order.
     */
    static List<VisitDraft> aggregate(List<RawObservation> all, long thresholdMs) {
        record Key(UUID siteId, String chip) {}
        Map<Key, List<RawObservation>> groups = new HashMap<>();
        Map<Key, FeedingSite> sites = new HashMap<>();
        for (RawObservation o : all) {
            if (!isUsable(o)) {
                continue;
            }
            Key key = new Key(o.getFeedingSite().getId(), o.getChipId());
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(o);
            sites.putIfAbsent(key, o.getFeedingSite());
        }
        List<VisitDraft> drafts = new ArrayList<>();
        for (Map.Entry<Key, List<RawObservation>> entry : groups.entrySet()) {
            List<RawObservation> rows = entry.getValue();
            rows.sort(Comparator.comparingLong(RawObservation::getObservedAtMs)
                    .thenComparing(o -> o.getNode().getNodeId().toString())
                    .thenComparingLong(RawObservation::getSequence)
                    .thenComparing(o -> o.getId().toString()));
            List<RawObservation> current = new ArrayList<>();
            RawObservation prev = null;
            for (RawObservation o : rows) {
                if (prev != null
                        && o.getObservedAtMs() - prev.getObservedAtMs() > thresholdMs) {
                    drafts.add(toDraft(sites.get(entry.getKey()), current));
                    current = new ArrayList<>();
                }
                current.add(o);
                prev = o;
            }
            if (!current.isEmpty()) {
                drafts.add(toDraft(sites.get(entry.getKey()), current));
            }
        }
        // Deterministic insert order across groups.
        drafts.sort(Comparator.comparing((VisitDraft d) -> d.feedingSite().getId().toString())
                .thenComparing(VisitDraft::chipId)
                .thenComparingLong(VisitDraft::startMs));
        return drafts;
    }

    private static VisitDraft toDraft(FeedingSite site, List<RawObservation> current) {
        RawObservation first = current.get(0);
        RawObservation last = current.get(current.size() - 1);
        return new VisitDraft(site, first.getChipId(), first.getObservedAtMs(),
                last.getObservedAtMs(), current.size(), first, last);
    }

    /**
     * Recomputes all visits of one organization from persisted raw
     * observations. Deletes only derived rows of this organization and
     * algorithm version, then inserts the newly derived result atomically.
     * Raw observations are only read, never modified.
     */
    @Transactional
    public RecomputeResult recompute(UUID userId, UUID organizationId,
            Integer gapSecondsOrNull, String algorithmVersionOrNull) {
        tenants.requireAdmin(userId, organizationId);
        String algorithm = algorithmVersionOrNull == null
                || algorithmVersionOrNull.isBlank() ? ALGORITHM_VISIT_GAP_V1
                        : algorithmVersionOrNull.trim();
        if (!ALGORITHM_VISIT_GAP_V1.equals(algorithm)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "unsupported algorithmVersion; expected " + ALGORITHM_VISIT_GAP_V1);
        }
        int gapSeconds = gapSecondsOrNull == null ? defaultGapSeconds : gapSecondsOrNull;
        if (gapSeconds < MIN_GAP_SECONDS || gapSeconds > MAX_GAP_SECONDS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "gapSeconds must be " + MIN_GAP_SECONDS + ".." + MAX_GAP_SECONDS);
        }
        var org = organizations.findById(organizationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        List<RawObservation> all = observations.findByOrganizationIdWithRefs(organizationId);

        long unknown = 0;
        long implausible = 0;
        long unattributed = 0;
        for (RawObservation o : all) {
            if (isUsable(o)) {
                continue;
            }
            String clock = o.getClockStatus() == null ? null
                    : o.getClockStatus().trim().toUpperCase(java.util.Locale.ROOT);
            Long at = o.getObservedAtMs();
            if (at == null || clock == null || !TRUSTED_CLOCKS.contains(clock)) {
                unknown++;
            } else if (at <= 0 || at >= MAX_OBSERVED_AT_MS_EXCLUSIVE) {
                implausible++;
            } else {
                unattributed++;
            }
        }
        long usable = all.size() - unknown - implausible - unattributed;

        List<VisitDraft> drafts = aggregate(all, gapSeconds * 1000L);

        // Deterministic replacement scoped to (organization, algorithm).
        // Logs carry technical identity only, no chip/location duplication.
        List<DerivedVisit> existing =
                visits.findByOrganizationIdAndAlgorithmVersion(organizationId, algorithm);
        if (!existing.isEmpty()) {
            visits.deleteAll(existing);
            visits.flush();
        }
        List<DerivedVisit> created = new ArrayList<>();
        for (VisitDraft draft : drafts) {
            Cat cat = cats
                    .findByOrganizationIdAndChipId(organizationId, draft.chipId())
                    .orElse(null);
            DerivedVisit visit = new DerivedVisit(org, draft.feedingSite(), draft.chipId(),
                    cat, Instant.ofEpochMilli(draft.startMs()),
                    Instant.ofEpochMilli(draft.endMs()), draft.count(), algorithm,
                    gapSeconds, draft.first(), draft.last());
            created.add(visit);
        }
        List<DerivedVisit> saved = visits.saveAllAndFlush(created);
        // Initialize lazy associations while the transaction is still open so
        // the controller can map detached views after commit.
        for (DerivedVisit v : saved) {
            v.getOrganization().getId();
            v.getFeedingSite().getId();
            if (v.getCat() != null) {
                v.getCat().getId();
            }
            v.getFirstObservation().getId();
            v.getLastObservation().getId();
        }
        log.info("visits recomputed org={} algorithm={} gapSeconds={} visits={} "
                + "total={} usable={} excludedUnknown={} excludedImplausible={} "
                + "excludedUnattributed={}", organizationId, algorithm, gapSeconds,
                saved.size(), all.size(), usable, unknown, implausible, unattributed);
        return new RecomputeResult(algorithm, gapSeconds, saved.size(), all.size(), usable,
                unknown, implausible, unattributed, List.copyOf(saved));
    }
}
