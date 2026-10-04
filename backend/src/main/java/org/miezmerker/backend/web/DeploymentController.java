package org.miezmerker.backend.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.miezmerker.backend.domain.NodeDeployment;
import org.miezmerker.backend.repo.NodeDeploymentRepository;
import org.miezmerker.backend.security.AppUserDetails;
import org.miezmerker.backend.security.TenantService;
import org.miezmerker.backend.service.DeploymentService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Historical node deployments (#9). Reads require ACTIVE membership; every
 * mutation requires ADMIN and serializes on the node row so concurrent moves
 * cannot create overlapping history. Moving a node never rewrites stored
 * observation attributions.
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/deployments")
public class DeploymentController {
    private final NodeDeploymentRepository deployments;
    private final DeploymentService service;
    private final TenantService tenants;

    public DeploymentController(NodeDeploymentRepository deployments, DeploymentService service,
            TenantService tenants) {
        this.deployments = deployments;
        this.service = service;
        this.tenants = tenants;
    }

    @Schema(name = "DeploymentView")
    public record DeploymentView(UUID id, String organizationId, String nodeId,
            String feedingSiteId, String validFrom, String validUntil, String createdAt) {}

    @Schema(name = "CreateDeploymentRequest")
    public record CreateDeploymentRequest(
            @NotNull UUID nodeId,
            @NotNull UUID feedingSiteId,
            @NotNull Instant validFrom,
            Instant validUntil) {}

    @Schema(name = "CloseDeploymentRequest")
    public record CloseDeploymentRequest(Instant validUntil) {}

    static DeploymentView toView(NodeDeployment d) {
        return new DeploymentView(d.getId(), d.getOrganization().getId().toString(),
                d.getNode().getNodeId().toString(), d.getFeedingSite().getId().toString(),
                d.getValidFrom().toString(),
                d.getValidUntil() == null ? null : d.getValidUntil().toString(),
                d.getCreatedAt().toString());
    }

    @GetMapping(produces = "application/json")
    @Operation(operationId = "listDeployments",
            summary = "List deployments of an organization, optionally filtered by node")
    @Transactional(readOnly = true)
    public List<DeploymentView> list(@PathVariable UUID organizationId,
            @RequestParam(required = false) UUID nodeId,
            @AuthenticationPrincipal AppUserDetails principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        tenants.requireActive(principal.getId(), organizationId);
        if (nodeId != null) {
            return deployments.findByOrganizationIdAndNodeNodeId(organizationId, nodeId).stream()
                    .map(DeploymentController::toView).toList();
        }
        return deployments.findByOrganizationId(organizationId).stream()
                .map(DeploymentController::toView).toList();
    }

    @GetMapping(value = "/{deploymentId}", produces = "application/json")
    @Operation(operationId = "getDeployment",
            summary = "Deployment details; never readable across organizations")
    @Transactional(readOnly = true)
    public DeploymentView get(@PathVariable UUID organizationId,
            @PathVariable UUID deploymentId,
            @AuthenticationPrincipal AppUserDetails principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        tenants.requireActive(principal.getId(), organizationId);
        return toView(deployments.findByIdAndOrganizationId(deploymentId, organizationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    @PostMapping(consumes = "application/json", produces = "application/json")
    @Operation(operationId = "createDeployment",
            summary = "ADMIN assigns a node to a feeding site for a validity range")
    public DeploymentView create(@PathVariable UUID organizationId,
            @Valid @RequestBody CreateDeploymentRequest request,
            @AuthenticationPrincipal AppUserDetails principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return toView(service.create(principal.getId(), organizationId, request.nodeId(),
                request.feedingSiteId(), request.validFrom(), request.validUntil()));
    }

    @PatchMapping(value = "/{deploymentId}", consumes = "application/json",
            produces = "application/json")
    @Operation(operationId = "closeDeployment",
            summary = "ADMIN closes or reopens a deployment by setting validUntil")
    public DeploymentView close(@PathVariable UUID organizationId,
            @PathVariable UUID deploymentId,
            @RequestBody CloseDeploymentRequest request,
            @AuthenticationPrincipal AppUserDetails principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return toView(service.close(principal.getId(), organizationId, deploymentId,
                request == null ? null : request.validUntil()));
    }

    @DeleteMapping("/{deploymentId}")
    @Operation(operationId = "deleteDeployment",
            summary = "ADMIN deletes a deployment (stored observation attributions are kept)")
    public void delete(@PathVariable UUID organizationId, @PathVariable UUID deploymentId,
            @AuthenticationPrincipal AppUserDetails principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        service.delete(principal.getId(), organizationId, deploymentId);
    }
}
