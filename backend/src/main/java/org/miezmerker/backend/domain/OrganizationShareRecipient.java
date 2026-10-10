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
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * One explicit ALLOWLIST recipient of a sharing policy (ADR 0016). The recipient
 * must be an ACTIVE, discoverable organization distinct from the owner; hiding
 * or disabling the recipient removes it from every allowlist.
 */
@Entity
@Table(name = "organization_share_recipients",
        uniqueConstraints = @UniqueConstraint(columnNames = {"policy_id", "recipient_org_id"}))
public class OrganizationShareRecipient {
    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "policy_id")
    private OrganizationSharePolicy policy;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipient_org_id")
    private Organization organization;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected OrganizationShareRecipient() {}

    public OrganizationShareRecipient(OrganizationSharePolicy policy, Organization organization) {
        this.policy = policy;
        this.organization = organization;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public OrganizationSharePolicy getPolicy() { return policy; }
    public Organization getOrganization() { return organization; }
    public Instant getCreatedAt() { return createdAt; }
}
