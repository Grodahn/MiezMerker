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
@Table(name = "feeding_sites")
public class FeedingSite {
    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(length = 2000)
    private String description;

    @Column(name = "location_lat")
    private Double locationLat;

    @Column(name = "location_lng")
    private Double locationLng;

    @Column(name = "location_label", length = 255)
    private String locationLabel;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected FeedingSite() {}

    public FeedingSite(Organization organization, String name, String description,
            Double locationLat, Double locationLng, String locationLabel) {
        this.organization = organization;
        this.name = name;
        this.description = description;
        this.locationLat = locationLat;
        this.locationLng = locationLng;
        this.locationLabel = locationLabel;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public Organization getOrganization() { return organization; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public Double getLocationLat() { return locationLat; }
    public Double getLocationLng() { return locationLng; }
    public String getLocationLabel() { return locationLabel; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setName(String name) { this.name = name; touch(); }
    public void setDescription(String description) { this.description = description; touch(); }
    public void setLocation(Double lat, Double lng, String label) {
        this.locationLat = lat;
        this.locationLng = lng;
        this.locationLabel = label;
        touch();
    }

    private void touch() { this.updatedAt = Instant.now(); }
}
