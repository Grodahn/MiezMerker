package org.miezmerker.backend.repo;

import java.util.List;
import java.util.UUID;
import org.miezmerker.backend.domain.OrganizationShareAudit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShareAuditRepository extends JpaRepository<OrganizationShareAudit, UUID> {
    List<OrganizationShareAudit> findByOwnerIdOrderByCreatedAtDesc(UUID ownerOrgId);

    List<OrganizationShareAudit> findByTargetIdOrderByCreatedAtDesc(UUID recipientOrgId);
}
