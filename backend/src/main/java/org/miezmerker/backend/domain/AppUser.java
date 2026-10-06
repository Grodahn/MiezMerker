package org.miezmerker.backend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "app_users")
public class AppUser {
    /** Maximum stored length for {@link #displayName}. Unicode allowed, no ASCII restriction. */
    public static final int MAX_DISPLAY_NAME_LENGTH = 255;

    @Id
    @UuidGenerator
    private UUID id;

    @Column(nullable = false, unique = true, length = 320)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    /**
     * Human-readable display name for #32. Global to the user identity, not per
     * organization: the same person shows the same name in every membership.
     * Nullable for pre-#32 rows; UI falls back to email until a name is set.
     * Never derived from the email prefix.
     */
    @Column(name = "display_name", length = 255)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private UserStatus status = UserStatus.ACTIVE;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    protected AppUser() {}

    public AppUser(String email, String passwordHash) {
        this(email, passwordHash, null);
    }

    public AppUser(String email, String passwordHash, String displayName) {
        this.email = normalizeEmail(email);
        this.passwordHash = passwordHash;
        this.displayName = normalizeDisplayName(displayName);
        this.status = UserStatus.ACTIVE;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getDisplayName() { return displayName; }
    public String getPasswordHash() { return passwordHash; }
    public UserStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastLoginAt() { return lastLoginAt; }

    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public void setStatus(UserStatus status) { this.status = status; }
    public void setLastLoginAt(Instant lastLoginAt) { this.lastLoginAt = lastLoginAt; }

    /**
     * Stores the trimmed name; blank-only input clears to {@code null}.
     * Length validation happens at the API/bootstrap boundary (400 / startup failure),
     * never by silent truncation.
     */
    public void setDisplayName(String displayName) {
        this.displayName = normalizeDisplayName(displayName);
    }

    public static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    public static String normalizeDisplayName(String displayName) {
        if (displayName == null) {
            return null;
        }
        String trimmed = displayName.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
