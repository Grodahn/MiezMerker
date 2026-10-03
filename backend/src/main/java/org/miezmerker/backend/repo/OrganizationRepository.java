package org.miezmerker.backend.repo;

import java.util.Optional;
import java.util.UUID;
import org.miezmerker.backend.domain.Organization;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {
    Optional<Organization> findBySlug(String slug);
}
