package org.miezmerker.backend.repo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.miezmerker.backend.domain.Cat;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CatRepository extends JpaRepository<Cat, UUID> {
    List<Cat> findByOrganizationId(UUID organizationId);

    Optional<Cat> findByIdAndOrganizationId(UUID id, UUID organizationId);

    Optional<Cat> findByOrganizationIdAndChipId(UUID organizationId, String chipId);
}
