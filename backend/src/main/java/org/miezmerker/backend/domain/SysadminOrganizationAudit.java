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
 * Append-only SYSADMIN organization-management ledger (#92, ADR 0016).
 * Records actor, affected organization, affected user where applicable,
 * operation, timestamp and relevant before/after state. Rows are never
 * updated or deleted by application code. Never stores passwords, keys,
 * session tokens or animal data: only organization metadata (slug, display
 * name, status, visibility) and membership role/status transitions.
 */
@Entity
@Table(name = "sysadmin_organization_audit")
public class SysadminOrganizationAudit {
    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_user_id")
    private AppUser actor;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_user_id")
    private AppUser target;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private SysadminOrgAction action;

    @Column(name = "before_state", nullable = false, length = 1000)
    private String beforeState = "";

    @Column(name = "after_state", nullable = false, length = 1000)
    private String afterState = "";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected SysadminOrganizationAudit() {}

    public SysadminOrganizationAudit(AppUser actor, Organization organization, AppUser target,
            SysadminOrgAction action, String beforeState, String afterState) {
        this.actor = actor;
        this.organization = organization;
        this.target = target;
        this.action = action;
        this.beforeState = beforeState == null ? "" : beforeState;
        this.afterState = afterState == null ? "" : afterState;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public AppUser getActor() { return actor; }
    public Organization getOrganization() { return organization; }
    public AppUser getTarget() { return target; }
    public SysadminOrgAction getAction() { return action; }
    public String getBeforeState() { return beforeState; }
    public String getAfterState() { return afterState; }
    public Instant getCreatedAt() { return createdAt; }
}
