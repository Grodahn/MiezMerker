package org.miezmerker.backend.repo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.miezmerker.backend.domain.DerivedVisit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DerivedVisitRepository extends JpaRepository<DerivedVisit, UUID> {
    List<DerivedVisit> findByOrganizationId(UUID organizationId);

    List<DerivedVisit> findByOrganizationIdAndAlgorithmVersion(UUID organizationId,
            String algorithmVersion);

    Optional<DerivedVisit> findByIdAndOrganizationId(UUID id, UUID organizationId);

    long countByOrganizationId(UUID organizationId);

    void deleteByOrganizationIdAndAlgorithmVersion(UUID organizationId,
            String algorithmVersion);
}
