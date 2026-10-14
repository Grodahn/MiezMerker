package org.miezmerker.backend.service;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigInteger;
import java.util.*;
import org.miezmerker.backend.domain.*;
import org.miezmerker.backend.repo.NodeRepository;
import org.miezmerker.backend.security.TenantService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Interval visits are separate rebuildable projections: never exact DerivedVisit rows. */
@Service
public class TemporalVisitService {
    public static final String VERSION = "visit-interval-v2";
    public static final int GAP_SECONDS = 60;
    private static final int CAPACITY = 4096;
    @PersistenceContext private EntityManager entities;
    private final TenantService tenants;
    private final TemporalProjectionService projections;
    private final NodeRepository nodes;
    public TemporalVisitService(TenantService tenants, TemporalProjectionService projections, NodeRepository nodes) {
        this.tenants=tenants; this.projections=projections; this.nodes=nodes;
    }
    @Schema(name="TemporalVisitTime")
    public record Time(UUID observationId,
            @Schema(type="string",pattern="^[0-9]+$") @JsonFormat(shape=JsonFormat.Shape.STRING) long sequence,
            @Schema(nullable=true) String incarnation, @Schema(nullable=true) Integer bootCounter,
            @Schema(type="string",nullable=true,pattern="^[0-9]+$") @JsonFormat(shape=JsonFormat.Shape.STRING) Long monotonicMs,
            String algorithmVersion, TemporalProjectionEngine.Quality quality,
            @Schema(type="string",nullable=true,pattern="^-?[0-9]+$") @JsonFormat(shape=JsonFormat.Shape.STRING) Long earliestUtcMs,
            @Schema(type="string",nullable=true,pattern="^-?[0-9]+$") @JsonFormat(shape=JsonFormat.Shape.STRING) Long latestUtcMs,
            List<UUID> anchorIds, String reason, List<String> uncertaintyPolicies,
            @Schema(nullable=true) UUID derivedFeedingSiteId, @Schema(nullable=true) UUID derivedDeploymentId,
            String attributionProvenance) {
        static Time from(TemporalProjectionService.Projection p) {
            return new Time(p.observationId(),p.sequence(),p.incarnation(),p.bootCounter(),p.monotonicMs(),
                p.algorithmVersion(),p.quality(),p.earliestUtcMs(),p.latestUtcMs(),p.anchorIds(),p.reason(),
                p.uncertaintyPolicies(),p.derivedFeedingSiteId(),p.derivedDeploymentId(),p.attributionProvenance());
        }
    }
    @Schema(name="TemporalVisitRead")
    public record Read(UUID nodeId, String chipId, Time time) {
        public Read(UUID nodeId,String chipId,TemporalProjectionService.Projection p) { this(nodeId,chipId,Time.from(p)); }
    }
    @Schema(name="TemporalVisitItem")
    public record Item(String id, String kind, String chipId, @Schema(nullable=true) UUID feedingSiteId,
            String quality, @Schema(type="string",nullable=true,pattern="^-?[0-9]+$") @JsonFormat(shape=JsonFormat.Shape.STRING) Long earliestUtcMs,
            @Schema(type="string",nullable=true,pattern="^-?[0-9]+$") @JsonFormat(shape=JsonFormat.Shape.STRING) Long latestUtcMs,
            int observationCount, boolean ambiguousGap, List<Read> observations) {}
    @Schema(name="TemporalVisitPage")
    public record Page(String algorithmVersion, String timeProjectionVersion, int gapSeconds,
            List<Item> items, @Schema(nullable=true) Integer nextOffset) {}

    @Transactional
    public Page read(UUID user, UUID org, String chip, int offset, int limit) {
        tenants.requireActive(user,org);
        if(chip==null || chip.isBlank() || chip.length()>64 || offset<0 || limit<1 || limit>100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Chip and valid pagination required");
        String normalized=Cat.normalizeChipId(chip);
        // Lock contributing nodes in stable order before the complete chip/evidence snapshot.
        var nodeIds=entities.createQuery("select distinct o.node.nodeId from RawObservation o where o.organization.id=:org and o.chipId=:chip order by o.node.nodeId",UUID.class)
            .setParameter("org",org).setParameter("chip",normalized).setMaxResults(CAPACITY+1).getResultList();
        if(nodeIds.size()>CAPACITY) capacity();
        for(var id:nodeIds) nodes.findByIdLocked(id).orElseThrow(TimeEvidenceService::denied);
        var rows=entities.createQuery("select o from RawObservation o join fetch o.node left join fetch o.organization left join fetch o.feedingSite left join fetch o.deployment where o.organization.id=:org and o.chipId=:chip order by o.node.nodeId,o.sequence,o.id",RawObservation.class)
            .setParameter("org",org).setParameter("chip",normalized).setMaxResults(CAPACITY+1).getResultList();
        if(rows.size()>CAPACITY) capacity();
        var locked=new HashSet<>(nodeIds);
        if(rows.stream().anyMatch(r->!locked.contains(r.getNode().getNodeId())))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Observation snapshot changed; retry");
        var byNode=new LinkedHashMap<UUID,List<RawObservation>>();
        for(var raw:rows) byNode.computeIfAbsent(raw.getNode().getNodeId(),k->new ArrayList<>()).add(raw);
        var reads=new ArrayList<Read>();
        for(var entry:byNode.entrySet()) {
            var projected=projections.projectRows(user,org,entry.getKey(),entry.getValue());
            for(var time:projected) reads.add(new Read(entry.getKey(),normalized,time));
        }
        var items=aggregate(reads);
        int start=Math.min(offset,items.size()), end=start+Math.min(limit,items.size()-start);
        return new Page(VERSION,TemporalProjectionEngine.VERSION,GAP_SECONDS,List.copyOf(items.subList(start,end)),end<items.size()?end:null);
    }
    private static void capacity() { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Temporal visit capacity exceeded; narrow by chip"); }

    /** Conservative independent interval decisions, without assuming correlated oscillator errors. */
    static List<Item> aggregate(List<Read> input) {
        var reads=new ArrayList<>(input);
        reads.sort(Comparator.comparing((Read r)->r.nodeId().toString())
            .thenComparing(r->Objects.toString(r.time().incarnation(),""))
            .thenComparing(r->r.time().bootCounter(),Comparator.nullsFirst(Integer::compareTo))
            .thenComparingLong(r->r.time().sequence())
            .thenComparing(r->r.time().observationId().toString()));
        boolean[] ambiguous=new boolean[reads.size()];
        boolean[] merge=new boolean[reads.size()];
        for(int i=1;i<reads.size();i++) {
            var a=reads.get(i-1); var b=reads.get(i);
            if(!sameGroup(a,b) || !eligible(a) || !eligible(b)) continue;
            BigInteger lower=BigInteger.valueOf(b.time().earliestUtcMs()).subtract(BigInteger.valueOf(a.time().latestUtcMs()));
            BigInteger upper=BigInteger.valueOf(b.time().latestUtcMs()).subtract(BigInteger.valueOf(a.time().earliestUtcMs()));
            if(lower.signum()>=0 && upper.compareTo(BigInteger.valueOf(GAP_SECONDS*1000L))<=0) merge[i]=true;
            else if(lower.compareTo(BigInteger.valueOf(GAP_SECONDS*1000L))<=0) ambiguous[i-1]=ambiguous[i]=true;
        }
        // If one boundary is ambiguous, every read connected by a proven merge
        // remains a candidate: otherwise its neighbor would be falsely split off.
        for(int i=1;i<reads.size();i++) if(merge[i] && ambiguous[i-1]) ambiguous[i]=true;
        for(int i=reads.size()-1;i>0;i--) if(merge[i] && ambiguous[i]) ambiguous[i-1]=true;
        var result=new ArrayList<Item>();
        for(int i=0;i<reads.size();) {
            int end=i+1;
            // Ambiguous candidates cannot indirectly become guaranteed visits via another neighbor.
            while(end<reads.size() && merge[end] && !ambiguous[end-1] && !ambiguous[end]) end++;
            var group=List.copyOf(reads.subList(i,end)); var first=group.getFirst();
            boolean visit=eligible(first) && !ambiguous[i];
            Long earliest=group.stream().map(r->r.time().earliestUtcMs()).filter(Objects::nonNull).min(Long::compareTo).orElse(null);
            Long latest=group.stream().map(r->r.time().latestUtcMs()).filter(Objects::nonNull).max(Long::compareTo).orElse(null);
            result.add(new Item(first.time().observationId().toString(),visit?"VISIT":"OBSERVATION",first.chipId(),first.time().derivedFeedingSiteId(),
                (visit && group.stream().anyMatch(r->r.time().quality()==TemporalProjectionEngine.Quality.RECONSTRUCTED) ? "RECONSTRUCTED" : first.time().quality().name()),earliest,latest,group.size(),ambiguous[i],group));
            i=end;
        }
        // Unknown times retain technical ordering; receipt time is never a sighting-time surrogate.
        return List.copyOf(result);
    }
    private static boolean eligible(Read r) {
        var p=r.time();
        return (p.quality()==TemporalProjectionEngine.Quality.RECONSTRUCTED || p.quality()==TemporalProjectionEngine.Quality.VERIFIED
            || (p.quality()==TemporalProjectionEngine.Quality.ESTIMATED && p.uncertaintyPolicies().contains("PHONE_ESTIMATE")))
            && p.incarnation()!=null && p.bootCounter()!=null && p.bootCounter()>0 && p.monotonicMs()!=null
            && p.earliestUtcMs()!=null && p.latestUtcMs()!=null && p.earliestUtcMs()>0
            && p.latestUtcMs()>=p.earliestUtcMs() && p.derivedFeedingSiteId()!=null;
    }
    private static boolean sameGroup(Read a,Read b) {
        return a.nodeId().equals(b.nodeId()) && a.chipId().equals(b.chipId())
            && a.time().incarnation()!=null && a.time().incarnation().equals(b.time().incarnation())
            && a.time().bootCounter()!=null && a.time().bootCounter().equals(b.time().bootCounter())
            && Objects.equals(a.time().derivedFeedingSiteId(),b.time().derivedFeedingSiteId());
    }
}
