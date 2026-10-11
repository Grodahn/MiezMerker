package org.miezmerker.backend.repo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.miezmerker.backend.domain.CatPhoto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface CatPhotoRepository extends JpaRepository<CatPhoto, UUID> {
    Optional<CatPhoto> findByCatIdAndOrganizationId(UUID catId, UUID organizationId);

    List<CatPhoto> findByOrganizationIdAndCatIdIn(UUID organizationId, java.util.Collection<UUID> catIds);

    /** Serializes concurrent replace/delete of the same tenant-scoped photo. */
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from CatPhoto p where p.catId = :catId and p.organization.id = :organizationId")
    Optional<CatPhoto> lockByCatIdAndOrganizationId(UUID catId, UUID organizationId);
}
