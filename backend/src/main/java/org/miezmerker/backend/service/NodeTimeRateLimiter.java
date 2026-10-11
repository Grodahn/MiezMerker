package org.miezmerker.backend.service;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Bounded, atomic per-process limits. Gateway must enforce aggregate limits across replicas. */
@Component
public class NodeTimeRateLimiter {
    private record Window(long start, int count) {}
    private final Map<String,Window> windows = new HashMap<>();
    private final LongSupplier timer;
    public NodeTimeRateLimiter() { this(System::nanoTime); }
    public NodeTimeRateLimiter(LongSupplier timer) { this.timer = timer; }
    public synchronized void acquire(String scope, Object id, int limit) {
        long now = timer.getAsLong();
        String key = scope + ":" + id;
        Window w = windows.get(key);
        if (w == null || now - w.start() >= 60_000_000_000L) {
            windows.entrySet().removeIf(e -> now - e.getValue().start() >= 60_000_000_000L);
            if (windows.size() >= 10000) throw limited();
            w = new Window(now,0);
        }
        if (w.count() >= limit) throw limited();
        windows.put(key,new Window(w.start(),w.count()+1));
    }
    private static ResponseStatusException limited() {
        return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Time bootstrap rate limit");
    }
}
