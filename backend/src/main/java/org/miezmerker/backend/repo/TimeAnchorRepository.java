package org.miezmerker.backend.repo;
import java.util.*;
import org.miezmerker.backend.domain.TimeAnchor;
import org.springframework.data.jpa.repository.JpaRepository;
public interface TimeAnchorRepository extends JpaRepository<TimeAnchor,UUID> {
    List<TimeAnchor> findByOrganizationIdAndNodeIdAndIncarnationOrderByBootCounterAscUptimeMsAscIdAsc(UUID org, UUID node, UUID incarnation, org.springframework.data.domain.Pageable page);
    Optional<TimeAnchor> findByAnchorIdAndOrganizationIdAndNodeIdAndIncarnationAndBootCounter(UUID id, UUID org, UUID node, UUID incarnation, long boot);
    boolean existsByOrganizationIdAndNodeIdAndIncarnationAndBootCounterAndNonceDigest(UUID org,UUID node,UUID incarnation,long boot,String nonce);
    boolean existsByProofIdOrProofDigest(UUID proofId,String digest);
}

