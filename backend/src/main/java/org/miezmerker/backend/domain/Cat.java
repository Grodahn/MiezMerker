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
@Table(name = "cats",
        uniqueConstraints = @UniqueConstraint(columnNames = {"organization_id", "chip_id"}))
public class Cat {
    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(name = "chip_id", nullable = false, length = 64)
    private String chipId;

    @Column(length = 255)
    private String name;

    @Column(length = 64)
    private String status;

    @Column(length = 2000)
    private String notes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Cat() {}

    public Cat(Organization organization, String chipId, String name, String status,
            String notes) {
        this.organization = organization;
        this.chipId = normalizeChipId(chipId);
        this.name = name;
        this.status = status;
        this.notes = notes;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public static String normalizeChipId(String chipId) {
        if (chipId == null) {
            return null;
        }
        return chipId.trim().toUpperCase(Locale.ROOT);
    }

    public UUID getId() { return id; }
    public Organization getOrganization() { return organization; }
    public String getChipId() { return chipId; }
    public String getName() { return name; }
    public String getStatus() { return status; }
    public String getNotes() { return notes; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setChipId(String chipId) { this.chipId = normalizeChipId(chipId); touch(); }
    public void setName(String name) { this.name = name; touch(); }
    public void setStatus(String status) { this.status = status; touch(); }
    public void setNotes(String notes) { this.notes = notes; touch(); }

    private void touch() { this.updatedAt = Instant.now(); }
}
