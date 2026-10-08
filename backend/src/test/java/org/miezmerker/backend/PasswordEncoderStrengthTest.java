package org.miezmerker.backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves the #59 BCrypt model: the {@code test} profile uses fast but real
 * BCrypt (cost 4), while the production default stays at cost 12.
 *
 * <p>Real BCrypt is kept everywhere: no {@code NoOpPasswordEncoder}, no
 * plaintext passwords. Hashes embed their own cost, so matching works across
 * costs and existing production hashes keep verifying.
 */
@SpringBootTest
@ActiveProfiles("test")
class PasswordEncoderStrengthTest {
    @Autowired PasswordEncoder passwords;

    @Test
    void testProfileUsesFastRealBcrypt() {
        String hash = passwords.encode("supersecret-password-1");
        assertTrue(hash.matches("^\\$2[aby]\\$04\\$.*"),
                "test profile must use BCrypt cost 4, got: " + hash);
        assertTrue(passwords.matches("supersecret-password-1", hash));
        assertFalse(passwords.matches("wrong-password-xyz", hash));
    }

    @Test
    void productionDefaultRemainsCostTwelve() {
        // The bean's property default (:12) is the production contract; the
        // no-profile CookieSecurityTest proves it end to end via the context.
        PasswordEncoder production = new BoundaryConfiguration().passwordEncoder(12);
        String hash = production.encode("supersecret-password-1");
        assertTrue(hash.matches("^\\$2[aby]\\$12\\$.*"),
                "production default must stay BCrypt cost 12, got: " + hash);
        assertTrue(production.matches("supersecret-password-1", hash));
        assertFalse(production.matches("wrong-password-xyz", hash));
    }

    @Test
    void hashesVerifyAcrossCosts() {
        String cheap = passwords.encode("supersecret-password-1");
        String expensive = new BoundaryConfiguration().passwordEncoder(12)
                .encode("supersecret-password-1");
        // A lowered test cost must never invalidate existing hashes.
        assertTrue(passwords.matches("supersecret-password-1", expensive));
        assertTrue(new BoundaryConfiguration().passwordEncoder(12)
                .matches("supersecret-password-1", cheap));
    }

    @Test
    void outOfRangeStrengthIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new BoundaryConfiguration().passwordEncoder(3));
        assertThrows(IllegalArgumentException.class,
                () -> new BoundaryConfiguration().passwordEncoder(32));
    }
}
