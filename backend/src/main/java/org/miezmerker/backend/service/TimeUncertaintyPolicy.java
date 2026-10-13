package org.miezmerker.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Operator-attested, versioned physical assumptions; defaults cannot certify UTC. */
@Component
public class TimeUncertaintyPolicy {
    public record Budget(long serverErrorMs, long samplingMs, long driftPpm, String provenance) {}
    private final Budget budget;
    public TimeUncertaintyPolicy(
            @Value("${miezmerker.time-projection.server-error-ms:-1}") long error,
            @Value("${miezmerker.time-projection.sampling-ms:-1}") long sampling,
            @Value("${miezmerker.time-projection.drift-ppm:-1}") long ppm,
            @Value("${miezmerker.time-projection.provenance:}") String provenance) {
        budget = error > 0 && sampling > 0 && ppm > 0 && ppm < 1_000_000
                && !provenance.isBlank() && provenance.length() <= 500
                ? new Budget(error,sampling,ppm,provenance) : null;
    }
    public Budget budget() { return budget; }
}

