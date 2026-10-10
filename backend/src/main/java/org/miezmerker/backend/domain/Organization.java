package org.miezmerker.backend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "organizations", uniqueConstraints = @UniqueConstraint(columnNames = "slug"))
public class Organization {
    @Id
    @UuidGenerator
    private UUID id;

    @Column(nullable = false, length = 64)
    private String slug;

    @Column(name = "display_name", nullable = false, length = 255)
    private String displayName;

    @Column(name = "public_contact", length = 500)
    private String publicContact;

    /**
     * Discoverability (ADR 0016): default-deny, separate from data sharing.
     * Only a discoverable ACTIVE organization appears in the authenticated
     * directory and may be selected as a sharing recipient; the flag alone
     * never authorizes reading cats, visits, observations or feeding sites.
     */
    @Column(nullable = false)
    private boolean discoverable;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OrganizationStatus status = OrganizationStatus.ACTIVE;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Organization() {}

    public Organization(String slug, String displayName, String publicContact) {
        this.slug = slug;
        this.displayName = displayName;
        this.publicContact = publicContact;
        this.status = OrganizationStatus.ACTIVE;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getSlug() { return slug; }
    public String getDisplayName() { return displayName; }
    public String getPublicContact() { return publicContact; }
    public boolean isDiscoverable() { return discoverable; }
    public OrganizationStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }

    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public void setPublicContact(String publicContact) { this.publicContact = publicContact; }
    public void setDiscoverable(boolean discoverable) { this.discoverable = discoverable; }
    public void setStatus(OrganizationStatus status) { this.status = status; }
}
