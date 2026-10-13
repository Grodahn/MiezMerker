package org.miezmerker.backend.service;

import java.math.BigInteger;
import java.util.*;
import org.miezmerker.backend.domain.*;
import org.miezmerker.backend.repo.*;
import org.miezmerker.backend.security.TenantService;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import io.swagger.v3.oas.annotations.media.Schema;

@Service
public class TemporalProjectionService {
    public record Projection(UUID observationId,long sequence,@Schema(nullable=true) String incarnation,
            @Schema(nullable=true) Integer bootCounter,@Schema(nullable=true) Long monotonicMs,
            String algorithmVersion,TemporalProjectionEngine.Quality quality,@Schema(nullable=true) Long earliestUtcMs,
            @Schema(nullable=true) Long latestUtcMs,@Schema(nullable=true) Long approximateUtcMs,List<UUID> anchorIds,
            String reason,List<String> uncertaintyPolicies,@Schema(nullable=true) UUID derivedFeedingSiteId,
            @Schema(nullable=true) UUID derivedDeploymentId,String attributionProvenance) {}
    public record Page(String algorithmVersion,List<Projection> items,@Schema(nullable=true) Long nextAfterSequence) {}
    private final TenantService tenants;
    private final NodeRepository nodes;
    private final TimeAnchorRepository anchors;
    private final BootEpochRepository boots;
    private final RawObservationRepository observations;
    private final NodeDeploymentRepository deployments;
    private final JdbcTemplate jdbc;
    public TemporalProjectionService(TenantService tenants,NodeRepository nodes,TimeAnchorRepository anchors,BootEpochRepository boots,
            RawObservationRepository observations,NodeDeploymentRepository deployments,JdbcTemplate jdbc) {
        this.tenants=tenants; this.nodes=nodes; this.anchors=anchors; this.boots=boots;
        this.observations=observations; this.deployments=deployments; this.jdbc=jdbc;
    }
    @Transactional
    public Page read(UUID user,UUID org,UUID nodeId,long after,int limit) {
        if(after<0 || limit<1 || limit>100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid pagination");
        tenants.requireActive(user,org);
        // Serialize with ingest/claiming/deployments: one deterministic evidence snapshot.
        var node=nodes.findByIdLocked(nodeId).orElseThrow(TimeEvidenceService::denied);
        if(node.getState()!=NodeState.CLAIMED || node.getOrganization()==null || !org.equals(node.getOrganization().getId())) throw TimeEvidenceService.denied();
        var rows=observations.temporalPage(org,nodeId,after,PageRequest.of(0,limit+1));
        var views=projectRows(user,org,nodeId,rows.subList(0,Math.min(limit,rows.size())));
        return new Page(TemporalProjectionEngine.VERSION,views,rows.size()>limit?views.getLast().sequence():null);
    }
    /** Reuse the authoritative #132 projection for selected immutable rows, without cursor loss. */
    @Transactional
    public List<Projection> projectRows(UUID user,UUID org,UUID nodeId,List<RawObservation> rows) {
        tenants.requireActive(user,org);
        var node=nodes.findByIdLocked(nodeId).orElseThrow(TimeEvidenceService::denied);
        if(node.getState()!=NodeState.CLAIMED || node.getOrganization()==null || !org.equals(node.getOrganization().getId())) throw TimeEvidenceService.denied();
        if(rows.stream().anyMatch(r->!org.equals(r.getOrganization().getId()) || !nodeId.equals(r.getNode().getNodeId()))) throw TimeEvidenceService.denied();
        var engines=new HashMap<String,TemporalProjectionEngine>();
        var policies=new HashMap<String,List<String>>();
        var history=deployments.temporalHistory(org,nodeId,PageRequest.of(0,4097));
        if(history.size()>4096) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Deployment projection capacity exceeded");
        var views=new ArrayList<Projection>();
        for(var raw:rows) {
            String inc=raw.getIncarnation();
            TemporalProjectionEngine.Result result=new TemporalProjectionEngine.Result(TemporalProjectionEngine.Quality.UNKNOWN,null,null,List.of(),"MISSING_BOOT_EVIDENCE");
            if(inc!=null && raw.getBootCounter()!=null && raw.getBootCounter()>0 && raw.getMonotonicMs()!=null && raw.getMonotonicMs()>=0) {
                try {
                    UUID incarnation=UUID.fromString(inc);
                    if(!engines.containsKey(inc)) {
                        var evidence=anchors.findByOrganizationIdAndNodeIdAndIncarnationOrderByBootCounterAscUptimeMsAscIdAsc(org,nodeId,incarnation,PageRequest.of(0,4097));
                        if(evidence.size()>4096) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Evidence projection capacity exceeded");
                        var verified=new ArrayList<TemporalProjectionEngine.Evidence>();
                        for(var a:evidence) if(a.getDriftPpm()!=null && a.getFingerprint().equals(node.getFingerprint())
                                && node.getClaimedAt()!=null && a.getUtcMs()>=node.getClaimedAt().toEpochMilli())
                            verified.add(new TemporalProjectionEngine.Evidence(a.getAnchorId(),a.getBootCounter(),a.getUptimeMs(),
                                Math.subtractExact(a.getUtcMs(),a.getUncertaintyMinusMs()),Math.addExact(a.getUtcMs(),a.getUncertaintyPlusMs()),a.getDriftPpm(),a.getSamplingMs(),a.getPolicyProvenance()));
                        var ranges=jdbc.query("SELECT boot_counter, MIN(sequence), MAX(sequence), MIN(monotonic_ms), MAX(monotonic_ms) FROM raw_observations WHERE organization_id=? AND node_id=? AND incarnation=? AND boot_counter>0 AND monotonic_ms IS NOT NULL GROUP BY boot_counter ORDER BY boot_counter LIMIT 4097",
                            (rs,n)->new TemporalProjectionEngine.BootRange(rs.getLong(1),rs.getLong(2),rs.getLong(3),BigInteger.valueOf(rs.getLong(4)),BigInteger.valueOf(rs.getLong(5))),org,nodeId,inc);
                        // Windowed predecessor checks cover the complete lifetime, including page boundaries.
                        if(ranges.size()>4096) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Boot projection capacity exceeded");
                        boolean invalid=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM (SELECT boot_counter b, monotonic_ms u, LAG(boot_counter) OVER (ORDER BY sequence) pb, LAG(monotonic_ms) OVER (ORDER BY sequence) pu FROM raw_observations WHERE organization_id=? AND node_id=? AND incarnation=? AND boot_counter>0 AND monotonic_ms IS NOT NULL) ordered_rows WHERE b<pb OR (b=pb AND u<pu))",Boolean.class,org,nodeId,inc));
                        var markers=boots.findByOrganizationIdAndNodeIdAndIncarnationOrderByBootCounter(org,nodeId,incarnation,PageRequest.of(0,4097));
                        if(markers.size()>4096) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Evidence projection capacity exceeded");
                        BigInteger previousSequence=null;
                        for(var b:markers) {
                            if(previousSequence!=null && b.getFirstPossibleSequence().compareTo(previousSequence)<0) invalid=true;
                            previousSequence=b.getFirstPossibleSequence();
                            for(var range:ranges) if(range.boot()==b.getBootCounter()
                                && (BigInteger.valueOf(range.firstSequence()).compareTo(b.getFirstPossibleSequence())<0 || range.firstUptime().compareTo(b.getStartedUptimeMs())<0)) invalid=true;
                        }
                        try { engines.put(inc,new TemporalProjectionEngine(verified,ranges,invalid)); }
                        catch(IllegalStateException e) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Boot anchor projection capacity exceeded"); }
                        policies.put(inc,verified.stream().map(TemporalProjectionEngine.Evidence::policy).distinct().sorted().toList());
                    }
                    result=engines.get(inc).project(raw.getBootCounter(),BigInteger.valueOf(raw.getMonotonicMs()));
                } catch(IllegalArgumentException | ArithmeticException e) {
                    result=new TemporalProjectionEngine.Result(TemporalProjectionEngine.Quality.CONFLICT,null,null,List.of(),"INVALID_OR_UNREPRESENTABLE_EVIDENCE");
                }
            }
            UUID site=null,deployment=null; String attribution="UNRESOLVED";
            if(raw.getFeedingSite()!=null) {
                site=raw.getFeedingSite().getId(); deployment=raw.getDeployment()==null?null:raw.getDeployment().getId(); attribution="FROZEN_RAW";
            } else if(result.quality()!=TemporalProjectionEngine.Quality.CONFLICT && result.earliestUtcMs()!=null && result.latestUtcMs()!=null) {
                var start=java.time.Instant.ofEpochMilli(result.earliestUtcMs()); var end=java.time.Instant.ofEpochMilli(result.latestUtcMs());
                // Exactly one intersecting deployment and full inclusive containment.
                var overlapping=history.stream().filter(d->!d.getValidFrom().isAfter(end)
                    && (d.getValidUntil()==null || d.getValidUntil().isAfter(start))).toList();
                if(overlapping.size()==1 && overlapping.getFirst().covers(start) && overlapping.getFirst().covers(end)) {
                    var d=overlapping.getFirst(); site=d.getFeedingSite().getId(); deployment=d.getId(); attribution="ENTIRE_INTERVAL_IN_HISTORICAL_DEPLOYMENT";
                }
            }
            views.add(new Projection(raw.getId(),raw.getSequence(),inc,raw.getBootCounter(),raw.getMonotonicMs(),TemporalProjectionEngine.VERSION,
                result.quality(),result.earliestUtcMs(),result.latestUtcMs(),null,result.anchorIds(),result.reason(),policies.getOrDefault(inc,List.of()),site,deployment,attribution));
        }
        return List.copyOf(views);
    }
}
