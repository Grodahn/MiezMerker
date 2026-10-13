package org.miezmerker.backend.support;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Shared real-container session-expiry probe for API and admin acceptance tests.
 * Import the same configuration to reuse Spring's cached application context;
 * clients and database fixtures remain fresh for each test.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(SessionExpiryTestConfiguration.ExpiryProbe.class)
public class SessionExpiryTestConfiguration {
    // Explicitly excluded from ordinary application scanning. Only tests that
    // import this configuration expose the probe; it is not a production route.
    @TestComponent
    @RestController
    public static class ExpiryProbe {
        @GetMapping("/api/v1/test/expire-session")
        int expire(HttpServletRequest request) {
            var session = request.getSession(false);
            int configuredTimeout = session.getMaxInactiveInterval();
            session.setMaxInactiveInterval(1);
            return configuredTimeout;
        }
    }
}
