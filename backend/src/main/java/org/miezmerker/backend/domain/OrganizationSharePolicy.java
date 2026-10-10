package org.miezmerker.backend.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * One outgoing sharing policy per (owner organization, scope) (ADR 0016).
 * The owner organization decides which other organizations may read the given
 * scope; grants are read-only and never imply reciprocity or transitivity.
 * Hiding or disabling the owner invalidates all of its outgoing policies.
 */
@Entity
@Table(name = "organization_share_policies",
        uniqueConstraints = @UniqueConstraint(columnNames = {"owner_org_id", "scope"}))
public class OrganizationSharePolicy {
    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_org_id")
    private Organization organization;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ShareScope scope;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ShareAudience audience;

    @Column(nullable = false)
    private int revision;

    @OneToMany(mappedBy = "policy", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    private List<OrganizationShareRecipient> recipients = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected OrganizationSharePolicy() {}

    public OrganizationSharePolicy(Organization organization, ShareScope scope, ShareAudience audience) {
        this.organization = organization;
        this.scope = scope;
        this.audience = audience;
        this.revision = 1;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public UUID getId() { return id; }
    public Organization getOrganization() { return organization; }
    public ShareScope getScope() { return scope; }
    public ShareAudience getAudience() { return audience; }
    public int getRevision() { return revision; }
    public List<OrganizationShareRecipient> getRecipients() { return recipients; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void replaceAudience(ShareAudience audience) {
        this.audience = audience;
        this.revision++;
        this.updatedAt = Instant.now();
    }

    public void touch() {
        this.revision++;
        this.updatedAt = Instant.now();
    }

    public void addRecipient(Organization recipient) {
        recipients.add(new OrganizationShareRecipient(this, recipient));
    }

    public void clearRecipients() {
        recipients.clear();
    }
}
