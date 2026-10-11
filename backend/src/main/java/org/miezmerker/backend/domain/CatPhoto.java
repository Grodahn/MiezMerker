package org.miezmerker.backend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Organization-local cat profile photo metadata (#95). One row per
 * org-local {@link Cat}; the same chip in two organizations keeps two
 * independent rows. The {@code storageKey} is a random, non-guessable
 * filename inside the configured storage directory and is never derived
 * from uploaded filenames, chip ids or cat ids.
 */
@Entity
@Table(name = "cat_photos")
public class CatPhoto {
    @Id
    @Column(name = "cat_id", nullable = false)
    private UUID catId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cat_id", nullable = false)
    private Cat cat;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(name = "storage_key", nullable = false, length = 64, unique = true)
    private String storageKey;

    @Column(name = "content_type", nullable = false, length = 32)
    private String contentType = "image/jpeg";

    @Column(name = "byte_size", nullable = false)
    private int byteSize;

    @Column(nullable = false)
    private int width;

    @Column(nullable = false)
    private int height;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected CatPhoto() {}

    public CatPhoto(Cat cat, Organization organization, String storageKey,
            String contentType, int byteSize, int width, int height) {
        this.cat = cat;
        this.organization = organization;
        this.storageKey = storageKey;
        this.contentType = contentType;
        this.byteSize = byteSize;
        this.width = width;
        this.height = height;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public UUID getCatId() { return catId; }
    public Cat getCat() { return cat; }
    public Organization getOrganization() { return organization; }
    public String getStorageKey() { return storageKey; }
    public String getContentType() { return contentType; }
    public int getByteSize() { return byteSize; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    /** Replacement keeps the same logical row and refreshes the physical key. */
    public void replace(String storageKey, String contentType, int byteSize,
            int width, int height) {
        this.storageKey = storageKey;
        this.contentType = contentType;
        this.byteSize = byteSize;
        this.width = width;
        this.height = height;
        this.updatedAt = Instant.now();
    }
}
