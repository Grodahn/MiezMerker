package org.miezmerker.backend.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.miezmerker.backend.domain.FeedingSite;
import org.miezmerker.backend.domain.NodeDeployment;
import org.miezmerker.backend.domain.NodeDevice;
import org.miezmerker.backend.domain.NodeState;
import org.miezmerker.backend.domain.Organization;
import org.miezmerker.backend.repo.FeedingSiteRepository;
import org.miezmerker.backend.repo.NodeDeploymentRepository;
import org.miezmerker.backend.repo.NodeRepository;
import org.miezmerker.backend.repo.OrganizationRepository;
import org.miezmerker.backend.security.TenantService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Historical node-to-feeding-site assignments (#9, #51).
 *
 * <p>Intervals use inclusive {@code valid_from} and exclusive {@code valid_until}
 * ({@code null} = currently open). Intervals of one node must never overlap; a
 * move closes the old interval and opens a new one. Deployment writes serialize
 * on the node row so concurrent moves cannot create overlapping history.
 * Stored observation attributions are frozen at ingest and never rewritten.
 *
 * <p>Authorization (#51): deployment reads require ACTIVE membership;
 * every deployment write (create, close/update, move, delete) requires
 * ACTIVE ADMIN. The check lives here in the service/domain boundary so
 * direct service calls fail closed even if a future controller forgets
 * its own check. Field users may read current/historical site context
 * but must not alter assignments.
 */
@Service
public class DeploymentService {
    private final TenantService tenants;
    private final OrganizationRepository organizations;
    private final NodeRepository nodes;
    private final FeedingSiteRepository sites;
    private final NodeDeploymentRepository deployments;

    public DeploymentService(TenantService tenants, OrganizationRepository organizations,
            NodeRepository nodes, FeedingSiteRepository sites,
            NodeDeploymentRepository deployments) {
        this.tenants = tenants;
        this.organizations = organizations;
        this.nodes = nodes;
        this.sites = sites;
        this.deployments = deployments;
    }

    @Transactional
    public NodeDeployment create(UUID userId, UUID organizationId, UUID nodeId,
            UUID feedingSiteId, Instant validFrom, Instant validUntil) {
        tenants.requireAdmin(userId, organizationId);
        Organization org = organizations.findById(organizationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (validFrom == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "validFrom is required");
        }
        // Bounds have millisecond precision: node RTC values arrive as epoch
        // millis, so sub-millisecond bound fractions would otherwise exclude an
        // observation stamped exactly at the bound.
        validFrom = validFrom.truncatedTo(ChronoUnit.MILLIS);
        if (validUntil != null) {
            validUntil = validUntil.truncatedTo(ChronoUnit.MILLIS);
        }
        if (validUntil != null && !validUntil.isAfter(validFrom)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "validUntil must be after validFrom");
        }
        // Serialize all deployment writes for this node.
        NodeDevice node = nodes.findByIdLocked(nodeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "node not found"));
        if (node.getState() != NodeState.CLAIMED || node.getOrganization() == null
                || !node.getOrganization().getId().equals(organizationId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "node not found");
        }
        FeedingSite site = sites.findByIdAndOrganizationId(feedingSiteId, organizationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "feeding site not found"));
        List<NodeDeployment> existing = deployments.findByNodeIdLocked(nodeId);
        for (NodeDeployment other : existing) {
            if (overlaps(other.getValidFrom(), other.getValidUntil(), validFrom, validUntil)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "deployment overlaps an existing interval of this node");
            }
        }
        NodeDeployment created =
                new NodeDeployment(org, node, site, validFrom, validUntil);
        return deployments.save(created);
    }

    @Transactional
    public NodeDeployment close(UUID userId, UUID organizationId, UUID deploymentId,
            Instant validUntil) {
        tenants.requireAdmin(userId, organizationId);
        NodeDeployment deployment = deployments.findByIdAndOrganizationId(deploymentId,
                organizationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        // Serialize against concurrent moves of the same node.
        nodes.findByIdLocked(deployment.getNode().getNodeId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "node not found"));
        if (validUntil != null) {
            validUntil = validUntil.truncatedTo(ChronoUnit.MILLIS);
        }
        if (validUntil != null && !validUntil.isAfter(deployment.getValidFrom())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "validUntil must be after validFrom");
        }
        for (NodeDeployment other : deployments
                .findByNodeIdLocked(deployment.getNode().getNodeId())) {
            if (other.getId().equals(deployment.getId())) {
                continue;
            }
            if (overlaps(other.getValidFrom(), other.getValidUntil(),
                    deployment.getValidFrom(), validUntil)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "closing would overlap an existing interval of this node");
            }
        }
        deployment.setValidUntil(validUntil);
        return deployments.save(deployment);
    }

    @Transactional
    public NodeDeployment move(UUID userId, UUID organizationId, UUID nodeId,
            UUID feedingSiteId, Instant validFrom) {
        tenants.requireAdmin(userId, organizationId);
        if (validFrom == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "validFrom is required");
        }
        validFrom = validFrom.truncatedTo(ChronoUnit.MILLIS);
        NodeDevice node = nodes.findByIdLocked(nodeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (node.getOrganization() == null
                || !node.getOrganization().getId().equals(organizationId)
                || node.getState() != NodeState.CLAIMED) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        // Validate every reference and interval before changing existing history.
        sites.findByIdAndOrganizationId(feedingSiteId, organizationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        List<NodeDeployment> history = deployments.findByNodeIdLocked(nodeId);
        NodeDeployment open = null;
        for (NodeDeployment other : history) {
            if (other.getValidUntil() == null) {
                if (!validFrom.isAfter(other.getValidFrom())) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "move must be after the open deployment start");
                }
                open = other;
            } else if (overlaps(other.getValidFrom(), other.getValidUntil(), validFrom, null)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "move overlaps existing deployment history");
            }
        }
        if (open != null) {
            if (open.getFeedingSite().getId().equals(feedingSiteId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "node is already assigned to this feeding site");
            }
            open.setValidUntil(validFrom);
            deployments.save(open);
        }
        // Joins the same transaction; failures roll back the closed interval too.
        return create(userId, organizationId, nodeId, feedingSiteId, validFrom, null);
    }

    @Transactional
    public void delete(UUID userId, UUID organizationId, UUID deploymentId) {
        tenants.requireAdmin(userId, organizationId);
        NodeDeployment deployment = deployments.findByIdAndOrganizationId(deploymentId,
                organizationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        // Serialize against a concurrent move of the same node; the node must
        // still exist because the deployment references it.
        nodes.findByIdLocked(deployment.getNode().getNodeId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "node not found"));
        try {
            deployments.delete(deployment);
            deployments.flush();
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "deployment is still referenced by observations");
        }
    }

    static boolean overlaps(Instant fromA, Instant untilA, Instant fromB, Instant untilB) {
        // [from, until) with null until = +infinity. Adjacent (until == from) is fine.
        boolean aStartsBeforeBEnds = untilB == null || fromA.isBefore(untilB);
        boolean bStartsBeforeAEnds = untilA == null || fromB.isBefore(untilA);
        return aStartsBeforeBEnds && bStartsBeforeAEnds;
    }
}
