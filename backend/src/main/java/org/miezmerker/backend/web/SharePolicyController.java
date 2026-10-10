package org.miezmerker.backend.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.miezmerker.backend.domain.ShareAudience;
import org.miezmerker.backend.domain.ShareScope;
import org.miezmerker.backend.security.AppUserDetails;
import org.miezmerker.backend.service.SharePolicyService;
import org.miezmerker.backend.service.SharePolicyService.PolicyView;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Outgoing sharing policy management (ADR 0016, issue #96). Every mutation
 * requires an ACTIVE ADMIN of the data-owning organization; the caller's
 * membership is the authority, never a client-supplied organization id.
 * Grants are read-only, non-reciprocal and non-transitive, and revocation
 * takes effect on the next resolution.
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/share-policies")
public class SharePolicyController {
    private final SharePolicyService sharePolicies;

    public SharePolicyController(SharePolicyService sharePolicies) {
        this.sharePolicies = sharePolicies;
    }

    @Schema(name = "UpsertSharePolicyRequest")
    public record UpsertSharePolicyRequest(
            @NotNull ShareAudience audience,
            @Schema(type = "array", nullable = true,
                    description = "Required for ALLOWLIST, forbidden otherwise: recipient organization ids")
            List<UUID> recipientIds) {}

    @Schema(name = "RevokeSharePolicyResponse")
    public record RevokeSharePolicyResponse(String scope, boolean revoked) {}

    @GetMapping(produces = "application/json")
    @Operation(operationId = "listSharePolicies",
            summary = "ADMIN lists the outgoing sharing policies of the active organization")
    @Transactional(readOnly = true)
    public List<PolicyView> list(@PathVariable UUID organizationId,
            @AuthenticationPrincipal AppUserDetails principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return sharePolicies.listOutgoing(principal.getId(), organizationId);
    }

    @PutMapping(value = "/{scope}", consumes = "application/json", produces = "application/json")
    @Operation(operationId = "upsertSharePolicy",
            summary = "ADMIN creates or replaces one outgoing policy; scope in path, audience in body")
    @Transactional
    public PolicyView upsert(@PathVariable UUID organizationId, @PathVariable ShareScope scope,
            @Valid @RequestBody UpsertSharePolicyRequest request,
            @AuthenticationPrincipal AppUserDetails principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return sharePolicies.upsert(principal.getId(), organizationId, scope,
                request.audience(), request.recipientIds());
    }

    @DeleteMapping(value = "/{scope}", produces = "application/json")
    @Operation(operationId = "revokeSharePolicy",
            summary = "ADMIN revokes one outgoing policy; effective on the next resolution")
    @Transactional
    public RevokeSharePolicyResponse revoke(@PathVariable UUID organizationId,
            @PathVariable ShareScope scope,
            @AuthenticationPrincipal AppUserDetails principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        sharePolicies.revoke(principal.getId(), organizationId, scope);
        return new RevokeSharePolicyResponse(scope.name(), true);
    }
}
