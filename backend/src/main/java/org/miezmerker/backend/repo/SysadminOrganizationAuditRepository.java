package org.miezmerker.backend.repo;

import java.util.List;
import java.util.UUID;
import org.miezmerker.backend.domain.SysadminOrganizationAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SysadminOrganizationAuditRepository
        extends JpaRepository<SysadminOrganizationAudit, UUID> {
    // Fetch actor/target inside the query: views and tests must never touch
    // lazy proxies (open-in-view is disabled).
    @Query("select a from SysadminOrganizationAudit a left join fetch a.actor"
            + " left join fetch a.target where a.organization.id = :organizationId"
            + " order by a.createdAt desc")
    List<SysadminOrganizationAudit> findByOrganizationIdOrderByCreatedAtDesc(
            @Param("organizationId") UUID organizationId);

    @Query("select a from SysadminOrganizationAudit a left join fetch a.actor"
            + " left join fetch a.target where a.organization.id = :organizationId"
            + " order by a.createdAt desc")
    List<SysadminOrganizationAudit> findTop50ByOrganizationIdOrderByCreatedAtDesc(
            @Param("organizationId") UUID organizationId);
}
