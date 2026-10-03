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
    public Instant getCreatedAt() { return createdAt; }
    public Instant getClaimedAt() { return claimedAt; }

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
