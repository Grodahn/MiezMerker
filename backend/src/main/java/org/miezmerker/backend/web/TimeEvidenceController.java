package org.miezmerker.backend.web;

import java.io.IOException;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import org.miezmerker.backend.security.*;
import org.miezmerker.backend.service.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.*;

@RestController
@RequestMapping("/api/v1/nodes")
public class TimeEvidenceController {
    private final TimeEvidenceService evidence;
    private final TemporalProjectionService projections;
    private final NodeTimeRateLimiter limits;
    public TimeEvidenceController(TimeEvidenceService evidence,TemporalProjectionService projections,NodeTimeRateLimiter limits) {
        this.evidence=evidence; this.projections=projections; this.limits=limits;
    }
    private byte[] body(HttpServletRequest request,UUID user) throws IOException {
        limits.acquire("evidence-user",user,60);
        if(request.getContentLengthLong()>8192) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
        byte[] bytes=request.getInputStream().readNBytes(8193);
        if(bytes.length>8192) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
        return bytes;
    }
    @PostMapping(value="/time-evidence",consumes="application/json",produces="application/json")
    @Operation(operationId="ingestNodeTimeEvidence",summary="Persist exact verified Node acceptance independently of raw ACK",
        requestBody=@io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(schema=@Schema(implementation=TimeEvidenceProtocol.Request.class))))
    public ResponseEntity<TimeEvidenceService.Receipt> ingest(HttpServletRequest request,@AuthenticationPrincipal AppUserDetails principal) throws IOException {
        UUID user=TenantService.currentUserId(principal); var bytes=body(request,user);
        TimeEvidenceProtocol.Request parsed;
        try { parsed=TimeEvidenceProtocol.parse(bytes); }
        catch(IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid time evidence"); }
        return ResponseEntity.ok().header("Cache-Control","no-store").body(evidence.ingest(user,parsed));
    }
    @PostMapping(value="/boot-evidence",consumes="application/json",produces="application/json")
    @Operation(operationId="ingestNodeBootEvidence",summary="Persist owner-uploaded boot diagnostics; does not certify UTC",
        requestBody=@io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(schema=@Schema(implementation=TimeEvidenceProtocol.BootRequest.class))))
    public ResponseEntity<TimeEvidenceService.BootReceipt> boot(HttpServletRequest request,@AuthenticationPrincipal AppUserDetails principal) throws IOException {
        UUID user=TenantService.currentUserId(principal); var bytes=body(request,user);
        TimeEvidenceProtocol.BootRequest parsed;
        try { parsed=TimeEvidenceProtocol.parseBoot(bytes); }
        catch(IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid boot evidence"); }
        return ResponseEntity.ok().header("Cache-Control","no-store").body(evidence.ingestBoot(user,parsed));
    }
    @PostMapping(value="/phone-time-evidence",consumes="application/json",produces="application/json")
    @Operation(operationId="ingestPhoneTimeEvidence",summary="Owner-reported observation-time estimate; never security-trusted UTC",
        requestBody=@io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(schema=@Schema(implementation=TimeEvidenceProtocol.PhoneRequest.class))))
    public ResponseEntity<TimeEvidenceService.Receipt> phone(HttpServletRequest request,@AuthenticationPrincipal AppUserDetails principal) throws IOException {
        UUID user=TenantService.currentUserId(principal); var bytes=body(request,user);
        TimeEvidenceProtocol.PhoneRequest parsed;
        try { parsed=TimeEvidenceProtocol.parsePhone(bytes); }
        catch(IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid phone estimate"); }
        return ResponseEntity.ok().header("Cache-Control","no-store").body(evidence.ingestPhone(user,parsed));
    }
    @GetMapping(value="/{nodeId}/temporal-projections",produces="application/json")
    @Operation(operationId="listNodeTemporalProjections",summary="Owner-only immutable observation UTC intervals ordered by sequence")
    public ResponseEntity<TemporalProjectionService.Page> read(@PathVariable UUID nodeId,@RequestParam UUID organizationId,
            @RequestParam(defaultValue="0") long afterSequence,@RequestParam(defaultValue="50") int limit,
            @AuthenticationPrincipal AppUserDetails principal) {
        return ResponseEntity.ok().header("Cache-Control","no-store")
            .body(projections.read(TenantService.currentUserId(principal),organizationId,nodeId,afterSequence,limit));
    }
}
