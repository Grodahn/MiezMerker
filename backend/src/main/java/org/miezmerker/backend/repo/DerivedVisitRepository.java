package org.miezmerker.backend.repo;

import java.util.List;
import java.util.UUID;
import org.miezmerker.backend.domain.DerivedVisit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DerivedVisitRepository extends JpaRepository<DerivedVisit, UUID> {
    List<DerivedVisit> findByOrganizationIdAndAlgorithmVersion(UUID organizationId,
            String algorithmVersion);

    long countByOrganizationId(UUID organizationId);
}
