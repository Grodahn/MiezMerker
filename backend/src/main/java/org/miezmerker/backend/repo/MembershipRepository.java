package org.miezmerker.backend.repo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.miezmerker.backend.domain.MembershipStatus;
import org.miezmerker.backend.domain.OrganizationMembership;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface MembershipRepository extends JpaRepository<OrganizationMembership, UUID> {
    Optional<OrganizationMembership> findByOrganizationIdAndUserId(UUID organizationId, UUID userId);

    List<OrganizationMembership> findByUserId(UUID userId);

    List<OrganizationMembership> findByOrganizationId(UUID organizationId);

    List<OrganizationMembership> findByUserIdAndStatus(UUID userId, MembershipStatus status);

    @Query("select m from OrganizationMembership m join fetch m.organization o join fetch m.user u where m.user.id = :userId")
    List<OrganizationMembership> findByUserIdWithRefs(UUID userId);

    @Query("select m from OrganizationMembership m join fetch m.user u where m.organization.id = :organizationId")
    List<OrganizationMembership> findByOrganizationIdWithUser(UUID organizationId);
}
