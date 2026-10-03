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
@Table(name = "app_devices")
public class AppDevice {
    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(name = "public_key_x", nullable = false, length = 64)
    private String publicKeyX;

    @Column(name = "public_key_y", nullable = false, length = 64)
    private String publicKeyY;

    @Column(nullable = false, unique = true, length = 64)
    private String fingerprint;

    @Column(length = 120)
    private String label;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt = Instant.now();

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected AppDevice() {}

    public AppDevice(AppUser user, String publicKeyX, String publicKeyY, String fingerprint, String label) {
        this.user = user;
        this.publicKeyX = publicKeyX;
        this.publicKeyY = publicKeyY;
        this.fingerprint = fingerprint;
        this.label = label;
        this.createdAt = Instant.now();
        this.lastSeenAt = Instant.now();
    }

    public UUID getId() { return id; }
    public AppUser getUser() { return user; }
    public String getPublicKeyX() { return publicKeyX; }
    public String getPublicKeyY() { return publicKeyY; }
    public String getFingerprint() { return fingerprint; }
    public String getLabel() { return label; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public Instant getRevokedAt() { return revokedAt; }

    public boolean isRevoked() { return revokedAt != null; }

    public void touch() { this.lastSeenAt = Instant.now(); }

    public void revoke() {
        if (this.revokedAt == null) {
            this.revokedAt = Instant.now();
        }
    }
}
