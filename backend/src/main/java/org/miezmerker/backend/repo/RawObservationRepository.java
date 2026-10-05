package org.miezmerker.backend.repo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.miezmerker.backend.domain.RawObservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RawObservationRepository extends JpaRepository<RawObservation, UUID> {
    Optional<RawObservation> findByNodeNodeIdAndSequence(UUID nodeId, long sequence);

    @Query("select o from RawObservation o join fetch o.node n left join fetch o.feedingSite "
            + "where o.node.nodeId = :nodeId and o.sequence = :sequence")
    Optional<RawObservation> findByNodeAndSequenceWithRefs(
            @Param("nodeId") UUID nodeId, @Param("sequence") long sequence);

    @Query("select count(o) from RawObservation o where o.organization.id = :organizationId")
    long countByOrganizationId(@Param("organizationId") UUID organizationId);

    @Query("select o from RawObservation o join fetch o.node n left join fetch o.feedingSite "
            + "left join fetch o.deployment where o.organization.id = :organizationId")
    List<RawObservation> findByOrganizationIdWithRefs(
            @Param("organizationId") UUID organizationId);
}
