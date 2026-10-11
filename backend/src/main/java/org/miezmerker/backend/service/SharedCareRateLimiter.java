package org.miezmerker.backend.service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Per-account fixed-window protection across organizations and both read routes.
 * Bounded memory; logs contain no chips, observation refs, source identities or results.
 * Multi-instance installations must additionally enforce an installation-wide gateway limit. */
@Component
public class SharedCareRateLimiter {
    private static final Logger LOG = LoggerFactory.getLogger(SharedCareRateLimiter.class);
    private static final int MAX_ACCOUNTS = 10000;
    private static final int REQUESTS_PER_MINUTE = 120;
    private final Map<UUID, Window> windows = new HashMap<>();
    private static class Window {
        final long started = System.nanoTime();
        int requests;
        int invalidProofs;
    }
    public synchronized void acquire(UUID user) {
        long now = System.nanoTime();
        Window window = windows.get(user);
        if (window == null || now - window.started >= 60_000_000_000L) {
            windows.entrySet().removeIf(e -> now - e.getValue().started >= 60_000_000_000L);
            if (windows.size() >= MAX_ACCOUNTS) throw limited();
            window = new Window();
            windows.put(user, window);
        }
        if (++window.requests > REQUESTS_PER_MINUTE) {
            if (window.requests == REQUESTS_PER_MINUTE + 1)
                LOG.warn("Shared-care rate limit reached for account {}", user);
            throw limited();
        }
    }
    public synchronized void invalidProof(UUID user) {
        Window window = windows.get(user);
        if (window != null && ++window.invalidProofs == 10)
            LOG.warn("Repeated invalid shared-care observation proofs for account {}", user);
    }
    private static ResponseStatusException limited() {
        return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "shared-care rate limit");
    }
}
