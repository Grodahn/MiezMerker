package org.miezmerker.backend.repo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.miezmerker.backend.domain.FeedingSite;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeedingSiteRepository extends JpaRepository<FeedingSite, UUID> {
    List<FeedingSite> findByOrganizationId(UUID organizationId);

    Optional<FeedingSite> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
