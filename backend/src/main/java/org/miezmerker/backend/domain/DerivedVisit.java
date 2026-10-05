package org.miezmerker.backend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.ConstraintMode;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * Secondary rebuildable visit derived from immutable raw observations (#10,
 * algorithm {@code visit-gap-v1}).
 *
 * <p>Never written by ingest; only (re)created by visit recompute from already
 * persisted {@link RawObservation}s. Raw observations are never modified,
 * deleted or rewritten when visits are built.
 *
 * <p>Grouping key is {@code (organization, feedingSite, chipId)} using the
 * frozen {@code feeding_site_id} stored on the raw observation at ingest time
 * (historical {@link NodeDeployment} attribution from #9). The node's current
 * site is never used; moving a node later cannot reinterpret old observations
 * or visits. Observations without frozen site attribution (e.g. {@code
 * UNKNOWN} clock rows) are excluded from aggregation and remain visible via
 * the raw observation API.
 */
@Entity
@Table(name = "derived_visits",
        uniqueConstraints = @UniqueConstraint(columnNames = {"organization_id",
                "algorithm_version", "feeding_site_id", "chip_id", "start_at"}))
public class DerivedVisit {
    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "feeding_site_id", nullable = false)
    private FeedingSite feedingSite;

    @Column(name = "chip_id", nullable = false, length = 64)
    private String chipId;

    /**
     * Best-effort snapshot of the {@link Cat} for {@code (organization,
     * chipId)} at recompute time; nullable when no cat is registered.
     * No foreign-key constraint on purpose: visits are secondary data and must
     * never block cat management. The current cat can always be resolved live
     * via {@code (organization_id, chip_id)}.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cat_id",
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private Cat cat;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    @Column(name = "observation_count", nullable = false)
    private int observationCount;

    @Column(name = "algorithm_version", nullable = false, length = 32)
    private String algorithmVersion;

    @Column(name = "gap_seconds", nullable = false)
    private int gapSeconds;

    /**
     * Provenance: first observation of the visit in deterministic order.
     * No FK constraint: visits are secondary and must never block raw-data
     * handling; the UUID still identifies the exact raw row deterministically.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "first_observation_id", nullable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private RawObservation firstObservation;

    /** Provenance: last observation of the visit in deterministic order. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "last_observation_id", nullable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private RawObservation lastObservation;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected DerivedVisit() {}

    public DerivedVisit(Organization organization, FeedingSite feedingSite, String chipId,
            Cat cat, Instant startAt, Instant endAt, int observationCount,
            String algorithmVersion, int gapSeconds, RawObservation firstObservation,
            RawObservation lastObservation) {
        this.organization = organization;
        this.feedingSite = feedingSite;
        this.chipId = chipId;
        this.cat = cat;
        this.startAt = startAt;
        this.endAt = endAt;
        this.observationCount = observationCount;
        this.algorithmVersion = algorithmVersion;
        this.gapSeconds = gapSeconds;
        this.firstObservation = firstObservation;
        this.lastObservation = lastObservation;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public Organization getOrganization() { return organization; }
    public FeedingSite getFeedingSite() { return feedingSite; }
    public String getChipId() { return chipId; }
    public Cat getCat() { return cat; }
    public Instant getStartAt() { return startAt; }
    public Instant getEndAt() { return endAt; }
    public int getObservationCount() { return observationCount; }
    public String getAlgorithmVersion() { return algorithmVersion; }
    public int getGapSeconds() { return gapSeconds; }
    public RawObservation getFirstObservation() { return firstObservation; }
    public RawObservation getLastObservation() { return lastObservation; }
    public Instant getCreatedAt() { return createdAt; }
}
