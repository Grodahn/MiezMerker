package org.miezmerker.backend.repo;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.miezmerker.backend.domain.NodeDeployment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface NodeDeploymentRepository extends JpaRepository<NodeDeployment, UUID> {
    List<NodeDeployment> findByOrganizationId(UUID organizationId);

    List<NodeDeployment> findByOrganizationIdAndNodeNodeId(UUID organizationId, UUID nodeId);

    List<NodeDeployment> findByNodeNodeId(UUID nodeId);

    Optional<NodeDeployment> findByIdAndOrganizationId(UUID id, UUID organizationId);

    @Query("select d from NodeDeployment d where d.node.nodeId = :nodeId "
            + "and d.validFrom <= :instant and (d.validUntil is null or d.validUntil > :instant)")
    List<NodeDeployment> findCovering(UUID nodeId, Instant instant);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from NodeDeployment d where d.node.nodeId = :nodeId")
    List<NodeDeployment> findByNodeIdLocked(UUID nodeId);
}
