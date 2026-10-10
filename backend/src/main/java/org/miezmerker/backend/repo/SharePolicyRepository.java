package org.miezmerker.backend.repo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.miezmerker.backend.domain.OrganizationSharePolicy;
import org.miezmerker.backend.domain.ShareScope;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SharePolicyRepository extends JpaRepository<OrganizationSharePolicy, UUID> {
    List<OrganizationSharePolicy> findByOrganizationId(UUID ownerOrgId);

    Optional<OrganizationSharePolicy> findByOrganizationIdAndScope(UUID ownerOrgId, ShareScope scope);

    @Query("select p from OrganizationSharePolicy p left join fetch p.recipients r"
            + " where p.organization.id = :ownerOrgId")
    List<OrganizationSharePolicy> findByOrganizationIdWithRecipients(UUID ownerOrgId);
}
