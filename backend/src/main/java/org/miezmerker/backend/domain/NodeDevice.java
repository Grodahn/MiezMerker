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

@Entity
@Table(name = "nodes")
public class NodeDevice {
    /**
     * Maximum stored length for {@link #displayName}. Unicode allowed, no ASCII
     * restriction. 100 characters comfortably fit bowl labels such as
     * "Silberner Napf am Unterstand" while staying short enough for a label.
     */
    public static final int MAX_DISPLAY_NAME_LENGTH = 100;

    @Id
    private UUID nodeId;

    @Column(name = "public_key_x", nullable = false, length = 64)
    private String publicKeyX;

    @Column(name = "public_key_y", nullable = false, length = 64)
    private String publicKeyY;

    @Column(nullable = false, unique = true, length = 64)
    private String fingerprint;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private NodeState state = NodeState.UNCLAIMED;

    @Column(name = "firmware_version", length = 64)
    private String firmwareVersion;

    @Column(name = "protocol_version", length = 32)
    private String protocolVersion;

    @Column(name = "status_note", length = 500)
    private String statusNote;

    /**
     * Optional human-readable bowl label for #50 (e.g. "Der Grüne",
     * "Silberner Napf"). Pure product metadata: never unique, never part of
     * the BLE/crypto identity, never snapshotted into observations or visits.
     * Nullable for pre-#50 rows; blank-only input normalizes to {@code null}.
     */
    @Column(name = "display_name", length = 100)
    private String displayName;

    @Column(name = "last_contact_at")
    private Instant lastContactAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "claimed_at")
    private Instant claimedAt;

    protected NodeDevice() {}

    public NodeDevice(UUID nodeId, String publicKeyX, String publicKeyY, String fingerprint,
            String firmwareVersion) {
        this.nodeId = nodeId;
        this.publicKeyX = publicKeyX;
        this.publicKeyY = publicKeyY;
        this.fingerprint = fingerprint;
        this.firmwareVersion = firmwareVersion;
        this.state = NodeState.UNCLAIMED;
        this.createdAt = Instant.now();
    }

    public UUID getNodeId() { return nodeId; }
    public String getPublicKeyX() { return publicKeyX; }
    public String getPublicKeyY() { return publicKeyY; }
    public String getFingerprint() { return fingerprint; }
    public Organization getOrganization() { return organization; }
    public NodeState getState() { return state; }
    public String getFirmwareVersion() { return firmwareVersion; }
    public String getProtocolVersion() { return protocolVersion; }
    public String getStatusNote() { return statusNote; }
    public String getDisplayName() { return displayName; }
    public Instant getLastContactAt() { return lastContactAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getClaimedAt() { return claimedAt; }

    public void setFirmwareVersion(String firmwareVersion) {
        this.firmwareVersion = firmwareVersion;
    }

    public void setProtocolVersion(String protocolVersion) {
        this.protocolVersion = protocolVersion;
    }

    public void setStatusNote(String statusNote) { this.statusNote = statusNote; }

    /**
     * Stores the trimmed bowl label; blank-only input clears to {@code null}.
     * Length validation happens at the API boundary (400), never by silent
     * truncation. Internal spacing is preserved.
     */
    public void setDisplayName(String displayName) {
        this.displayName = normalizeDisplayName(displayName);
    }

    /**
     * Trims Unicode whitespace at both ends; blank-only input becomes
     * {@code null}. Mirrors the #32 AppUser display-name semantics without
     * coupling the Node aggregate to the user identity.
     */
    public static String normalizeDisplayName(String displayName) {
        if (displayName == null) {
            return null;
        }
        int start = 0;
        int end = displayName.length();
        while (start < end && isNameWhitespace(displayName.codePointAt(start))) {
            start += Character.charCount(displayName.codePointAt(start));
        }
        while (start < end && isNameWhitespace(displayName.codePointBefore(end))) {
            end -= Character.charCount(displayName.codePointBefore(end));
        }
        String trimmed = displayName.substring(start, end);
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean isNameWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    public void touchContact() { this.lastContactAt = Instant.now(); }

    public void claim(Organization organization) {
        this.organization = organization;
        this.state = NodeState.CLAIMED;
        this.claimedAt = Instant.now();
    }

    public void updateDeviceKey(String x, String y, String fingerprint, String firmwareVersion) {
        this.publicKeyX = x;
        this.publicKeyY = y;
        this.fingerprint = fingerprint;
        this.firmwareVersion = firmwareVersion;
    }
}
