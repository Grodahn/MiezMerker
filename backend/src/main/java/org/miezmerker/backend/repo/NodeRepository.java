package org.miezmerker.backend.repo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.miezmerker.backend.domain.NodeDevice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface NodeRepository extends JpaRepository<NodeDevice, UUID> {
    // A fixed database row also serializes claims for identities not inserted yet.
    // Held until the enclosing claim transaction commits, across backend instances.
    @Query(value = "select id from node_claim_lock where id = 1 for update", nativeQuery = true)
    Integer lockClaims();
    Optional<NodeDevice> findByFingerprint(String fingerprint);

    List<NodeDevice> findByOrganizationId(UUID organizationId);
}
