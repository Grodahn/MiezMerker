package org.miezmerker.backend.service;

import java.math.BigInteger;

/** Integer-only ADR 0017 interval propagation. An absent endpoint is unbounded. */
public final class TemporalMath {
    private TemporalMath() {}
    public record Interval(Long earliest, Long latest) {
        public boolean conflict() { return earliest != null && latest != null && earliest > latest; }
        public Interval intersect(Interval other) {
            Long lower=earliest;
            if(lower==null) lower=other.earliest;
            else if(other.earliest!=null) lower=Math.max(lower,other.earliest);
            Long upper=latest;
            if(upper==null) upper=other.latest;
            else if(other.latest!=null) upper=Math.min(upper,other.latest);
            return new Interval(lower,upper);
        }
    }
    public static Interval propagate(long lower, long upper, BigInteger anchorUptime, BigInteger eventUptime, long ppm) {
        if (ppm < 0 || ppm >= 1_000_000 || lower > upper) throw new IllegalArgumentException("Invalid bounds");
        BigInteger delta = anchorUptime.subtract(eventUptime);
        BigInteger drift = delta.abs().multiply(BigInteger.valueOf(ppm)).add(BigInteger.valueOf(999_999))
                .divide(BigInteger.valueOf(1_000_000));
        return new Interval(BigInteger.valueOf(lower).subtract(delta).subtract(drift).longValueExact(),
                BigInteger.valueOf(upper).subtract(delta).add(drift).longValueExact());
    }
}
