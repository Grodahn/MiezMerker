package org.miezmerker.backend;

import static org.junit.jupiter.api.Assertions.*;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.*;
import org.miezmerker.backend.domain.*;
import org.miezmerker.backend.repo.*;
import org.miezmerker.backend.service.*;
import org.miezmerker.backend.support.TestDatabaseCleaner;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Tag("auth")
@Tag("visits")
class SharedCareTest {
    @Value("${local.server.port}") int port;
    @Autowired AppUserRepository users;
    @Autowired OrganizationRepository organizations;
    @Autowired MembershipRepository memberships;
    @Autowired NodeRepository nodes;
    @Autowired FeedingSiteRepository sites;
    @Autowired NodeDeploymentRepository deployments;
    @Autowired RawObservationRepository observations;
    @Autowired DerivedVisitRepository visits;
    @Autowired CatRepository cats;
    @Autowired SharePolicyService policies;
    @Autowired SharedCareService shared;
    @Autowired VisitAggregationService aggregation;
    @Autowired PasswordEncoder passwords;
    @Autowired TestDatabaseCleaner cleaner;
    @Autowired jakarta.persistence.EntityManagerFactory entityFactory;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    final ObjectMapper json = new ObjectMapper();
    final Instant time = Instant.parse("2026-10-01T10:00:00Z");
    final String password = "shared-care-test-password";
    Organization a, b, c;
    AppUser reader, adminB, adminC;
    OrganizationMembership member;
    NodeDevice nodeA, nodeB;
    RawObservation proof, foreign;
    FeedingSite oldSite, newSite;
    HttpClient client;

    @BeforeEach void seed() {
        cleaner.clean();
        a = organization("a"); b = organization("b"); c = organization("c");
        reader = user("a"); adminB = user("b"); adminC = user("c");
        member = memberships.save(new OrganizationMembership(a, reader, MembershipRole.ADMIN, MembershipStatus.ACTIVE));
        memberships.save(new OrganizationMembership(b, adminB, MembershipRole.ADMIN, MembershipStatus.ACTIVE));
        memberships.save(new OrganizationMembership(c, adminC, MembershipRole.ADMIN, MembershipStatus.ACTIVE));
        nodeA = node(a); nodeB = node(b);
        // UNKNOWN clocks are still genuine chip observations, but never visit times.
        proof = observation(a, nodeA, 1, "00123", null, "UNKNOWN", null, null);
        oldSite = sites.save(new FeedingSite(b, "Historical bowl", "private description", 52.1, 13.2, "private address"));
        newSite = sites.save(new FeedingSite(b, "Current bowl", "private current", 53.1, 14.2, "private current address"));
        var oldDeployment = deployments.save(new NodeDeployment(b, nodeB, oldSite, time.minusSeconds(1), time.plusSeconds(7200)));
        var newDeployment = deployments.save(new NodeDeployment(b, nodeB, newSite, time.plusSeconds(7200), null));
        foreign = observation(b, nodeB, 1, "00123", time.toEpochMilli(), "SYNCED", oldSite, oldDeployment);
        observation(b, nodeB, 2, "00123", time.plusSeconds(3600).toEpochMilli(), "RTC_ONLY", oldSite, oldDeployment);
        observation(b, nodeB, 3, "00123", time.plusSeconds(7200).toEpochMilli(), "SYNCED", newSite, newDeployment);
        observation(b, nodeB, 4, "00123", null, "UNKNOWN", null, null);
        cats.save(new Cat(a, "00123", "Local authoritative name", "local status", "local private note"));
        cats.save(new Cat(b, "00123", "Foreign display name", "foreign status", "foreign private note"));
        aggregation.recompute(adminB.getId(), b.getId(), null, null);
        client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
    }
    Organization organization(String slug) {
        var org = new Organization(slug, "Organization " + slug, "private contact");
        org.setDiscoverable(true); return organizations.save(org);
    }
    AppUser user(String prefix) { return users.save(new AppUser(prefix + "@sharing.test", passwords.encode(password))); }
    NodeDevice node(Organization org) {
        var node = new NodeDevice(UUID.randomUUID(), "0".repeat(64), "1".repeat(64), UUID.randomUUID().toString(), "test");
        node.claim(org); return nodes.save(node);
    }
    RawObservation observation(Organization org, NodeDevice node, long sequence, String chip, Long at,
            String clock, FeedingSite site, NodeDeployment deployment) {
        return observations.save(new RawObservation(org, node, sequence, chip, at, clock, null, null, null, site, deployment));
    }
    void grant(ShareScope... scopes) { grantTo(adminB, b, a, scopes); }
    void grantTo(AppUser admin, Organization owner, Organization recipient, ShareScope... scopes) {
        for (var scope : scopes) policies.upsert(admin.getId(), owner.getId(), scope, ShareAudience.ALLOWLIST, List.of(recipient.getId()));
    }
    SharedCareViews.ProfilePage resolve() { return shared.resolve(reader.getId(), a.getId(), List.of(proof.getId()), 50, 0); }
    SharedCareViews.VisitPage page(int offset) { return shared.visits(reader.getId(), a.getId(), proof.getId(), b.getId(), 1, offset, true); }
    void denied(int status, org.junit.jupiter.api.function.Executable call) {
        assertEquals(status, assertThrows(ResponseStatusException.class, call).getStatusCode().value());
    }
    String route(Organization org, String action) { return "/api/v1/organizations/" + org.getId() + "/shared-care/" + action; }
    HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> post(String path, Object body) throws Exception {
        String token = json.readTree(get("/api/v1/auth/csrf").body()).get("token").asText();
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").header("X-XSRF-TOKEN", token)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }
    void login(AppUser user) throws Exception {
        var res = post("/api/v1/auth/login", Map.of("email", user.getEmail(), "password", password));
        assertEquals(200, res.statusCode(), res.body());
    }
    Map<String, Object> resolveBody() { return Map.of("ownObservationRefs", List.of(proof.getId())); }
    Map<String, Object> visitBody() { return Map.of("ownObservationRef", proof.getId(), "sourceOrganizationId", b.getId()); }

    @Test void defaultDenyAndHiddenOwnerAreIndistinguishable() {
        assertEquals(new SharedCareViews.ProfilePage(List.of(), null), resolve());
        assertEquals(new SharedCareViews.VisitPage(List.of(), null), page(0));
        grant(ShareScope.CARE, ShareScope.VISITS);
        b.setDiscoverable(false); organizations.save(b);
        assertTrue(resolve().items().isEmpty()); assertEquals(new SharedCareViews.VisitPage(List.of(), null), page(0));
        assertEquals(page(0), shared.visits(reader.getId(), a.getId(), proof.getId(), UUID.randomUUID(), 1, 0, true));
    }
    @Test void careProjectionPreservesLocalIdentityAndContainsOnlyAllowedFields() throws Exception {
        grant(ShareScope.CARE);
        var row = resolve().items().getFirst();
        assertEquals("00123", row.chipId()); assertEquals("Foreign display name", row.catDisplayName());
        assertEquals(b.getId(), row.source().organizationId());
        assertEquals("Local authoritative name", cats.findByOrganizationIdAndChipId(a.getId(), "00123").orElseThrow().getName());
        assertTrue(page(0).items().isEmpty());
        login(reader);
        var response = post(route(a, "resolve"), resolveBody());
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        var encoded = post(route(a, "resolve").replace("shared-care", "%73hared-care"), resolveBody());
        assertEquals(200, encoded.statusCode(), encoded.body());
        assertEquals("no-store", encoded.headers().firstValue("Cache-Control").orElseThrow());
        JsonNode item = json.readTree(response.body()).get("items").get(0);
        assertEquals(Set.of("chipId", "source", "catDisplayName"), item.propertyNames());
        assertEquals(Set.of("organizationId", "displayName"), item.get("source").propertyNames());
        for (String forbidden : List.of("private", "status", foreign.getId().toString(), oldSite.getId().toString(),
                cats.findByOrganizationIdAndChipId(b.getId(), "00123").orElseThrow().getId().toString()))
            assertFalse(response.body().contains(forbidden), response.body());
    }
    @Test void visitsRequireCareAndAdditionalScopeAndUseOnlyHistoricalLabels() throws Exception {
        grant(ShareScope.VISITS, ShareScope.SITE_LABEL);
        assertTrue(page(0).items().isEmpty()); assertTrue(resolve().items().isEmpty());
        grant(ShareScope.CARE); policies.revoke(adminB.getId(), b.getId(), ShareScope.SITE_LABEL);
        assertEquals(time.plusSeconds(7200).toString(), page(0).items().getFirst().startAt());
        assertNull(page(0).items().getFirst().siteDisplayName());
        grant(ShareScope.SITE_LABEL);
        assertEquals("Current bowl", page(0).items().getFirst().siteDisplayName());
        assertEquals("Historical bowl", page(1).items().getFirst().siteDisplayName());
        assertEquals("Historical bowl", page(2).items().getFirst().siteDisplayName());
        login(reader); var response = post(route(a, "visits"), visitBody());
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(Set.of("source", "startAt", "endAt", "siteDisplayName"), json.readTree(response.body()).get("items").get(0).propertyNames());
        for (String secret : List.of("private", "location", "latitude", "observation", "catId", "52.1", foreign.getId().toString()))
            assertFalse(response.body().contains(secret), response.body());
    }
    @Test void allDiscoverableAndPrivateAudiencesFollowTheExistingPolicyResolver() {
        for (ShareScope scope : ShareScope.values())
            policies.upsert(adminB.getId(), b.getId(), scope, ShareAudience.ALL_DISCOVERABLE, null);
        assertEquals(Set.of(ShareScope.CARE, ShareScope.VISITS, ShareScope.SITE_LABEL, ShareScope.PHOTO),
                policies.resolveEffectiveScopes(a.getId(), b.getId()));
        assertEquals(1, resolve().items().size()); assertNotNull(page(0).items().getFirst().siteDisplayName());
        policies.upsert(adminB.getId(), b.getId(), ShareScope.CARE, ShareAudience.PRIVATE, null);
        assertTrue(policies.resolveEffectiveScopes(a.getId(), b.getId()).isEmpty());
        assertTrue(resolve().items().isEmpty()); assertTrue(page(0).items().isEmpty());
    }
    @Test void fabricatedForeignAndMixedProofsFailBeforeForeignDiscovery() {
        grant(ShareScope.CARE, ShareScope.VISITS);
        for (UUID ref : List.of(UUID.randomUUID(), foreign.getId())) {
            denied(400, () -> shared.resolve(reader.getId(), a.getId(), List.of(ref), 50, 0));
            denied(400, () -> shared.visits(reader.getId(), a.getId(), ref, b.getId(), 50, 0, true));
            denied(400, () -> shared.resolve(reader.getId(), a.getId(), List.of(proof.getId(), ref), 50, 0));
        }
        // Even an incorrectly persisted observation cannot prove ownership of a foreign Node.
        var mismatched = observation(a, nodeB, 100, "00123", null, "UNKNOWN", null, null);
        denied(400, () -> shared.resolve(reader.getId(), a.getId(), List.of(mismatched.getId()), 50, 0));
        jdbc.update("update nodes set state = 'UNCLAIMED' where node_id = ?", nodeA.getNodeId());
        denied(400, this::resolve);
    }
    @Test void manuallyCreatedCatIsNotObservationProofAndArbitraryChipInputIsRejected() throws Exception {
        grant(ShareScope.CARE);
        var guessed = cats.save(new Cat(a, "guessed", "Guessed profile", null, null));
        cats.save(new Cat(b, "guessed", "Secret guessed cat", null, null));
        denied(400, () -> shared.resolve(reader.getId(), a.getId(), List.of(guessed.getId()), 50, 0));
        assertEquals(1, resolve().items().size());
        login(reader);
        var response = post(route(a, "resolve"), Map.of("chipIds", List.of("guessed")));
        assertEquals(400, response.statusCode());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(400, post(route(a, "resolve"), Map.of("ownObservationRefs", List.of())).statusCode());
    }
    @Test void grantsToCDoNotAuthorizeAAndThereIsNoReciprocityOrTransitivity() {
        grantTo(adminB, b, c, ShareScope.CARE, ShareScope.VISITS);
        grantTo(adminC, c, a, ShareScope.CARE, ShareScope.VISITS);
        grantTo(reader, a, b, ShareScope.CARE, ShareScope.VISITS);
        assertTrue(resolve().items().isEmpty()); assertTrue(page(0).items().isEmpty());
        grant(ShareScope.CARE);
        assertEquals(1, resolve().items().size()); assertTrue(page(0).items().isEmpty());
    }
    @Test void revocationImmediatelyBlocksLaterPagesAndSiteLabels() {
        grant(ShareScope.CARE, ShareScope.VISITS, ShareScope.SITE_LABEL);
        assertEquals(1, page(0).nextOffset());
        policies.revoke(adminB.getId(), b.getId(), ShareScope.SITE_LABEL);
        assertNull(page(1).items().getFirst().siteDisplayName());
        policies.revoke(adminB.getId(), b.getId(), ShareScope.VISITS);
        assertEquals(new SharedCareViews.VisitPage(List.of(), null), page(1));
        assertEquals(1, resolve().items().size());
        policies.revoke(adminB.getId(), b.getId(), ShareScope.CARE);
        assertTrue(resolve().items().isEmpty()); assertEquals(new SharedCareViews.VisitPage(List.of(), null), page(2));
    }
    @Test void disabledAndPendingMembershipUserAndOrganizationAreRejected() {
        grant(ShareScope.CARE, ShareScope.VISITS);
        for (var status : List.of(MembershipStatus.DISABLED, MembershipStatus.PENDING)) {
            member.setStatusDirect(status); memberships.save(member);
            denied(403, this::resolve); denied(403, () -> page(0));
        }
        member.setStatusDirect(MembershipStatus.ACTIVE); memberships.save(member);
        reader.setStatus(UserStatus.DISABLED); users.save(reader); denied(403, this::resolve);
        reader.setStatus(UserStatus.ACTIVE); users.save(reader);
        a.setStatus(OrganizationStatus.DISABLED); organizations.save(a); denied(403, this::resolve);
    }
    @Test void inactiveOrHiddenRecipientAndInactiveOwnerHaveNoEffectiveSharing() {
        grant(ShareScope.CARE, ShareScope.VISITS);
        b.setStatus(OrganizationStatus.DISABLED); organizations.save(b);
        assertTrue(resolve().items().isEmpty()); assertTrue(page(0).items().isEmpty());
        b.setStatus(OrganizationStatus.ACTIVE); organizations.save(b);
        a.setDiscoverable(false); organizations.save(a);
        assertTrue(resolve().items().isEmpty()); assertTrue(page(0).items().isEmpty());
    }
    @Test void sysadminAloneHasNoTenantAuthorityAndOrganizationSwitchCannotTransferProof() {
        var sysadmin = user("system");
        jdbc.update("insert into app_user_system_roles (user_id, role, enabled) values (?, 'SYSADMIN', true)", sysadmin.getId());
        grant(ShareScope.CARE, ShareScope.VISITS);
        denied(403, () -> shared.resolve(sysadmin.getId(), a.getId(), List.of(proof.getId()), 50, 0));
        denied(403, () -> shared.visits(sysadmin.getId(), a.getId(), proof.getId(), b.getId(), 50, 0, true));
        memberships.save(new OrganizationMembership(b, reader, MembershipRole.MEMBER, MembershipStatus.ACTIVE));
        memberships.save(new OrganizationMembership(c, reader, MembershipRole.MEMBER, MembershipStatus.ACTIVE));
        denied(400, () -> shared.resolve(reader.getId(), b.getId(), List.of(proof.getId()), 50, 0));
        assertTrue(shared.resolve(reader.getId(), b.getId(), List.of(foreign.getId()), 50, 0).items().isEmpty());
        denied(400, () -> shared.visits(reader.getId(), c.getId(), proof.getId(), b.getId(), 50, 0, true));
        assertEquals(1, resolve().items().size());
    }
    @Test void unknownClocksNeverBecomePreciseVisitsAndReceivedAtIsNeverObservationTime() {
        grant(ShareScope.CARE, ShareScope.VISITS);
        var all = shared.visits(reader.getId(), a.getId(), proof.getId(), b.getId(), 50, 0, false);
        assertEquals(3, all.items().size()); assertEquals(time.toString(), all.items().getFirst().startAt());
        assertNotEquals(foreign.getReceivedAt().toString(), all.items().getFirst().startAt());
        var unknown = observation(b, nodeB, 10, "00123", null, "UNKNOWN", oldSite, null);
        visits.save(new DerivedVisit(b, oldSite, "00123", null, time.plusSeconds(10000), time.plusSeconds(10001), 1,
                "legacy", 120, unknown, unknown));
        assertEquals(3, shared.visits(reader.getId(), a.getId(), proof.getId(), b.getId(), 50, 0, false).items().size());
        assertEquals("UNKNOWN", observations.findById(proof.getId()).orElseThrow().getClockStatus());
    }
    @Test void paginationIsDeterministicAndManipulatingOffsetOrSourceCannotAuthorizeData() throws Exception {
        grant(ShareScope.CARE, ShareScope.VISITS);
        var first = page(0); var second = page(first.nextOffset()); var third = page(second.nextOffset());
        assertEquals(first, page(0)); assertNull(third.nextOffset());
        assertEquals(time.toString(), third.items().getFirst().startAt());
        assertTrue(page(9999).items().isEmpty());
        assertTrue(shared.visits(reader.getId(), a.getId(), proof.getId(), c.getId(), 1, 1, true).items().isEmpty());
        for (int offset : List.of(-1, 10001)) denied(400, () -> page(offset));
        login(reader);
        var body = new HashMap<String, Object>(visitBody()); body.put("offset", -1);
        assertEquals(400, post(route(a, "visits"), body).statusCode());
        body.put("offset", 1); body.put("cursor", b.getId().toString());
        policies.revoke(adminB.getId(), b.getId(), ShareScope.CARE);
        var response = post(route(a, "visits"), body);
        // Cursor is never authority, regardless of unknown-field deserialization policy.
        assertTrue(response.statusCode() == 400 || (response.statusCode() == 200 && json.readTree(response.body()).get("items").isEmpty()));
    }
    @Test void resolutionAlsoPagesOnlyGrantedProfilesAndPhotoScopeDoesNotCreateMediaUrls() {
        cats.save(new Cat(c, "00123", "C display", null, "C private"));
        grant(ShareScope.CARE, ShareScope.PHOTO);
        grantTo(adminC, c, a, ShareScope.CARE);
        var one = shared.resolve(reader.getId(), a.getId(), List.of(proof.getId()), 1, 0);
        var two = shared.resolve(reader.getId(), a.getId(), List.of(proof.getId()), 1, one.nextOffset());
        assertNull(two.nextOffset()); assertNotEquals(one.items().getFirst().source(), two.items().getFirst().source());
        assertEquals(2, resolve().items().size());
        policies.revoke(adminC.getId(), c.getId(), ShareScope.CARE);
        assertNull(shared.resolve(reader.getId(), a.getId(), List.of(proof.getId()), 1, 0).nextOffset());
    }
    @Test void boundedBatchesAndManySourceOrganizationsUseConstantNumberOfStatements() {
        grant(ShareScope.CARE);
        var refs = new ArrayList<UUID>(); refs.add(proof.getId());
        for (int i = 1; i < 50; i++) {
            String chip = "BATCH-" + i;
            refs.add(observation(a, nodeA, i + 1, chip, null, "UNKNOWN", null, null).getId());
            cats.save(new Cat(b, chip, "Shared " + i, null, "private"));
        }
        for (int i = 0; i < 20; i++) {
            var source = organization("many-" + i);
            memberships.save(new OrganizationMembership(source, adminB, MembershipRole.ADMIN, MembershipStatus.ACTIVE));
            cats.save(new Cat(source, "00123", "Many " + i, null, "private"));
            grantTo(adminB, source, a, ShareScope.CARE);
        }
        var stats = entityFactory.unwrap(SessionFactory.class).getStatistics(); stats.setStatisticsEnabled(true);
        try {
            stats.clear(); assertEquals(21, resolve().items().size()); long small = stats.getPrepareStatementCount();
            stats.clear(); var result = shared.resolve(reader.getId(), a.getId(), refs, 100, 0);
            assertEquals(70, result.items().size()); assertNull(result.nextOffset());
            assertEquals(small, stats.getPrepareStatementCount()); assertTrue(small <= 6, "Queries: " + small);
        } finally { stats.setStatisticsEnabled(false); }
        refs.add(UUID.randomUUID()); denied(400, () -> shared.resolve(reader.getId(), a.getId(), refs, 100, 0));
        denied(400, () -> shared.resolve(reader.getId(), a.getId(), List.of(proof.getId(), proof.getId()), 100, 0));
        denied(400, () -> shared.resolve(reader.getId(), a.getId(), List.of(proof.getId()), 101, 0));
    }
    @Test void authenticationCsrfAndNoStoreProtectSuccessAndErrors() throws Exception {
        var anonymous = post(route(a, "resolve"), resolveBody());
        assertEquals(401, anonymous.statusCode());
        assertEquals("no-store", anonymous.headers().firstValue("Cache-Control").orElseThrow());
        login(reader);
        var missingCsrf = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + route(a, "resolve")))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(resolveBody()))).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, missingCsrf.statusCode());
        assertEquals("no-store", missingCsrf.headers().firstValue("Cache-Control").orElseThrow());
        var invalid = post(route(a, "resolve"), Map.of("ownObservationRefs", List.of(foreign.getId())));
        assertEquals(400, invalid.statusCode()); assertEquals("no-store", invalid.headers().firstValue("Cache-Control").orElseThrow());
        reader.setStatus(UserStatus.DISABLED); users.save(reader);
        assertEquals(403, post(route(a, "resolve"), resolveBody()).statusCode());
    }
    @Test void oversizedAndChunkedBodiesAreBoundedBeforeDeserialization() throws Exception {
        login(reader);
        for (boolean chunked : List.of(false, true)) {
            String token = json.readTree(get("/api/v1/auth/csrf").body()).get("token").asText();
            byte[] bytes = (json.writeValueAsString(resolveBody()) + " ".repeat(17000)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var publisher = chunked ? HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(bytes))
                    : HttpRequest.BodyPublishers.ofByteArray(bytes);
            var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + route(a, "resolve")))
                    .header("Content-Type", "application/json").header("X-XSRF-TOKEN", token)
                    .POST(publisher).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(413, response.statusCode(), response.body());
            assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        }
    }
    @Test void provisioningHintsDoNotRevealHiddenOrDisabledSourceOrganizations() throws Exception {
        b.setDiscoverable(false); organizations.save(b);
        var hidden = get("/api/v1/nodes/" + nodeB.getNodeId() + "/owner");
        assertEquals(200, hidden.statusCode());
        assertEquals("CLAIMED", json.readTree(hidden.body()).get("state").asText());
        for (String field : List.of("organizationId", "organizationSlug", "organizationName", "publicContact"))
            assertTrue(json.readTree(hidden.body()).get(field).isNull(), hidden.body());
        b.setDiscoverable(true); b.setStatus(OrganizationStatus.DISABLED); organizations.save(b);
        assertEquals(hidden.body(), get("/api/v1/nodes/" + nodeB.getNodeId() + "/owner").body());
    }
    @Test void rateLimitIsSharedAcrossOrganizationsAndRoutes() {
        for (int i = 0; i < 120; i++) resolve();
        denied(429, () -> page(0));
        memberships.save(new OrganizationMembership(b, reader, MembershipRole.MEMBER, MembershipStatus.ACTIVE));
        denied(429, () -> shared.resolve(reader.getId(), b.getId(), List.of(foreign.getId()), 50, 0));
    }
}
