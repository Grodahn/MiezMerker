package org.miezmerker.backend.domain;

import jakarta.persistence.*;
import java.math.BigInteger;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name="boot_epochs")
public class BootEpoch {
    @Id @Column(name="id", nullable=false)
    private UUID id;
    @Column(name="organization_id", nullable=false)
    private UUID organizationId;
    @Column(name="node_id", nullable=false)
    private UUID nodeId;
    @Column(name="incarnation", nullable=false)
    private UUID incarnation;
    @Column(name="boot_counter", nullable=false)
    private long bootCounter;
    @Column(name="reset_reason", nullable=false)
    private long resetReason;
    @Column(name="started_uptime_ms", nullable=false, precision=20, scale=0)
    private BigInteger startedUptimeMs;
    @Column(name="first_possible_sequence", nullable=false, precision=20, scale=0)
    private BigInteger firstPossibleSequence;
    @Column(name="format_version", nullable=false)
    private int formatVersion;
    @Column(name="provenance", nullable=false, length=64)
    private String provenance;
    protected BootEpoch() {}
    public BootEpoch(UUID org, org.miezmerker.backend.service.TimeEvidenceProtocol.Boot b) {
        id=UUID.randomUUID(); organizationId=org; nodeId=UUID.fromString(b.nodeId()); incarnation=UUID.fromString(b.incarnation());
        bootCounter=b.bootCounter(); resetReason=b.resetReason(); startedUptimeMs=b.startedUptimeMs();
        firstPossibleSequence=b.firstPossibleSequence(); formatVersion=b.formatVersion(); provenance="OWNER_UPLOADED_BOOT_V1";
    }
    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getNodeId() { return nodeId; }
    public UUID getIncarnation() { return incarnation; }
    public long getBootCounter() { return bootCounter; }
    public long getResetReason() { return resetReason; }
    public BigInteger getStartedUptimeMs() { return startedUptimeMs; }
    public BigInteger getFirstPossibleSequence() { return firstPossibleSequence; }
    public int getFormatVersion() { return formatVersion; }
    public String getProvenance() { return provenance; }
}

