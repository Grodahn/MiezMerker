package org.miezmerker.backend.service;

import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.miezmerker.backend.service.TemporalProjectionEngine.Quality.*;

@Tag("visits")
class TemporalVisitAggregationTest {
    final UUID node=UUID.fromString("00000000-0000-4000-8000-000000000001");
    final UUID site=UUID.fromString("00000000-0000-4000-8000-000000000002");
    TemporalVisitService.Read read(long seq,int boot,Long low,Long high,TemporalProjectionEngine.Quality quality,UUID siteId) {
        var p=new TemporalProjectionService.Projection(new UUID(0,seq),seq,"00000000-0000-4000-8000-000000000003",boot,seq*1000,
            TemporalProjectionEngine.VERSION,quality,low,high,null,List.of(),"test",List.of("synthetic-budget"),siteId,null,"TEST");
        return new TemporalVisitService.Read(node,"CHIP",p);
    }
    @Test void provenSameBootGapMergesWithoutExactTimesAndIsOrderIndependent() {
        var a=read(1,1,100000L,100100L,RECONSTRUCTED,site);
        var b=read(2,1,159000L,159100L,RECONSTRUCTED,site);
        var items=TemporalVisitService.aggregate(List.of(b,a));
        assertEquals(items,TemporalVisitService.aggregate(List.of(a,b)));
        assertEquals(1,items.size()); var visit=items.getFirst();
        assertEquals("VISIT",visit.kind()); assertEquals(2,visit.observationCount());
        assertEquals(100000L,visit.earliestUtcMs()); assertEquals(159100L,visit.latestUtcMs());
        assertEquals(List.of(a,b),visit.observations()); assertFalse(visit.ambiguousGap());
    }
    @Test void thresholdStraddlingDoesNotClaimEitherMergeOrSplit() {
        var a=read(1,1,100000L,100100L,RECONSTRUCTED,site);
        var b=read(2,1,160050L,160150L,RECONSTRUCTED,site);
        var items=TemporalVisitService.aggregate(List.of(a,b));
        assertEquals(2,items.size());
        assertTrue(items.stream().allMatch(i->i.ambiguousGap() && i.kind().equals("OBSERVATION")));
    }
    @Test void overlappingIntervalsAndAmbiguousNeighborsCannotBecomeGuaranteedVisits() {
        var a=read(1,1,100000L,100200L,RECONSTRUCTED,site);
        var b=read(2,1,100100L,100300L,RECONSTRUCTED,site);
        var c=read(3,1,110000L,110100L,RECONSTRUCTED,site);
        var items=TemporalVisitService.aggregate(List.of(a,b,c));
        assertEquals(3,items.size());
        assertEquals("OBSERVATION",items.get(0).kind()); assertEquals("OBSERVATION",items.get(1).kind());
        assertEquals(1,items.get(2).observationCount());
        assertTrue(items.stream().allMatch(i->i.kind().equals("OBSERVATION") && i.ambiguousGap()));
    }
    @Test void provenThresholdInclusiveAndProvenSplit() {
        var a=read(1,1,100000L,100100L,RECONSTRUCTED,site);
        assertEquals(1,TemporalVisitService.aggregate(List.of(a,read(2,1,159900L,160000L,RECONSTRUCTED,site))).size());
        var split=TemporalVisitService.aggregate(List.of(a,read(2,1,160101L,160201L,RECONSTRUCTED,site)));
        assertEquals(2,split.size()); assertTrue(split.stream().noneMatch(TemporalVisitService.Item::ambiguousGap));
    }
    @Test void bootNodeChipIncarnationAndSiteIsolation() {
        var a=read(1,1,100000L,100100L,RECONSTRUCTED,site);
        var b=read(2,2,101000L,101100L,RECONSTRUCTED,site);
        assertEquals(2,TemporalVisitService.aggregate(List.of(a,b)).size());
        b=read(2,1,101000L,101100L,RECONSTRUCTED,UUID.randomUUID());
        assertEquals(2,TemporalVisitService.aggregate(List.of(a,b)).size());
        b=read(2,1,101000L,101100L,RECONSTRUCTED,site);
        assertEquals(2,TemporalVisitService.aggregate(List.of(a,new TemporalVisitService.Read(UUID.randomUUID(),"CHIP",b.time()))).size());
        assertEquals(2,TemporalVisitService.aggregate(List.of(a,new TemporalVisitService.Read(node,"OTHER",b.time()))).size());
        var t=b.time(); var p=new TemporalProjectionService.Projection(t.observationId(),t.sequence(),UUID.randomUUID().toString(),t.bootCounter(),t.monotonicMs(),t.algorithmVersion(),t.quality(),t.earliestUtcMs(),t.latestUtcMs(),null,t.anchorIds(),t.reason(),t.uncertaintyPolicies(),site,null,"TEST");
        assertEquals(2,TemporalVisitService.aggregate(List.of(a,new TemporalVisitService.Read(node,"CHIP",p))).size());
    }
    @Test void boundedUnknownConflictUnattributedAndUnboundedStayObservations() {
        for(var quality:List.of(BOUNDED,UNKNOWN,CONFLICT,ESTIMATED)) {
            var item=TemporalVisitService.aggregate(List.of(read(1,1,100000L,200000L,quality,site))).getFirst();
            assertEquals("OBSERVATION",item.kind()); assertEquals(quality.name(),item.quality());
        }
        for(var r:List.of(read(1,1,null,null,UNKNOWN,null),read(2,1,100000L,null,UNKNOWN,null),read(3,1,null,200000L,UNKNOWN,null),read(4,1,100000L,100100L,RECONSTRUCTED,null)))
            assertEquals("OBSERVATION",TemporalVisitService.aggregate(List.of(r)).getFirst().kind());
    }
    @Test void extremeUtcGapUsesCheckedIntegerDomainWithoutWrapping() {
        var a=read(1,1,1L,2L,RECONSTRUCTED,site);
        var b=read(2,1,Long.MAX_VALUE-1,Long.MAX_VALUE,RECONSTRUCTED,site);
        assertEquals(2,TemporalVisitService.aggregate(List.of(a,b)).size());
        assertEquals("OBSERVATION",TemporalVisitService.aggregate(List.of(read(1,1,0L,1L,RECONSTRUCTED,site))).getFirst().kind());
    }
}
