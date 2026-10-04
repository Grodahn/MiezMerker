package org.miezmerker.backend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "node_deployments")
public class NodeDeployment {
    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "node_id", nullable = false)
    private NodeDevice node;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "feeding_site_id", nullable = false)
    private FeedingSite feedingSite;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_until")
    private Instant validUntil;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected NodeDeployment() {}

    public NodeDeployment(Organization organization, NodeDevice node, FeedingSite feedingSite,
            Instant validFrom, Instant validUntil) {
        this.organization = organization;
        this.node = node;
        this.feedingSite = feedingSite;
        this.validFrom = validFrom;
        this.validUntil = validUntil;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public Organization getOrganization() { return organization; }
    public NodeDevice getNode() { return node; }
    public FeedingSite getFeedingSite() { return feedingSite; }
    public Instant getValidFrom() { return validFrom; }
    public Instant getValidUntil() { return validUntil; }
    public Instant getCreatedAt() { return createdAt; }

    public void setValidUntil(Instant validUntil) { this.validUntil = validUntil; }

    /** valid_from is inclusive, valid_until is exclusive (null = currently open). */
    public boolean covers(Instant instant) {
        if (instant == null) {
            return false;
        }
        if (instant.isBefore(validFrom)) {
            return false;
        }
        return validUntil == null || instant.isBefore(validUntil);
    }
}
