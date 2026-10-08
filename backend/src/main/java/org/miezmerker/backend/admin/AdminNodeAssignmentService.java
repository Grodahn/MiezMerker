package org.miezmerker.backend.admin;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.miezmerker.backend.repo.NodeDeploymentRepository;
import org.miezmerker.backend.repo.NodeRepository;
import org.miezmerker.backend.security.TenantService;
import org.miezmerker.backend.service.DeploymentService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Admin form concurrency guard; assignment rules remain in DeploymentService. */
@Service
public class AdminNodeAssignmentService {
    private final TenantService tenants;
    private final NodeRepository nodes;
    private final NodeDeploymentRepository deployments;
    private final DeploymentService service;

    public AdminNodeAssignmentService(TenantService tenants, NodeRepository nodes,
            NodeDeploymentRepository deployments, DeploymentService service) {
        this.tenants = tenants;
        this.nodes = nodes;
        this.deployments = deployments;
        this.service = service;
    }

    @Transactional
    public void assign(UUID userId, UUID orgId, UUID nodeId, UUID siteId,
            String expectedDeploymentId) {
        tenants.requireAdmin(userId, orgId);
        var node = nodes.findByIdLocked(nodeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (node.getOrganization() == null
                || !node.getOrganization().getId().equals(orgId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        // Check the form's snapshot while holding the same node lock as move().
        // Empty is the explicit snapshot of an initially unassigned node.
        String actual = deployments.findByNodeIdLocked(nodeId).stream()
                .filter(d -> d.getValidUntil() == null)
                .map(d -> d.getId().toString()).findFirst().orElse("");
        if (!Objects.equals(actual, expectedDeploymentId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "stale assignment form");
        }
        if (siteId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "feeding site required");
        }
        // One atomic domain operation for both initial assignment and moving.
        service.move(userId, orgId, nodeId, siteId, Instant.now());
    }
}
