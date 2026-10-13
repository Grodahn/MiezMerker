package org.miezmerker.backend.web;

import java.util.UUID;
import org.miezmerker.backend.security.AppUserDetails;
import org.miezmerker.backend.service.TemporalVisitService;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import io.swagger.v3.oas.annotations.Operation;

@RestController
public class TemporalVisitController {
    private final TemporalVisitService visits;
    public TemporalVisitController(TemporalVisitService visits) { this.visits=visits; }
    @GetMapping("/api/v1/organizations/{organizationId}/visits/temporal")
    @Operation(operationId="listTemporalVisits",summary="Owner-only interval visits and uncertain observations; rebuilt from immutable evidence")
    public ResponseEntity<TemporalVisitService.Page> read(@PathVariable UUID organizationId,
            @RequestParam String chipId,@RequestParam(defaultValue="0") int offset,
            @RequestParam(defaultValue="20") int limit,@AuthenticationPrincipal AppUserDetails principal) {
        if(principal==null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(visits.read(principal.getId(),organizationId,chipId,offset,limit));
    }
}
