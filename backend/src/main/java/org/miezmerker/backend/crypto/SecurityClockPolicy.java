package org.miezmerker.backend.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Provisioning expectation, not an assertion about a remote Node's clock. */
@Component
public class SecurityClockPolicy {
    public enum Mode { FIELD_ALPHA_PHONE_CLOCK, SIGNED_BACKEND_TIME }
    private final Mode mode;

    public SecurityClockPolicy(
            @Value("${miezmerker.security-clock-policy:FIELD_ALPHA_PHONE_CLOCK}") Mode mode,
            @Value("${miezmerker.release:false}") boolean release) {
        if (release && mode != Mode.SIGNED_BACKEND_TIME) {
            throw new IllegalStateException("Release requires SIGNED_BACKEND_TIME; FIELD_ALPHA_PHONE_CLOCK is field-test only");
        }
        this.mode = mode;
        org.slf4j.LoggerFactory.getLogger(SecurityClockPolicy.class).info(
            "claim_policy policy={} release={} freshnessWindowMs=300000 receiptSigned=true", mode, release);
        org.slf4j.LoggerFactory.getLogger(SecurityClockPolicy.class).warn(
                "security_clock_policy={} release={}; claim window=300000ms against server UTC; "
                + "signed receipts, ACTIVE ADMIN and tenant gates remain mandatory. "
                + "Policy is provisioning intent, not remote clock attestation. "
                + "Alpha phone time is untrusted; signed Node claims do not prove independent age/live presence. "
                + "TIME_BOOTSTRAP_CLOCK_HEALTHY gates only the separate signed UTC service.", mode, release);
    }

    public Mode mode() { return mode; }
}
