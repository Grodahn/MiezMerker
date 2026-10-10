package org.miezmerker.backend.repo;

import java.util.List;
import java.util.UUID;
import org.miezmerker.backend.domain.OrganizationShareRecipient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ShareRecipientRepository extends JpaRepository<OrganizationShareRecipient, UUID> {
    List<OrganizationShareRecipient> findByPolicyId(UUID policyId);

    @Query("select r from OrganizationShareRecipient r join fetch r.policy p"
            + " join fetch p.organization where r.organization.id = :recipientOrgId")
    List<OrganizationShareRecipient> findByOrganizationIdWithPolicy(UUID recipientOrgId);
}
