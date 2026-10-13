package org.miezmerker.backend.repo;
import java.util.*;
import org.miezmerker.backend.domain.BootEpoch;
import org.springframework.data.jpa.repository.JpaRepository;
public interface BootEpochRepository extends JpaRepository<BootEpoch,UUID> {
    Optional<BootEpoch> findByOrganizationIdAndNodeIdAndIncarnationAndBootCounter(UUID org, UUID node, UUID incarnation, long boot);
    java.util.List<BootEpoch> findByOrganizationIdAndNodeIdAndIncarnationOrderByBootCounter(UUID org, UUID node, UUID incarnation, org.springframework.data.domain.Pageable page);
}

