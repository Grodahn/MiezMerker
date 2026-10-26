package org.miezmerker.backend.diagnostics;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Runs outside security so even auth/CSRF denial has a safe correlation ID. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ClaimDiagnostics extends OncePerRequestFilter {
    private record Context(String attempt, long start, String phase, long phaseStart) {}
    private static final ThreadLocal<Context> CONTEXT = new ThreadLocal<>();
    private static boolean detailed;
    public ClaimDiagnostics(@Value("${miezmerker.claim-diagnostics.detailed:false}") boolean enabled) { detailed = enabled; }
    public static boolean validAttempt(String value) {
        return value != null && value.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    }
    public static void phase(String phase) {
        var c = CONTEXT.get();
        if (c == null) return;
        emit(c.attempt(), "backend", false, c.phase(), "ok", "OK", (System.nanoTime()-c.phaseStart())/1000000, 0, 0);
        CONTEXT.set(new Context(c.attempt(), c.start(), phase, System.nanoTime()));
        emit(c.attempt(), "backend", false, phase, "started", "OK", 0, 0, 0);
    }
    public static void rejected(String code) {
        var c = CONTEXT.get();
        if (c != null) emit(c.attempt(), "backend", false, c.phase(), "rejected", code,
            (System.nanoTime()-c.phaseStart())/1000000, 403, 0);
    }
    public static void emit(String attempt, String source, boolean untrusted, String phase, String status,
            String code, long duration, int httpStatus, int sequence) {
        emit(attempt,source,untrusted,phase,status,code,duration,httpStatus,sequence,0,"unknown",0,0);
    }
    public static void emit(String attempt, String source, boolean untrusted, String phase, String status,
            String code, long duration, int httpStatus, int sequence, long elapsed, String policy, int caps, int protocol) {
        if (!detailed && (status.equals("ok") || status.equals("started"))) return;
        try {
            LoggerFactory.getLogger(ClaimDiagnostics.class).info(
                "claim_diagnostic utc={} attemptId={} source={} untrusted={} phase={} status={} code={} durationMs={} httpStatus={} sequence={} elapsedMs={} policy={} caps={} protocolVersion={}",
                Instant.now(), attempt, source, untrusted, phase, status, code, duration, httpStatus, sequence, elapsed, policy, caps, protocol);
        } catch (RuntimeException ignored) { /* Logging must never change claim outcome. */ }
    }
    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI();
        if (!req.getMethod().equals("POST") || !(path.equals("/api/v1/nodes/claim") || path.equals("/api/v1/nodes/time-bootstrap"))) {
            chain.doFilter(req,res); return;
        }
        String header = req.getHeader("X-MM-Claim-Attempt");
        String attempt = validAttempt(header) ? header : UUID.randomUUID().toString();
        long start = System.nanoTime();
        CONTEXT.set(new Context(attempt,start,"authorization_csrf",start));
        res.setHeader("X-MM-Claim-Attempt",attempt);
        emit(attempt,"backend",false,"authorization_csrf","started","OK",0,0,0);
        boolean failed = false;
        try { chain.doFilter(req,res); }
        catch (IOException | ServletException | RuntimeException e) { failed = true; throw e; }
        finally {
            var c = CONTEXT.get();
            int status = failed ? 500 : res.getStatus();
            String code = status < 400 ? "OK" : ErrorCodes.operation(path.endsWith("/claim") ? "NodeController#claim" : "NodeTimeController#issue", status, ApiError.forStatus(status).code());
            if (c != null) emit(attempt,"backend",false,c.phase(),status < 400 ? "ok" : "rejected",code,(System.nanoTime()-c.phaseStart())/1000000,status,0);
            emit(attempt,"backend",false,"complete",status < 400 ? "ok" : "failed",code,(System.nanoTime()-start)/1000000,status,0);
            CONTEXT.remove();
        }
    }
}
