package org.miezmerker.backend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "raw_observations",
        uniqueConstraints = @UniqueConstraint(columnNames = {"node_id", "sequence"}))
public class RawObservation {
    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "node_id", nullable = false)
    private NodeDevice node;

    @Column(nullable = false)
    private long sequence;

    @Column(name = "chip_id", nullable = false, length = 64)
    private String chipId;

    @Column(name = "observed_at_ms")
    private Long observedAtMs;

    @Column(name = "clock_status", nullable = false, length = 16)
    private String clockStatus;

    @Column(length = 36)
    private String incarnation;

    @Column(name = "monotonic_ms")
    private Long monotonicMs;

    @Column(name = "boot_counter")
    private Integer bootCounter;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "feeding_site_id")
    private FeedingSite feedingSite;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deployment_id")
    private NodeDeployment deployment;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt = Instant.now();

    protected RawObservation() {}

    public RawObservation(Organization organization, NodeDevice node, long sequence,
            String chipId, Long observedAtMs, String clockStatus, String incarnation,
            Long monotonicMs, Integer bootCounter, FeedingSite feedingSite,
            NodeDeployment deployment) {
        this.organization = organization;
        this.node = node;
        this.sequence = sequence;
        this.chipId = normalizeChipId(chipId);
        this.observedAtMs = observedAtMs;
        this.clockStatus = normalizeClockStatus(clockStatus);
        this.incarnation = incarnation;
        this.monotonicMs = monotonicMs;
        this.bootCounter = bootCounter;
        this.feedingSite = feedingSite;
        this.deployment = deployment;
        this.receivedAt = Instant.now();
    }

    public static String normalizeChipId(String chipId) {
        if (chipId == null) {
            return null;
        }
        return chipId.trim().toUpperCase(Locale.ROOT);
    }

    public static String normalizeClockStatus(String clockStatus) {
        if (clockStatus == null) {
            return null;
        }
        return clockStatus.trim().toUpperCase(Locale.ROOT);
    }

    /** True when two rows carry the same immutable raw payload (idempotent retry). */
    public boolean samePayload(String chipId, Long observedAtMs, String clockStatus,
            String incarnation, Long monotonicMs, Integer bootCounter) {
        return java.util.Objects.equals(this.chipId, normalizeChipId(chipId))
                && java.util.Objects.equals(this.observedAtMs, observedAtMs)
                && java.util.Objects.equals(this.clockStatus, normalizeClockStatus(clockStatus))
                && java.util.Objects.equals(this.incarnation, incarnation)
                && java.util.Objects.equals(this.monotonicMs, monotonicMs)
                && java.util.Objects.equals(this.bootCounter, bootCounter);
    }

    public UUID getId() { return id; }
    public Organization getOrganization() { return organization; }
    public NodeDevice getNode() { return node; }
    public long getSequence() { return sequence; }
    public String getChipId() { return chipId; }
    public Long getObservedAtMs() { return observedAtMs; }
    public String getClockStatus() { return clockStatus; }
    public String getIncarnation() { return incarnation; }
    public Long getMonotonicMs() { return monotonicMs; }
    public Integer getBootCounter() { return bootCounter; }
    public FeedingSite getFeedingSite() { return feedingSite; }
    public NodeDeployment getDeployment() { return deployment; }
    public Instant getReceivedAt() { return receivedAt; }
}
