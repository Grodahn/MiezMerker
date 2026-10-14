package org.miezmerker.backend;

import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.*;
import org.miezmerker.backend.service.*;
import static org.miezmerker.backend.service.TemporalProjectionEngine.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("visits") @Tag("node")
class TemporalProjectionEngineTest {
    static BigInteger u(long value) { return BigInteger.valueOf(value); }
    static Evidence a(long boot,long uptime,long lower,long upper,long ppm) {
        return new Evidence(UUID.randomUUID(),boot,u(uptime),lower,upper,ppm,"test-measured-v1");
    }
    static Evidence phone(long boot,long uptime,long utc) { return Evidence.phone(UUID.randomUUID(),boot,u(uptime),utc); }
    @Test void phoneSingleBootSeveralReadsAcrossThreeDaysHasNoDriftOrPrecisionBudget() {
        long day=86400000, s=1791748800000L;
        var engine=new TemporalProjectionEngine(List.of(phone(1,3*day,s)),List.of(),false);
        for(long uptime:List.of(0L,day,2*day,3*day+123)) {
            var result=engine.project(1,u(uptime));
            assertEquals(Quality.ESTIMATED,result.quality());
            assertEquals(s-3*day+uptime,result.earliestUtcMs()); assertEquals(result.earliestUtcMs(),result.latestUtcMs());
        }
    }
    @Test void repeatedPhoneSyncsShowAllOffsetsWithoutCorrectionThresholdOrFavoredTimestamp() {
        long s=1791748800000L;
        for(long difference:List.of(1L,59999L,300000L,86400000L)) {
            var engine=new TemporalProjectionEngine(List.of(phone(1,1000,s),phone(1,2000,s+1000+difference)),List.of(),false);
            var result=engine.project(1,u(900));
            assertEquals(Quality.ESTIMATED,result.quality()); assertEquals(s-100,result.earliestUtcMs());
            assertEquals(s-100+difference,result.latestUtcMs()); assertEquals(2,result.anchorIds().size());
            assertEquals(s+900,engine.project(1,u(1900)).earliestUtcMs());
        }
    }
    @Test void reversedPhoneChronologyConflictsAndNeverSelectsAnAnchor() {
        var engine=new TemporalProjectionEngine(List.of(phone(1,1000,10000),phone(1,2000,9000)),List.of(),false);
        assertEquals(Quality.CONFLICT,engine.project(1,u(1200)).quality());
        assertNull(engine.project(1,u(1200)).earliestUtcMs());
    }
    @Test void tiedPhoneUptimesCannotHideReversedChronologyInAnyInputOrder() {
        var low=phone(1,1000,10000); var high=phone(1,1000,20000); var later=phone(1,2000,15000);
        for(var evidence:List.of(List.of(low,high,later),List.of(high,low,later),
            List.of(later,low,high),List.of(later,high,low))) {
            var result=new TemporalProjectionEngine(evidence,List.of(),false).project(1,u(1200));
            assertEquals(Quality.CONFLICT,result.quality()); assertNull(result.earliestUtcMs()); assertNull(result.latestUtcMs());
        }
    }
    @Test void tiedLaterPhoneUptimesCheckEveryTimestampAgainstAllEarlierAnchors() {
        var earlier=phone(1,1000,20000); var low=phone(1,2000,15000); var high=phone(1,2000,25000);
        for(var evidence:List.of(List.of(earlier,low,high),List.of(earlier,high,low)))
            assertEquals(Quality.CONFLICT,new TemporalProjectionEngine(evidence,List.of(),false).project(1,u(1500)).quality());
        // Equal-uptime disagreement alone remains a visible estimate span;
        // it does not invent sub-millisecond ordering or a tolerance threshold.
        for(var evidence:List.of(List.of(low,high),List.of(high,low))) {
            var result=new TemporalProjectionEngine(evidence,List.of(),false).project(1,u(2000));
            assertEquals(Quality.ESTIMATED,result.quality()); assertEquals(15000,result.earliestUtcMs()); assertEquals(25000,result.latestUtcMs());
        }
    }
    @Test void phoneRebootKeepsAnchorsInTheirOwnEpoch() {
        var engine=new TemporalProjectionEngine(List.of(phone(1,86400000,100000000),phone(2,100,200000000)),List.of(),false);
        assertEquals(13600000,engine.project(1,u(0)).earliestUtcMs());
        assertEquals(199999900,engine.project(2,u(0)).earliestUtcMs());
    }
    @Test void phoneMultipleIntermediateBootsPreserveRelativeOrderAndOnlyDefensibleOuterBounds() {
        var ranges=List.of(new BootRange(2,2,3,u(100),u(1100)),new BootRange(3,4,5,u(10),u(2010)));
        var engine=new TemporalProjectionEngine(List.of(phone(1,1000,10000),phone(4,100,20000)),ranges,false);
        assertEquals(Quality.BOUNDED,engine.project(2,u(100)).quality());
        assertEquals(10000,engine.project(2,u(100)).earliestUtcMs());
        assertEquals(17000,engine.project(2,u(100)).latestUtcMs());
        assertEquals(11000,engine.project(3,u(10)).earliestUtcMs());
        assertEquals(20000,engine.project(3,u(2010)).latestUtcMs());
        var preceding=new TemporalProjectionEngine(List.of(phone(1,1000,10000)),ranges,false).project(3,u(10));
        assertEquals(11000,preceding.earliestUtcMs()); assertNull(preceding.latestUtcMs()); assertEquals(Quality.UNKNOWN,preceding.quality());
        var following=new TemporalProjectionEngine(List.of(phone(4,100,20000)),ranges,false).project(2,u(100));
        assertNull(following.earliestUtcMs()); assertEquals(17000,following.latestUtcMs());
        var unknown=new TemporalProjectionEngine(List.of(),ranges,false).project(2,u(100));
        assertEquals(Quality.UNKNOWN,unknown.quality()); assertNull(unknown.earliestUtcMs()); assertNull(unknown.latestUtcMs());
    }
    @Test void phoneBusinessAuthorityDoesNotRelabelExistingSignedAnchors() {
        var signed=a(1,1000,10000,10100,20);
        assertEquals(Quality.RECONSTRUCTED,new TemporalProjectionEngine(List.of(signed),List.of(),false).project(1,u(1000)).quality());
        var phone=phone(1,1000,15000);
        var result=new TemporalProjectionEngine(List.of(signed,phone),List.of(),false).project(1,u(1000));
        assertEquals(Quality.ESTIMATED,result.quality()); assertEquals(15000,result.earliestUtcMs());
    }
    @Test void repeatedPhoneSyncCapacityDoesNotInheritSignedAnchorLimitOrQuadraticProjection() {
        var evidence=new ArrayList<Evidence>();
        for(int i=0;i<4096;i++) evidence.add(phone(1,i+1000,100000+i));
        assertTimeout(java.time.Duration.ofSeconds(3),()->{
            var engine=new TemporalProjectionEngine(evidence,List.of(),false);
            for(int i=0;i<100;i++) assertEquals(99000+i,engine.project(1,u(i)).earliestUtcMs());
        });
    }
    @Test void tv5AsymmetricLatencyBeforeAndAfterAnchor() {
        long s=1791748800000L;
        assertEquals(new TemporalMath.Interval(s-500,s),TemporalMath.propagate(s-100,s+400,u(1300),u(900),0));
        assertEquals(new TemporalMath.Interval(s+300,s+800),TemporalMath.propagate(s-100,s+400,u(1300),u(1700),0));
    }
    @Test void tv1TwoEarlierObservationsAndIncreasingCeilingDrift() {
        long s=1791748800000L;
        var engine=new TemporalProjectionEngine(List.of(a(42,14400000,s-100,s+400,20)),List.of(),false);
        var first=engine.project(42,u(1200000)); var second=engine.project(42,u(9000000));
        assertEquals(Quality.RECONSTRUCTED,first.quality());
        assertEquals(s-13200000-100-264,first.earliestUtcMs());
        assertEquals(s-5400000+400+108,second.latestUtcMs());
        assertEquals(new TemporalMath.Interval(1000L,1002L),TemporalMath.propagate(1000,1000,u(0),u(1),20));
    }
    @Test void consistentAnchorsNarrowAtCommonReferenceAndContradictionsConflict() {
        var first=a(1,1000,10000,11000,20);
        var second=a(1,2000,11100,11110,20);
        var narrowed=new TemporalProjectionEngine(List.of(first,second),List.of(),false).project(1,u(1000));
        assertEquals(10099,narrowed.earliestUtcMs()); assertEquals(10111,narrowed.latestUtcMs());
        assertEquals(Quality.RECONSTRUCTED,narrowed.quality());
        var contradictory=a(1,2000,14000,14010,20);
        assertEquals(Quality.CONFLICT,new TemporalProjectionEngine(List.of(first,contradictory),List.of(),false).project(1,u(1000)).quality());
    }
    @Test void tv2TwoPowerlossIntermediateBootOnlyBoundedAndOriginalRelativeUptimeRetained() {
        var evidence=List.of(a(1,100,10000,10100,20),a(9,4000,1000000,1000100,20));
        var ranges=List.of(new BootRange(1,1,1,u(100),u(100)),new BootRange(3,2,3,u(1200),u(9000)),new BootRange(9,4,4,u(1000),u(1000)));
        var engine=new TemporalProjectionEngine(evidence,ranges,false);
        var middle=engine.project(3,u(1200)); var last=engine.project(9,u(1000));
        assertEquals(Quality.BOUNDED,middle.quality()); assertEquals(10000,middle.earliestUtcMs());
        assertEquals(989302,middle.latestUtcMs()); assertEquals(Quality.RECONSTRUCTED,last.quality());
        assertEquals(middle,new Result(middle.quality(),middle.earliestUtcMs(),middle.latestUtcMs(),middle.anchorIds(),middle.reason()));
        assertEquals(17799,engine.project(3,u(9000)).earliestUtcMs());
    }
    @Test void tv3OpenBoundsNeverBecomeMidpointsAndOneRebootCannotBorrowAnchor() {
        assertEquals(Quality.UNKNOWN,new TemporalProjectionEngine(List.of(),List.of(),false).project(1,u(500)).quality());
        var before=new TemporalProjectionEngine(List.of(a(5,1000,10000,10100,20)),List.of(),false).project(1,u(1000));
        assertEquals(Quality.UNKNOWN,before.quality()); assertNull(before.earliestUtcMs()); assertEquals(10100,before.latestUtcMs());
        var after=new TemporalProjectionEngine(List.of(a(1,1000,10000,10100,20)),List.of(),false).project(5,u(1000));
        assertEquals(Quality.UNKNOWN,after.quality()); assertNull(after.latestUtcMs()); assertEquals(10000,after.earliestUtcMs());
    }
    @Test void invalidOrderingAndCrossBootChronologyStayConflict() {
        assertEquals(Quality.CONFLICT,new TemporalProjectionEngine(List.of(a(1,1000,10000,10100,20)),List.of(),true).project(1,u(900)).quality());
        var contradictory=List.of(a(1,1000,10000,10100,20),a(2,1,5000,5100,20));
        assertEquals(Quality.CONFLICT,new TemporalProjectionEngine(contradictory,List.of(),false).project(2,u(1)).quality());
    }
    @Test void unsignedUptimeAndSignedUtcBoundariesUseExactIntegers() {
        var max=TimeEvidenceProtocol.U64_MAX;
        assertEquals(new TemporalMath.Interval(98L,200L),TemporalMath.propagate(100,200,max,max.subtract(BigInteger.ONE),20));
        assertThrows(ArithmeticException.class,()->TemporalMath.propagate(Long.MIN_VALUE,Long.MIN_VALUE,u(1),u(0),0));
        assertThrows(ArithmeticException.class,()->TemporalMath.propagate(Long.MAX_VALUE,Long.MAX_VALUE,u(0),u(1),0));
        assertThrows(IllegalArgumentException.class,()->TemporalMath.propagate(10,9,u(0),u(0),20));
        assertThrows(IllegalArgumentException.class,()->TemporalMath.propagate(0,0,u(0),u(0),-1));
    }
    @Test void missingOrZeroAssumptionsCannotCertifyTime() {
        assertNull(new TimeUncertaintyPolicy(-1,1,20,"v1").budget());
        assertNull(new TimeUncertaintyPolicy(0,1,20,"v1").budget());
        assertNull(new TimeUncertaintyPolicy(100,0,20,"v1").budget());
        assertNull(new TimeUncertaintyPolicy(100,1,0,"v1").budget());
        assertNull(new TimeUncertaintyPolicy(100,1,20,"").budget());
        assertNotNull(new TimeUncertaintyPolicy(100,1,20,"v1").budget());
    }
    @Test void intermediateBootSpanCannotFitShortOuterWindow() {
        var engine=new TemporalProjectionEngine(List.of(a(1,1000,10000,10100,20),a(9,1000,15000,15100,20)),
            List.of(new BootRange(3,2,3,u(1200),u(9000))),false);
        assertEquals(Quality.CONFLICT,engine.project(3,u(1200)).quality());
        assertEquals(Quality.CONFLICT,engine.project(3,u(9000)).quality());
    }
    @Test void representativeVolumeUsesPrecomputedBootBoundariesAndBoundedAnchorGroups() {
        var evidence=new ArrayList<Evidence>();
        for(int boot=1;boot<=128;boot++) for(int anchor=0;anchor<32;anchor++)
            evidence.add(a(boot,1000+anchor,boot*100000L+anchor,boot*100000L+anchor+100,20));
        assertTimeout(java.time.Duration.ofSeconds(10),()->{
            var engine=new TemporalProjectionEngine(evidence,List.of(),false);
            for(int i=0;i<100;i++) assertEquals(Quality.UNKNOWN,engine.project(129,u(i)).quality());
        });
        var tooMany=new ArrayList<Evidence>(); for(int i=0;i<65;i++) tooMany.add(a(1,1000+i,10000+i,10100+i,20));
        assertThrows(IllegalStateException.class,()->new TemporalProjectionEngine(tooMany,List.of(),false));
    }
    @Test void consecutiveUnanchoredBootDurationsAccumulateAndDetectImpossibleOuterWindow() {
        var ranges=List.of(new BootRange(2,2,3,u(0),u(4000)),new BootRange(3,4,5,u(0),u(4000)));
        var impossible=new TemporalProjectionEngine(List.of(a(1,1000,10000,10100,20),a(4,1000,15000,15100,20)),ranges,false);
        assertEquals(Quality.CONFLICT,impossible.project(2,u(0)).quality());
        assertEquals(Quality.CONFLICT,impossible.project(3,u(4000)).quality());
        var possible=new TemporalProjectionEngine(List.of(a(1,1000,10000,10100,20),a(4,1000,20000,20100,20)),ranges,false);
        assertEquals(13999,possible.project(3,u(0)).earliestUtcMs());
        assertEquals(16101,possible.project(2,u(4000)).latestUtcMs());
    }
}

