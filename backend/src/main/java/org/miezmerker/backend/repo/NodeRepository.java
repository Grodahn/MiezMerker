package org.miezmerker.backend.repo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.miezmerker.backend.domain.NodeDevice;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NodeRepository extends JpaRepository<NodeDevice, UUID> {
    Optional<NodeDevice> findByFingerprint(String fingerprint);

    List<NodeDevice> findByOrganizationId(UUID organizationId);
}
