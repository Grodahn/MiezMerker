package org.miezmerker.backend.service;

import java.util.*;
import java.math.BigInteger;
import org.miezmerker.backend.crypto.*;
import org.miezmerker.backend.domain.*;
import org.miezmerker.backend.repo.*;
import org.miezmerker.backend.security.TenantService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TimeEvidenceService {
    public record Receipt(UUID anchorId,String status,int version,String trustStatus,boolean boundedUtc) {}
    public record BootReceipt(String nodeId,String incarnation,long bootCounter,String status,int version,String provenance) {}
    private final TenantService tenants;
    private final NodeRepository nodes;
    private final BootEpochRepository boots;
    private final TimeAnchorRepository anchors;
    private final NodeTimeProofVerifier verifier;
    private final TimeUncertaintyPolicy policy;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    public TimeEvidenceService(TenantService tenants,NodeRepository nodes,BootEpochRepository boots,
            TimeAnchorRepository anchors,NodeTimeProofVerifier verifier,TimeUncertaintyPolicy policy,org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.tenants=tenants; this.nodes=nodes; this.boots=boots; this.anchors=anchors; this.verifier=verifier; this.policy=policy;
        this.jdbc=jdbc;
    }
    @Transactional
    public Receipt ingestPhone(UUID user,TimeEvidenceProtocol.PhoneRequest r) {
        try { TimeEvidenceProtocol.validatePhone(r); }
        catch(IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid phone estimate"); }
        UUID org=UUID.fromString(r.organizationId()),nodeId=UUID.fromString(r.nodeId()),id=UUID.fromString(r.anchorId()),inc=UUID.fromString(r.incarnation());
        tenants.requireActive(user,org); nodes.lockClaims();
        var node=nodes.findByIdLocked(nodeId).orElseThrow(TimeEvidenceService::denied);
        if(node.getState()!=NodeState.CLAIMED || node.getOrganization()==null || !org.equals(node.getOrganization().getId())) throw denied();
        // Owner-reported estimates carry no proof, trust budget or security UTC.
        var old=jdbc.query("SELECT organization_id,node_id,incarnation,boot_counter,uptime_ms,utc_ms,transport_duration_ms FROM phone_time_anchors WHERE anchor_id=?",
            (rs,n)->new TimeEvidenceProtocol.PhoneRequest(rs.getString(1),id.toString(),rs.getString(2),rs.getString(3),rs.getLong(4),rs.getBigDecimal(5).toBigIntegerExact(),rs.getLong(6),rs.getLong(7),"PHONE_ESTIMATE",1),id);
        if(!old.isEmpty()) {
            if(!old.getFirst().equals(r)) throw conflict();
            return new Receipt(id,"DUPLICATE_IDENTICAL",1,"PHONE_ESTIMATE",false);
        }
        jdbc.update("INSERT INTO phone_time_anchors(anchor_id,organization_id,node_id,incarnation,boot_counter,uptime_ms,utc_ms,transport_duration_ms,source,format_version) VALUES (?,?,?,?,?,?,?,?,?,?)",
            id,org,nodeId,inc,r.bootCounter(),new java.math.BigDecimal(r.uptimeMs()),r.utcMs(),r.transportDurationMs(),"PHONE_ESTIMATE",1);
        return new Receipt(id,"CREATED",1,"PHONE_ESTIMATE",false);
    }
    @Transactional
    public BootReceipt ingestBoot(UUID user,TimeEvidenceProtocol.BootRequest request) {
        try { TimeEvidenceProtocol.validateBoot(request.boot()); NodeTimeProtocol.uuid(request.organizationId()); }
        catch(IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid boot evidence"); }
        UUID org=UUID.fromString(request.organizationId()); var b=request.boot();
        tenants.requireActive(user,org); nodes.lockClaims();
        var node=nodes.findByIdLocked(UUID.fromString(b.nodeId())).orElseThrow(TimeEvidenceService::denied);
        if(node.getState()!=NodeState.CLAIMED || node.getOrganization()==null || !org.equals(node.getOrganization().getId())) throw denied();
        var existing=boots.findByOrganizationIdAndNodeIdAndIncarnationAndBootCounter(org,node.getNodeId(),UUID.fromString(b.incarnation()),b.bootCounter()).orElse(null);
        if(existing!=null && !sameBoot(existing,b)) throw conflict();
        if(existing==null) boots.saveAndFlush(new BootEpoch(org,b));
        return new BootReceipt(b.nodeId(),b.incarnation(),b.bootCounter(),existing==null?"CREATED":"DUPLICATE_IDENTICAL",1,"OWNER_UPLOADED_BOOT_V1");
    }
    @Transactional
    public Receipt ingest(UUID user, TimeEvidenceProtocol.Request r) {
        try { TimeEvidenceProtocol.validate(r); }
        catch(IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid time evidence"); }
        UUID org=UUID.fromString(r.organizationId()),nodeId=UUID.fromString(r.boot().nodeId());
        tenants.requireActive(user,org);
        // Claiming/ownership changes and concurrent evidence uploads share existing locking.
        nodes.lockClaims();
        var node=nodes.findByIdLocked(nodeId).orElseThrow(TimeEvidenceService::denied);
        if(node.getState()!=NodeState.CLAIMED || node.getOrganization()==null || !org.equals(node.getOrganization().getId())) throw denied();
        var key=EcKeyUtils.publicFromXY(node.getPublicKeyX(),node.getPublicKeyY());
        long utc; UUID jti;
        try {
            verifier.verifyAcceptance(r.proof(),r.challenge(),r.acceptance(),key);
            var claims=verifier.verify(r.proof(),r.challenge());
            utc=claims.path("utc_ms").asLong(); jti=UUID.fromString(claims.path("jti").asText());
        } catch(IllegalArgumentException e) { throw denied(); }
        if(r.nodeMetadata()!=null) {
            var m=NodeTimeProtocol.json(r.nodeMetadata().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if(m.path("utcMs").asLong()!=utc || !m.path("proofIdOrDigest").asText().equals(jti.toString()))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Metadata proof binding");
        }
        // #18 stores no incarnation/monotonic claim receipt chain. Pre-claim UTC cannot
        // independently prove continuity: reject until that chain is available.
        if(node.getClaimedAt()==null) throw denied();
        if(utc<node.getClaimedAt().toEpochMilli()) throw preClaimContinuityDenied();
        var existing=anchors.findByAnchorIdAndOrganizationIdAndNodeIdAndIncarnationAndBootCounter(UUID.fromString(r.acceptance().anchorId()),org,nodeId,UUID.fromString(r.boot().incarnation()),r.boot().bootCounter()).orElse(null);
        if(existing!=null) {
            if(!Objects.equals(existing.getNodeMetadata(),r.nodeMetadata()) || !existing.getProof().equals(r.proof()) || !existing.getChallengeBytes().equals(r.challengeBytes())
                    || !existing.getAcceptanceBytes().equals(r.acceptanceBytes()) || !sameBoot(boots.findById(existing.getBootEpochId()).orElseThrow(),r.boot()))
                throw conflict();
            return receipt(existing,"DUPLICATE_IDENTICAL");
        }
        if(anchors.existsByProofIdOrProofDigest(jti,r.acceptance().proofDigest())
                || anchors.existsByOrganizationIdAndNodeIdAndIncarnationAndBootCounterAndNonceDigest(org,nodeId,
                    UUID.fromString(r.boot().incarnation()),r.boot().bootCounter(),r.acceptance().challengeNonceDigest())) throw conflict();
        var boot=boots.findByOrganizationIdAndNodeIdAndIncarnationAndBootCounter(org,nodeId,UUID.fromString(r.boot().incarnation()),r.boot().bootCounter()).orElse(null);
        if(boot!=null && !sameBoot(boot,r.boot())) throw conflict();
        if(boot==null) {
            boot=boots.saveAndFlush(new BootEpoch(org,r.boot()));
        }
        TimeAnchor anchor;
        try { anchor=new TimeAnchor(boot,r,utc,jti,policy.budget()); }
        catch(ArithmeticException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unrepresentable evidence bounds"); }
        anchors.saveAndFlush(anchor);
        return receipt(anchor,"CREATED");
    }
    static boolean sameBoot(BootEpoch b,TimeEvidenceProtocol.Boot r) {
        return b.getNodeId().toString().equals(r.nodeId()) && b.getIncarnation().toString().equals(r.incarnation())
            && b.getBootCounter()==r.bootCounter() && b.getResetReason()==r.resetReason()
            && b.getStartedUptimeMs().equals(r.startedUptimeMs()) && b.getFirstPossibleSequence().equals(r.firstPossibleSequence())
            && b.getFormatVersion()==r.formatVersion();
    }
    private static Receipt receipt(TimeAnchor a,String status) {
        return new Receipt(a.getAnchorId(),status,1,a.getTrustStatus(),a.getDriftPpm()!=null);
    }
    static ResponseStatusException denied() { return new ResponseStatusException(HttpStatus.FORBIDDEN,"Time evidence denied"); }
    private static ResponseStatusException preClaimContinuityDenied() {
        // Expose the reason only after owner authorization and signed proof /
        // acceptance verification. Never reveal this hint to foreign callers.
        return new ResponseStatusException(HttpStatus.FORBIDDEN,"Time evidence continuity before claim unverified") {
            @Override public HttpHeaders getHeaders() {
                var headers=new HttpHeaders();
                headers.set("X-MM-Time-Evidence","PRE_CLAIM_CONTINUITY_UNVERIFIED");
                return headers;
            }
        };
    }
    static ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT,"Time evidence conflict"); }
}

