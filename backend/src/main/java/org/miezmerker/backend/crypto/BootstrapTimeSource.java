package org.miezmerker.backend.crypto;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Operational monitoring must explicitly enable issuance; clock availability is not health. */
@Component
public class BootstrapTimeSource {
    private final Clock clock;
    private volatile boolean healthy;

    @org.springframework.beans.factory.annotation.Autowired
    public BootstrapTimeSource(@Value("${miezmerker.time-bootstrap.clock-healthy:false}") boolean healthy) {
        this(Clock.systemUTC(), healthy);
        org.slf4j.LoggerFactory.getLogger(BootstrapTimeSource.class).info(
                "UTC bootstrap operator attestation={}; static configuration is not live NTP monitoring. See docs/local-onboarding.md.", healthy);
    }

    public BootstrapTimeSource(Clock clock, boolean healthy) {
        this.clock = clock;
        this.healthy = healthy;
    }

    /** Integration point for trusted in-process clock-health monitoring (never exposed over HTTP). */
    public void reportHealthy(boolean healthy) { this.healthy = healthy; }

    public long issuanceMillis() {
        if (!healthy) throw new IllegalStateException("Time issuance unavailable");
        long now = clock.millis();
        if (now <= 0) throw new IllegalStateException("Time issuance unavailable");
        return now;
    }
}
