package org.miezmerker.backend.service;

import java.math.BigInteger;
import java.util.*;
import static org.miezmerker.backend.service.TemporalMath.*;

/** Stateless, reproducible projection; never accesses or modifies raw persistence. */
public final class TemporalProjectionEngine {
    public static final String VERSION="boot-interval-v2";
    public enum Quality { VERIFIED, RECONSTRUCTED, BOUNDED, ESTIMATED, UNKNOWN, CONFLICT }
    public record Evidence(UUID anchorId,long boot,BigInteger uptime,long lower,long upper,long ppm,long samplingMs,String policy,boolean phoneEstimate) {
        public Evidence(UUID anchorId,long boot,BigInteger uptime,long lower,long upper,long ppm,long samplingMs,String policy) {
            this(anchorId,boot,uptime,lower,upper,ppm,samplingMs,policy,false);
        }
        public Evidence(UUID anchorId,long boot,BigInteger uptime,long lower,long upper,long ppm,String policy) {
            this(anchorId,boot,uptime,lower,upper,ppm,0,policy);
        }
        public static Evidence phone(UUID id,long boot,BigInteger uptime,long utc) {
            return new Evidence(id,boot,uptime,utc,utc,0,0,"PHONE_ESTIMATE",true);
        }
    }
    public record BootRange(long boot,long firstSequence,long lastSequence,BigInteger firstUptime,BigInteger lastUptime) {}
    public record Result(Quality quality,Long earliestUtcMs,Long latestUtcMs,List<UUID> anchorIds,String reason) {}
    private record Timeline(BigInteger first,BigInteger last,Interval firstBounds,Interval lastBounds) {}
    private record PhoneOffsets(BigInteger lower,BigInteger upper) {}
    private final Map<Long,List<Evidence>> byBoot=new TreeMap<>();
    private final Map<Long,PhoneOffsets> phoneOffsets=new HashMap<>();
    private final NavigableMap<Long,Timeline> timelines=new TreeMap<>();
    private final Map<Long,Long> lowerFirst=new HashMap<>(),upperLast=new HashMap<>();
    private final List<UUID> anchorIds;
    private final long ppm;
    private final BigInteger relativeSampling;
    private final boolean conflict;

    public TemporalProjectionEngine(List<Evidence> evidence,List<BootRange> boots,boolean invalidChronology) {
        for(var a:evidence) byBoot.computeIfAbsent(a.boot(),k->new ArrayList<>()).add(a);
        // Phone time is the business authority where available. Signed evidence
        // keeps its original projection rules for boots without phone estimates.
        byBoot.replaceAll((boot,items)->items.stream().anyMatch(Evidence::phoneEstimate)
            ?items.stream().filter(Evidence::phoneEstimate).toList():items);
        if(byBoot.values().stream().anyMatch(a->a.size()>64 && !a.getFirst().phoneEstimate())) throw new IllegalStateException("Boot anchor capacity exceeded");
        var activeEvidence=byBoot.values().stream().flatMap(Collection::stream).toList();
        anchorIds=activeEvidence.stream().map(Evidence::anchorId).sorted().toList();
        // Persisted policies must attest this Node oscillator over its lifetime/all modes.
        ppm=activeEvidence.stream().mapToLong(Evidence::ppm).max().orElse(0);
        relativeSampling=BigInteger.valueOf(activeEvidence.stream().mapToLong(Evidence::samplingMs).max().orElse(0)).multiply(BigInteger.TWO);
        boolean bad=invalidChronology;
        for(var items:byBoot.values()) {
            var ordered=items.stream().filter(Evidence::phoneEstimate).sorted(Comparator.comparing(Evidence::uptime)).toList();
            if(!ordered.isEmpty()) {
                BigInteger lower=null,upper=null;
                for(var a:ordered) {
                    var offset=BigInteger.valueOf(a.lower()).subtract(a.uptime());
                    lower=lower==null?offset:lower.min(offset); upper=upper==null?offset:upper.max(offset);
                }
                phoneOffsets.put(ordered.getFirst().boot(),new PhoneOffsets(lower,upper));
            }
            BigInteger currentUptime=null;
            Long precedingMaxUtc=null,currentMaxUtc=null;
            for(var a:ordered) {
                if(!a.uptime().equals(currentUptime)) {
                    precedingMaxUtc=max(precedingMaxUtc,currentMaxUtc);
                    currentUptime=a.uptime(); currentMaxUtc=null;
                }
                // All anchors at an earlier uptime matter. A tied uptime's
                // arbitrary UUID/input ordering must not hide a contradiction.
                if(precedingMaxUtc!=null && a.lower()<precedingMaxUtc) bad=true;
                currentMaxUtc=max(currentMaxUtc,a.lower());
            }
        }
        try {
            var rawRanges=new TreeMap<Long,BootRange>();
            for(var b:boots) rawRanges.put(b.boot(),b);
            var allBoots=new TreeSet<Long>(byBoot.keySet()); allBoots.addAll(rawRanges.keySet());
            for(long boot:allBoots) {
                var raw=rawRanges.get(boot);
                BigInteger first=raw==null?null:raw.firstUptime(),last=raw==null?null:raw.lastUptime();
                for(var a:byBoot.getOrDefault(boot,List.of())) {
                    first=first==null?a.uptime():first.min(a.uptime());
                    last=last==null?a.uptime():last.max(a.uptime());
                    // Compare all constraints at a common acceptance reference.
                    if(interval(boot,a.uptime()).conflict()) bad=true;
                }
                var firstBounds=interval(boot,first); var lastBounds=interval(boot,last);
                if(firstBounds.conflict() || lastBounds.conflict()) bad=true;
                timelines.put(boot,new Timeline(first,last,firstBounds,lastBounds));
            }
            // Forward and backward constraint propagation accumulates minimum powered-on
            // duration across every intervening boot, never shutdown time or cross-boot uptime.
            Long precedingLast=null;
            for(var entry:timelines.entrySet()) {
                var t=entry.getValue();
                Long lower=max(precedingLast,t.firstBounds().earliest());
                if(lower!=null) {
                    lowerFirst.put(entry.getKey(),lower);
                    if(t.firstBounds().latest()!=null && lower>t.firstBounds().latest()) bad=true;
                    precedingLast=max(shift(lower,minElapsed(t.last().subtract(t.first()))),t.lastBounds().earliest());
                    if(t.lastBounds().latest()!=null && precedingLast>t.lastBounds().latest()) bad=true;
                }
            }
            Long followingFirst=null;
            for(var entry:timelines.descendingMap().entrySet()) {
                var t=entry.getValue();
                Long upper=min(followingFirst,t.lastBounds().latest());
                if(upper!=null) {
                    upperLast.put(entry.getKey(),upper);
                    followingFirst=min(shift(upper,minElapsed(t.last().subtract(t.first())).negate()),t.firstBounds().latest());
                    Long lower=lowerFirst.get(entry.getKey());
                    if(lower!=null && lower>followingFirst) bad=true;
                }
            }
        } catch(ArithmeticException | IllegalArgumentException e) { bad=true; }
        conflict=bad;
    }
    private static Long max(Long a,Long b) { if(a==null) return b; if(b==null) return a; return Math.max(a,b); }
    private static Long min(Long a,Long b) { if(a==null) return b; if(b==null) return a; return Math.min(a,b); }
    private static long shift(long utc,BigInteger duration) { return BigInteger.valueOf(utc).add(duration).longValueExact(); }
    private BigInteger minElapsed(BigInteger duration) {
        if(duration.signum()<0) throw new IllegalArgumentException("Invalid boot range");
        BigInteger drift=duration.multiply(BigInteger.valueOf(ppm)).add(BigInteger.valueOf(999999)).divide(BigInteger.valueOf(1000000));
        return duration.subtract(drift).subtract(relativeSampling).max(BigInteger.ZERO);
    }
    private BigInteger relativeShift(BigInteger delta,boolean upper) {
        BigInteger error=delta.abs().multiply(BigInteger.valueOf(ppm)).add(BigInteger.valueOf(999999))
                .divide(BigInteger.valueOf(1000000)).add(relativeSampling);
        return upper?delta.add(error):delta.subtract(error);
    }
    private Interval interval(long boot,BigInteger uptime) {
        var phone=phoneOffsets.get(boot);
        // Constant-time envelope of all phone offsets, even after years of syncs.
        // No favored anchor, drift budget, tolerance or precision threshold.
        if(phone!=null) return new Interval(phone.lower().add(uptime).longValueExact(),phone.upper().add(uptime).longValueExact());
        var result=new Interval(null,null);
        for(var a:byBoot.getOrDefault(boot,List.of())) {
            var projected=propagate(a.lower(),a.upper(),a.uptime(),uptime,a.ppm());
            result=result.intersect(projected);
        }
        return result;
    }
    public Result project(long boot,BigInteger uptime) {
        if(conflict) return new Result(Quality.CONFLICT,null,null,anchorIds,"INCONSISTENT_EVIDENCE_OR_CHRONOLOGY");
        try {
            var own=interval(boot,uptime);
            var timeline=timelines.get(boot);
            Long lower=null,upper=null;
            if(timeline!=null) {
                Long first=lowerFirst.get(boot),last=upperLast.get(boot);
                if(first!=null) lower=shift(first,uptime.compareTo(timeline.first())>=0
                    ?minElapsed(uptime.subtract(timeline.first())):relativeShift(uptime.subtract(timeline.first()),false));
                if(last!=null) upper=shift(last,uptime.compareTo(timeline.last())<=0
                    ?minElapsed(timeline.last().subtract(uptime)).negate():relativeShift(uptime.subtract(timeline.last()),true));
            } else {
                // Unknown boot with no raw range: only adjacent established chronology.
                var previous=timelines.lowerEntry(boot); var next=timelines.higherEntry(boot);
                if(previous!=null && lowerFirst.containsKey(previous.getKey()))
                    lower=shift(lowerFirst.get(previous.getKey()),minElapsed(previous.getValue().last().subtract(previous.getValue().first())));
                if(next!=null && upperLast.containsKey(next.getKey()))
                    upper=shift(upperLast.get(next.getKey()),minElapsed(next.getValue().last().subtract(next.getValue().first())).negate());
            }
            var bounds=own.intersect(new Interval(lower,upper));
            if(bounds.conflict()) return new Result(Quality.CONFLICT,null,null,anchorIds,"EMPTY_INTERVAL");
            boolean phone=byBoot.getOrDefault(boot,List.of()).stream().anyMatch(Evidence::phoneEstimate);
            Quality quality=own.earliest()!=null?(phone?Quality.ESTIMATED:Quality.RECONSTRUCTED)
                :bounds.earliest()!=null && bounds.latest()!=null?Quality.BOUNDED:Quality.UNKNOWN;
            return new Result(quality,bounds.earliest(),bounds.latest(),anchorIds,
                phone?"PHONE_ESTIMATE_SPAN":quality==Quality.UNKNOWN?"MISSING_ABSOLUTE_BOUND":quality==Quality.BOUNDED?"BOOT_ORDER_AND_MINIMUM_ELAPSED":"VERIFIED_ACCEPTANCE_AND_DRIFT");
        } catch(ArithmeticException | IllegalArgumentException e) {
            return new Result(Quality.CONFLICT,null,null,anchorIds,"UNREPRESENTABLE_INTERVAL");
        }
    }
}

