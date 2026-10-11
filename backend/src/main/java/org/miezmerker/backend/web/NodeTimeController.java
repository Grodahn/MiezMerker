package org.miezmerker.backend.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.miezmerker.backend.crypto.NodeTimeProtocol;
import org.miezmerker.backend.security.AppUserDetails;
import org.miezmerker.backend.security.TenantService;
import org.miezmerker.backend.service.NodeTimeRateLimiter;
import org.miezmerker.backend.service.NodeTimeService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/nodes/time-bootstrap")
public class NodeTimeController {
    private final NodeTimeService service;
    private final NodeTimeRateLimiter limits;
    public NodeTimeController(NodeTimeService service, NodeTimeRateLimiter limits) { this.service=service; this.limits=limits; }
    @PostMapping(consumes="application/json",produces="application/json")
    @Operation(operationId="issueNodeTimeProof",summary="Issue challenge-bound UTC proof; ACTIVE owner member or pre-claim ADMIN",
        requestBody=@io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,
            content=@Content(schema=@Schema(implementation=NodeTimeProtocol.Request.class))))
    public ResponseEntity<NodeTimeService.ProofView> issue(HttpServletRequest request,
            @AuthenticationPrincipal AppUserDetails principal) throws IOException {
        var user = TenantService.currentUserId(principal);
        limits.acquire("user",user,30); // Includes malformed bodies and invalid signatures.
        if (request.getContentLengthLong() > 4096) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
        byte[] bytes = request.getInputStream().readNBytes(4097);
        if (bytes.length > 4096) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
        NodeTimeProtocol.Request parsed;
        try { parsed = NodeTimeProtocol.parseRequest(bytes); }
        catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid time challenge"); }
        return ResponseEntity.ok().header("Cache-Control","no-store").body(service.issue(user,parsed));
    }
}
