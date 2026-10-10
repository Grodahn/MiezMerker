package org.miezmerker.backend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * Append-only sharing audit ledger (ADR 0016): who granted, revoked or
 * invalidated which scope between which organizations, and why. Rows are
 * never updated or deleted by application code.
 */
@Entity
@Table(name = "organization_share_audit")
public class OrganizationShareAudit {
    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_user_id")
    private AppUser actor;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_org_id")
    private Organization owner;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipient_org_id")
    private Organization target;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ShareScope scope;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ShareAction action;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected OrganizationShareAudit() {}

    public OrganizationShareAudit(AppUser actor, Organization owner, Organization target,
            ShareScope scope, ShareAction action, String reason) {
        this.actor = actor;
        this.owner = owner;
        this.target = target;
        this.scope = scope;
        this.action = action;
        this.reason = reason;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public AppUser getActor() { return actor; }
    public Organization getOwner() { return owner; }
    public Organization getTarget() { return target; }
    public ShareScope getScope() { return scope; }
    public ShareAction getAction() { return action; }
    public String getReason() { return reason; }
    public Instant getCreatedAt() { return createdAt; }
}
