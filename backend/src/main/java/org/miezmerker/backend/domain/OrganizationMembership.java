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
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "organization_memberships",
        uniqueConstraints = @UniqueConstraint(columnNames = {"organization_id", "user_id"}))
public class OrganizationMembership {
    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MembershipRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MembershipStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "disabled_at")
    private Instant disabledAt;

    protected OrganizationMembership() {}

    public OrganizationMembership(Organization organization, AppUser user,
            MembershipRole role, MembershipStatus status) {
        this.organization = organization;
        this.user = user;
        this.role = role;
        this.status = status;
        this.createdAt = Instant.now();
        if (status == MembershipStatus.ACTIVE) {
            this.activatedAt = Instant.now();
        }
    }

    public UUID getId() { return id; }
    public Organization getOrganization() { return organization; }
    public AppUser getUser() { return user; }
    public MembershipRole getRole() { return role; }
    public MembershipStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getActivatedAt() { return activatedAt; }
    public Instant getDisabledAt() { return disabledAt; }

    public void setRole(MembershipRole role) { this.role = role; }

    public void activate() {
        this.status = MembershipStatus.ACTIVE;
        this.activatedAt = Instant.now();
        this.disabledAt = null;
    }

    public void disable() {
        this.status = MembershipStatus.DISABLED;
        this.disabledAt = Instant.now();
    }

    public void setStatusDirect(MembershipStatus status) {
        this.status = status;
        if (status == MembershipStatus.ACTIVE && this.activatedAt == null) {
            this.activatedAt = Instant.now();
        }
        if (status == MembershipStatus.DISABLED) {
            this.disabledAt = Instant.now();
        } else if (status == MembershipStatus.ACTIVE) {
            this.disabledAt = null;
        }
    }
}
