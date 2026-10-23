package org.miezmerker.backend;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.miezmerker.backend.crypto.SecurityClockPolicy;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;

@Tag("node")
@Tag("auth")
class SecurityClockPolicyTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(SecurityClockPolicy.class);

    @Test void defaultsToAlphaAndAllowsExplicitSignedTime() {
        context.run(c -> assertEquals(SecurityClockPolicy.Mode.FIELD_ALPHA_PHONE_CLOCK,
                c.getBean(SecurityClockPolicy.class).mode()));
        context.withPropertyValues("miezmerker.security-clock-policy=SIGNED_BACKEND_TIME", "miezmerker.release=true")
                .run(c -> { assertNull(c.getStartupFailure()); assertEquals(SecurityClockPolicy.Mode.SIGNED_BACKEND_TIME,
                        c.getBean(SecurityClockPolicy.class).mode()); });
    }

    @Test void releaseRejectsAlphaAndInvalidPoliciesFailClosed() {
        context.withPropertyValues("miezmerker.release=true")
                .run(c -> assertNotNull(c.getStartupFailure()));
        context.withPropertyValues("miezmerker.security-clock-policy=typo")
                .run(c -> assertNotNull(c.getStartupFailure()));
    }
}
