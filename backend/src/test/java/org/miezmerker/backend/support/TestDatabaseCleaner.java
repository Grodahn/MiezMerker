package org.miezmerker.backend.support;

import org.miezmerker.backend.repo.AppDeviceRepository;
import org.miezmerker.backend.repo.AppUserRepository;
import org.miezmerker.backend.repo.CatRepository;
import org.miezmerker.backend.repo.DerivedVisitRepository;
import org.miezmerker.backend.repo.FeedingSiteRepository;
import org.miezmerker.backend.repo.MembershipRepository;
import org.miezmerker.backend.repo.NodeDeploymentRepository;
import org.miezmerker.backend.repo.NodeRepository;
import org.miezmerker.backend.repo.OrganizationRepository;
import org.miezmerker.backend.repo.RawObservationRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Central FK-safe fixture cleanup for backend integration tests (#59).
 *
 * <p>Replaces repeated per-test {@code repository.deleteAll()} chains. Bulk
 * deletes ({@code deleteAllInBatch()}) skip the SELECT + N x DELETE round
 * trips of entity loading while keeping the same deletion semantics: no
 * entity callbacks exist on the domain, and foreign-key order is preserved
 * (referential integrity is never disabled).
 *
 * <p>Works identically on H2 and PostgreSQL (plain bulk JPQL deletes, no
 * {@code TRUNCATE}). Runs in a single transaction so cleanup is atomic.
 * Tests are not {@code @Transactional} themselves, so every repository call
 * uses its own persistence context; no stale managed entities can survive
 * across {@link #clean()}.
 */
@Component
public class TestDatabaseCleaner {
    private final DerivedVisitRepository derivedVisits;
    private final RawObservationRepository observations;
    private final NodeDeploymentRepository deployments;
    private final CatRepository cats;
    private final FeedingSiteRepository sites;
    private final AppDeviceRepository devices;
    private final NodeRepository nodes;
    private final MembershipRepository memberships;
    private final AppUserRepository users;
    private final OrganizationRepository organizations;

    public TestDatabaseCleaner(
            DerivedVisitRepository derivedVisits,
            RawObservationRepository observations,
            NodeDeploymentRepository deployments,
            CatRepository cats,
            FeedingSiteRepository sites,
            AppDeviceRepository devices,
            NodeRepository nodes,
            MembershipRepository memberships,
            AppUserRepository users,
            OrganizationRepository organizations) {
        this.derivedVisits = derivedVisits;
        this.observations = observations;
        this.deployments = deployments;
        this.cats = cats;
        this.sites = sites;
        this.devices = devices;
        this.nodes = nodes;
        this.memberships = memberships;
        this.users = users;
        this.organizations = organizations;
    }

    /** Deletes all fixture rows, children before parents. */
    @Transactional
    public void clean() {
        derivedVisits.deleteAllInBatch();
        observations.deleteAllInBatch();
        deployments.deleteAllInBatch();
        cats.deleteAllInBatch();
        sites.deleteAllInBatch();
        devices.deleteAllInBatch();
        nodes.deleteAllInBatch();
        memberships.deleteAllInBatch();
        users.deleteAllInBatch();
        organizations.deleteAllInBatch();
    }
}
