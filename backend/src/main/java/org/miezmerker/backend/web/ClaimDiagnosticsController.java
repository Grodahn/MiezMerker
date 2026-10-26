package org.miezmerker.backend.web;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import org.miezmerker.backend.diagnostics.ClaimDiagnostics;
import org.miezmerker.backend.diagnostics.ErrorCodes;
import org.miezmerker.backend.security.AppUserDetails;
import org.miezmerker.backend.security.TenantService;
import org.miezmerker.backend.service.NodeTimeRateLimiter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Untrusted telemetry, never a claim/ownership channel. No client IDs are logged. */
@RestController
public class ClaimDiagnosticsController {
    private static final Set<String> PHASES = Set.of("discovered","connection","hello","policy","alpha_clock",
        "time_challenge","time_proof","claim_advert","local_signature","backend_claim","receipt_persisted",
        "receipt","node_claimed","preflight","complete");
    private static final Set<String> STATUSES = Set.of("started","ok","rejected","failed","cancelled","timeout");
    private static final Set<String> CODES = java.util.Arrays.stream(ErrorCodes.class.getFields())
        .filter(f -> f.getType() == String.class).map(f -> { try { return (String) f.get(null); }
            catch (IllegalAccessException e) { throw new IllegalStateException(e); } }).collect(java.util.stream.Collectors.toSet());
    private final TenantService tenants;
    private final NodeTimeRateLimiter limits;
    private final org.springframework.transaction.support.TransactionTemplate authorization;
    private final ObjectMapper mapper = tools.jackson.databind.json.JsonMapper.builder()
        .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public ClaimDiagnosticsController(TenantService tenants, NodeTimeRateLimiter limits,
            org.springframework.transaction.PlatformTransactionManager transactions) {
        this.tenants=tenants; this.limits=limits;
        this.authorization = new org.springframework.transaction.support.TransactionTemplate(transactions);
        this.authorization.setReadOnly(true);
    }
    @io.swagger.v3.oas.annotations.media.Schema(additionalProperties=io.swagger.v3.oas.annotations.media.Schema.AdditionalPropertiesValue.FALSE)
    public record Event(int sequence, String phase, String status, String code, long durationMs, long elapsedMs,
        String policy, Integer caps, Integer protocolVersion) {}
    @io.swagger.v3.oas.annotations.media.Schema(additionalProperties=io.swagger.v3.oas.annotations.media.Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties={"attemptId","organizationId","events"})
    public record Batch(UUID attemptId, UUID organizationId,
        @io.swagger.v3.oas.annotations.media.ArraySchema(maxItems=32, minItems=1) java.util.List<Event> events) {}
    @io.swagger.v3.oas.annotations.Operation(operationId="collectClaimDiagnostics", summary="Collect bounded untrusted claim diagnostics; ACTIVE ADMIN, CSRF, 12 KiB, 12 requests/user/minute",
        requestBody=@io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,
            content=@io.swagger.v3.oas.annotations.media.Content(schema=@io.swagger.v3.oas.annotations.media.Schema(implementation=Batch.class))))
    @PostMapping(value="/api/v1/nodes/claim-diagnostics", consumes="application/json")
    public ResponseEntity<Void> collect(HttpServletRequest request, @AuthenticationPrincipal AppUserDetails principal) throws IOException {
        var user = TenantService.currentUserId(principal);
        limits.acquire("claim-diagnostics-user",user,12);
        if (request.getContentLengthLong() > 12288) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
        byte[] bytes = request.getInputStream().readNBytes(12289);
        if (bytes.length > 12288) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
        JsonNode root;
        try { root = mapper.readTree(bytes); }
        catch (RuntimeException e) { throw bad(); }
        fields(root,Set.of("attemptId","organizationId","events"));
        String attempt = root.path("attemptId").asText();
        if (!ClaimDiagnostics.validAttempt(attempt)) throw bad();
        UUID org;
        try { org = UUID.fromString(root.path("organizationId").asText()); } catch (IllegalArgumentException e) { throw bad(); }
        // Reading a slow/chunked client body must never reserve a claim DB
        // connection. Keep only the lazy membership checks in a short transaction.
        authorization.executeWithoutResult(status -> tenants.requireAdmin(user,org));
        limits.acquire("claim-diagnostics-org",org,60);
        var events = root.path("events");
        if (!events.isArray() || events.isEmpty() || events.size() > 32) throw bad();
        // Validate the entire batch before any logging; never include invalid input in exceptions.
        for (var e : events) {
            var required = Set.of("sequence","phase","status","code","durationMs","elapsedMs");
            var allowed = Set.of("sequence","phase","status","code","durationMs","elapsedMs","policy","caps","protocolVersion");
            if (!e.isObject() || !e.propertyNames().containsAll(required)) throw bad();
            for (var key : e.propertyNames()) if (!allowed.contains(key)) throw bad();
            if (e.has("policy") && !Set.of("FIELD_ALPHA_PHONE_CLOCK","SIGNED_BACKEND_TIME").contains(e.path("policy").asText())) throw bad();
            if (e.has("caps")) number(e,"caps",0,65535);
            if (e.has("protocolVersion")) number(e,"protocolVersion",0,255);
            if (!PHASES.contains(e.path("phase").asText()) || !STATUSES.contains(e.path("status").asText())
                || !(e.path("code").asText().equals("OK") || CODES.contains(e.path("code").asText()))) throw bad();
            number(e,"sequence",1,10000); number(e,"durationMs",0,3600000); number(e,"elapsedMs",0,3600000);
        }
        for (var e : events) ClaimDiagnostics.emit(attempt,"pwa",true,e.path("phase").asText(),e.path("status").asText(),
            e.path("code").asText(),e.path("durationMs").asLong(),0,e.path("sequence").asInt(),
            e.path("elapsedMs").asLong(), e.path("policy").asText("unknown"), e.path("caps").asInt(0), e.path("protocolVersion").asInt(0));
        return ResponseEntity.noContent().header("Cache-Control","no-store").build();
    }
    private static void fields(JsonNode node, Set<String> allowed) {
        if (node == null || !node.isObject() || node.size() != allowed.size()) throw bad();
        for (var property : node.properties()) if (!allowed.contains(property.getKey())) throw bad();
    }
    private static void number(JsonNode node,String key,long min,long max) {
        var n=node.path(key); if (!n.isIntegralNumber() || !n.canConvertToLong() || n.asLong()<min || n.asLong()>max) throw bad();
    }
    private static ResponseStatusException bad() { return new ResponseStatusException(HttpStatus.BAD_REQUEST); }
}
