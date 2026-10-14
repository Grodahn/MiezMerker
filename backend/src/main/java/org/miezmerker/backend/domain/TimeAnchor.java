package org.miezmerker.backend.domain;

import jakarta.persistence.*;
import java.math.BigInteger;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name="time_anchors")
public class TimeAnchor {
    @Id @Column(name="id", nullable=false)
    private UUID id;
    @Column(name="anchor_id",nullable=false)
    private UUID anchorId;
    @Column(name="organization_id", nullable=false)
    private UUID organizationId;
    @Column(name="node_id", nullable=false)
    private UUID nodeId;
    @Column(name="incarnation", nullable=false)
    private UUID incarnation;
    @Column(name="boot_counter", nullable=false)
    private long bootCounter;
    @Column(name="boot_epoch_id", nullable=false)
    private UUID bootEpochId;
    @Column(name="uptime_ms", nullable=false, precision=20, scale=0)
    private BigInteger uptimeMs;
    @Column(name="challenge_uptime_ms", nullable=false, precision=20, scale=0)
    private BigInteger challengeUptimeMs;
    @Column(name="utc_ms", nullable=false)
    private long utcMs;
    @Column(name="proof_id", nullable=false)
    private UUID proofId;
    @Column(name="proof_digest", nullable=false,length=43)
    private String proofDigest;
    @Column(name="nonce_digest", nullable=false,length=43)
    private String nonceDigest;
    @Column(name="fingerprint", nullable=false,length=64)
    private String fingerprint;
    @Column(name="proof", nullable=false,length=2048)
    private String proof;
    @Column(name="challenge_bytes", nullable=false,length=512)
    private String challengeBytes;
    @Column(name="acceptance_bytes", nullable=false,length=512)
    private String acceptanceBytes;
    @Column(name="node_metadata",length=1024)
    private String nodeMetadata;
    @Column(name="trust_status", nullable=false,length=32)
    private String trustStatus;
    @Column(name="source", nullable=false,length=32)
    private String source;
    @Column(name="uncertainty_minus_ms", nullable=true)
    private Long uncertaintyMinusMs;
    @Column(name="uncertainty_plus_ms", nullable=true)
    private Long uncertaintyPlusMs;
    @Column(name="server_error_ms")
    private Long serverErrorMs;
    @Column(name="sampling_ms")
    private Long samplingMs;
    @Column(name="drift_ppm", nullable=true)
    private Long driftPpm;
    @Column(name="policy_provenance", nullable=true,length=500)
    private String policyProvenance;
    @Column(name="format_version", nullable=false)
    private int formatVersion;
    protected TimeAnchor() {}
    public TimeAnchor(BootEpoch boot, org.miezmerker.backend.service.TimeEvidenceProtocol.Request r, long utc, UUID jti, org.miezmerker.backend.service.TimeUncertaintyPolicy.Budget budget) {
        var c=r.challenge(); var a=r.acceptance();
        id=UUID.randomUUID(); anchorId=UUID.fromString(a.anchorId()); organizationId=boot.getOrganizationId(); nodeId=boot.getNodeId();
        incarnation=boot.getIncarnation(); bootCounter=boot.getBootCounter(); bootEpochId=boot.getId();
        uptimeMs=a.acceptedUptimeMs(); challengeUptimeMs=c.challengeUptimeMs(); utcMs=utc; proofId=jti;
        proofDigest=a.proofDigest(); nonceDigest=a.challengeNonceDigest(); fingerprint=c.devicePublicKeyFingerprint();
        proof=r.proof(); challengeBytes=r.challengeBytes(); acceptanceBytes=r.acceptanceBytes();
        nodeMetadata=r.nodeMetadata();
        source="BACKEND_SIGNED"; trustStatus="DEVICE_ACCEPTANCE_VERIFIED"; formatVersion=1;
        if(budget != null) {
            serverErrorMs=budget.serverErrorMs(); samplingMs=budget.samplingMs();
            uncertaintyMinusMs=Math.addExact(budget.serverErrorMs(),budget.samplingMs());
            uncertaintyPlusMs=Math.addExact(uncertaintyMinusMs,uptimeMs.subtract(challengeUptimeMs).longValueExact());
            driftPpm=budget.driftPpm(); policyProvenance=budget.provenance();
            Math.subtractExact(utcMs,uncertaintyMinusMs);
            Math.addExact(utcMs,uncertaintyPlusMs);
        }
    }
    public UUID getId() { return id; }
    public UUID getAnchorId() { return anchorId; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getNodeId() { return nodeId; }
    public UUID getIncarnation() { return incarnation; }
    public long getBootCounter() { return bootCounter; }
    public UUID getBootEpochId() { return bootEpochId; }
    public BigInteger getUptimeMs() { return uptimeMs; }
    public BigInteger getChallengeUptimeMs() { return challengeUptimeMs; }
    public long getUtcMs() { return utcMs; }
    public UUID getProofId() { return proofId; }
    public String getProofDigest() { return proofDigest; }
    public String getNonceDigest() { return nonceDigest; }
    public String getFingerprint() { return fingerprint; }
    public String getProof() { return proof; }
    public String getChallengeBytes() { return challengeBytes; }
    public String getAcceptanceBytes() { return acceptanceBytes; }
    public String getNodeMetadata() { return nodeMetadata; }
    public String getTrustStatus() { return trustStatus; }
    public String getSource() { return source; }
    public Long getUncertaintyMinusMs() { return uncertaintyMinusMs; }
    public Long getUncertaintyPlusMs() { return uncertaintyPlusMs; }
    public Long getServerErrorMs() { return serverErrorMs; }
    public Long getSamplingMs() { return samplingMs; }
    public Long getDriftPpm() { return driftPpm; }
    public String getPolicyProvenance() { return policyProvenance; }
    public int getFormatVersion() { return formatVersion; }
}

