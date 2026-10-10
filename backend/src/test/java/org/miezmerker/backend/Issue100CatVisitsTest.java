package org.miezmerker.backend;

import org.junit.jupiter.api.Tag;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.security.interfaces.ECPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.miezmerker.backend.crypto.EcKeyUtils;
import org.miezmerker.backend.crypto.NodeClaimVerifier;
import org.miezmerker.backend.domain.AppUser;
import org.miezmerker.backend.domain.MembershipRole;
import org.miezmerker.backend.domain.MembershipStatus;
import org.miezmerker.backend.domain.Organization;
import org.miezmerker.backend.domain.OrganizationMembership;
import org.miezmerker.backend.repo.AppUserRepository;
import org.miezmerker.backend.repo.CatRepository;
import org.miezmerker.backend.repo.DerivedVisitRepository;
import org.miezmerker.backend.repo.FeedingSiteRepository;
import org.miezmerker.backend.repo.MembershipRepository;
import org.miezmerker.backend.repo.NodeDeploymentRepository;
import org.miezmerker.backend.repo.NodeRepository;
import org.miezmerker.backend.repo.OrganizationRepository;
import org.miezmerker.backend.repo.RawObservationRepository;
import org.miezmerker.backend.support.TestDatabaseCleaner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Acceptance tests for #100: user-facing cat/unknown-chip visit history.
 *
 * <p>Covers newest-first paginated visit history, latest reliable visit per
 * feeding site, unknown chips without a Cat entity, empty history, multiple
 * feeding sites, frozen historical site attribution, UNKNOWN-clock honesty,
 * observation-time vs server-receipt separation and organization isolation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Tag("visits")
class Issue100CatVisitsTest {
    @Autowired AppUserRepository users;
    @Autowired OrganizationRepository organizations;
    @Autowired MembershipRepository memberships;
    @Autowired NodeRepository nodes;
    @Autowired FeedingSiteRepository sites;
    @Autowired CatRepository cats;
    @Autowired NodeDeploymentRepository deployments;
    @Autowired RawObservationRepository observations;
    @Autowired DerivedVisitRepository derivedVisits;
    @Autowired PasswordEncoder passwords;
    @Autowired TestDatabaseCleaner cleaner;

    final ObjectMapper mapper = new ObjectMapper();
    HttpClient client;
    CookieManager cookies;

    @BeforeEach
    void http() {
        cookies = new CookieManager();
        client = HttpClient.newBuilder().cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(10)).build();
    }

    String base(String path) {
        return "http://localhost:" + port + path;
    }

    @org.springframework.beans.factory.annotation.Value("${local.server.port}") int port;

    String csrf() throws Exception {
        var res = client.send(HttpRequest.newBuilder(URI.create(base("/api/v1/auth/csrf")))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode());
        return mapper.readTree(res.body()).get("token").asText();
    }

    HttpResponse<String> post(String path, String json) throws Exception {
        String token = csrf();
        return client.send(HttpRequest.newBuilder(URI.create(base(path)))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", token)
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base(path))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    void login(String email, String password) throws Exception {
        var res = post("/api/v1/auth/login",
                "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
        assertEquals(200, res.statusCode(), res.body());
    }

    record Seed(Organization orgA, Organization orgB) {}

    Seed seed() {
        cleaner.clean();
        Organization orgA = organizations.save(new Organization("org-a100", "Org A", null));
        Organization orgB = organizations.save(new Organization("org-b100", "Org B", null));
        AppUser adminA = users.save(
                new AppUser("a100-admin-a@example.org", passwords.encode("supersecret-password-a")));
        AppUser memberA = users.save(new AppUser("a100-member-a@example.org",
                passwords.encode("supersecret-password-m")));
        AppUser adminB = users.save(
                new AppUser("a100-admin-b@example.org", passwords.encode("supersecret-password-b")));
        memberships.save(
                new OrganizationMembership(orgA, adminA, MembershipRole.ADMIN, MembershipStatus.ACTIVE));
        memberships.save(new OrganizationMembership(orgA, memberA, MembershipRole.MEMBER,
                MembershipStatus.ACTIVE));
        memberships.save(
                new OrganizationMembership(orgB, adminB, MembershipRole.ADMIN, MembershipStatus.ACTIVE));
        return new Seed(orgA, orgB);
    }

    record NodeKeys(UUID nodeId, KeyPair kp, String x, String y) {
        static NodeKeys fresh() {
            KeyPair kp = EcKeyUtils.generateP256();
            ECPublicKey pub = (ECPublicKey) kp.getPublic();
            return new NodeKeys(UUID.randomUUID(), kp, EcKeyUtils.xOf(pub),
                    EcKeyUtils.yOf(pub));
        }
    }

    NodeKeys claimNode(UUID orgId) throws Exception {
        NodeKeys n = NodeKeys.fresh();
        long ts = Instant.now().toEpochMilli();
        String sig = NodeClaimVerifier.signClaim(n.kp().getPrivate(), n.nodeId(), n.x(), n.y(), ts);
        String json = "{\"nodeId\":\"" + n.nodeId() + "\",\"publicKeyX\":\"" + n.x()
                + "\",\"publicKeyY\":\"" + n.y() + "\",\"firmwareVersion\":\"test-1\","
                + "\"organizationId\":\"" + orgId + "\",\"timestampMillis\":" + ts
                + ",\"claimSignature\":\"" + sig + "\"}";
        var res = post("/api/v1/nodes/claim", json);
        assertEquals(200, res.statusCode(), res.body());
        return n;
    }

    UUID createSite(UUID orgId, String name) throws Exception {
        var res = post("/api/v1/organizations/" + orgId + "/feeding-sites",
                mapper.writeValueAsString(Map.of("name", name)));
        assertEquals(200, res.statusCode(), res.body());
        return UUID.fromString(mapper.readTree(res.body()).get("id").asText());
    }

    UUID createCat(UUID orgId, String chip) throws Exception {
        var res = post("/api/v1/organizations/" + orgId + "/cats",
                mapper.writeValueAsString(Map.of("chipId", chip)));
        assertEquals(200, res.statusCode(), res.body());
        return UUID.fromString(mapper.readTree(res.body()).get("id").asText());
    }

    UUID createDeployment(UUID orgId, UUID nodeId, UUID siteId, Instant from, Instant until)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("nodeId", nodeId.toString());
        body.put("feedingSiteId", siteId.toString());
        body.put("validFrom", from.toString());
        if (until != null) {
            body.put("validUntil", until.toString());
        }
        var res = post("/api/v1/organizations/" + orgId + "/deployments",
                mapper.writeValueAsString(body));
        assertEquals(200, res.statusCode(), res.body());
        return UUID.fromString(mapper.readTree(res.body()).get("id").asText());
    }

    Map<String, Object> item(UUID nodeId, long seq, String chip, Long observedAt, String clock) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nodeId", nodeId.toString());
        m.put("sequence", seq);
        m.put("chipId", chip);
        m.put("observedAtMillis", observedAt);
        m.put("clockStatus", clock);
        return m;
    }

    JsonNode ingest(UUID orgId, List<Map<String, Object>> items) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("organizationId", orgId.toString());
        body.put("observations", items);
        var res = post("/api/v1/observations/ingest", mapper.writeValueAsString(body));
        assertEquals(200, res.statusCode(), res.body());
        return mapper.readTree(res.body());
    }

    JsonNode recompute(UUID orgId) throws Exception {
        var res = post("/api/v1/organizations/" + orgId + "/visits/recompute", "{}");
        assertEquals(200, res.statusCode(), res.body());
        return mapper.readTree(res.body());
    }

    JsonNode listVisits(UUID orgId, String query) throws Exception {
        var res = get("/api/v1/organizations/" + orgId + "/visits" + query);
        assertEquals(200, res.statusCode(), res.body());
        return mapper.readTree(res.body());
    }

    JsonNode latestPerSite(UUID orgId, String chipId) throws Exception {
        var res = get("/api/v1/organizations/" + orgId
                + "/visits/latest-per-site?chipId=" + chipId);
        assertEquals(200, res.statusCode(), res.body());
        return mapper.readTree(res.body());
    }

    @Test
    void knownCatHistoryPagesNewestFirstWithStableOrdering() throws Exception {
        Seed s = seed();
        login("a100-admin-a@example.org", "supersecret-password-a");
        NodeKeys n = claimNode(s.orgA().getId());
        UUID site = createSite(s.orgA().getId(), "Site");
        createDeployment(s.orgA().getId(), n.nodeId(), site,
                Instant.now().minus(Duration.ofDays(30)), null);
        long base = Instant.now().minus(Duration.ofDays(1)).toEpochMilli();
        // Three well-separated visits for one known cat.
        for (int i = 0; i < 3; i++) {
            ingest(s.orgA().getId(), List.of(item(n.nodeId(), i + 1, "chip-cat",
                    base + i * 600_000, "SYNCED")));
        }
        assertEquals(3, recompute(s.orgA().getId()).get("visitCount").asInt());
        createCat(s.orgA().getId(), "chip-cat");

        JsonNode newest = listVisits(s.orgA().getId(),
                "?chipId=chip-cat&limit=2&offset=0&newestFirst=true");
        assertEquals(2, newest.size());
        // Newest first: the latest visit leads, the older one follows.
        assertEquals(base + 1_200_000L, newest.get(0).get("startAtMillis").asLong());
        assertEquals(base + 600_000L, newest.get(1).get("startAtMillis").asLong());

        JsonNode older = listVisits(s.orgA().getId(),
                "?chipId=chip-cat&limit=2&offset=2&newestFirst=true");
        assertEquals(1, older.size());
        assertEquals(base, older.get(0).get("startAtMillis").asLong());

        // Same window without the flag keeps the historical ascending order.
        JsonNode ascending = listVisits(s.orgA().getId(), "?chipId=chip-cat&limit=2&offset=0");
        assertEquals(base, ascending.get(0).get("startAtMillis").asLong());
        assertEquals(base + 600_000L, ascending.get(1).get("startAtMillis").asLong());
    }

    @Test
    void unknownChipHistoryWorksWithoutCreatingACat() throws Exception {
        Seed s = seed();
        login("a100-admin-a@example.org", "supersecret-password-a");
        NodeKeys n = claimNode(s.orgA().getId());
        UUID site = createSite(s.orgA().getId(), "Site");
        createDeployment(s.orgA().getId(), n.nodeId(), site,
                Instant.now().minus(Duration.ofDays(30)), null);
        long base = Instant.now().minus(Duration.ofDays(1)).toEpochMilli();
        ingest(s.orgA().getId(), List.of(
                item(n.nodeId(), 1, "chip-unknown", base, "SYNCED"),
                item(n.nodeId(), 2, "chip-unknown", base + 10_000, "SYNCED")));
        assertEquals(1, recompute(s.orgA().getId()).get("visitCount").asInt());

        JsonNode visits = listVisits(s.orgA().getId(), "?chipId=chip-unknown&newestFirst=true");
        assertEquals(1, visits.size());
        assertTrue(visits.get(0).get("catId").isNull());
        assertEquals("CHIP-UNKNOWN", visits.get(0).get("chipId").asText());

        JsonNode perSite = latestPerSite(s.orgA().getId(), "chip-unknown");
        assertEquals(1, perSite.size());
        assertEquals(site.toString(), perSite.get(0).get("feedingSiteId").asText());
        assertEquals("Site", perSite.get(0).get("feedingSiteName").asText());
        assertEquals(base, perSite.get(0).get("latestVisitStartAtMillis").asLong());

        // No Cat entity was implicitly created for the unknown chip.
        assertEquals(0, cats.findByOrganizationId(s.orgA().getId()).size());
    }

    @Test
    void emptyHistoryIsEmptyForListAndLatestPerSite() throws Exception {
        Seed s = seed();
        login("a100-admin-a@example.org", "supersecret-password-a");
        NodeKeys n = claimNode(s.orgA().getId());
        UUID site = createSite(s.orgA().getId(), "Site");
        createDeployment(s.orgA().getId(), n.nodeId(), site,
                Instant.now().minus(Duration.ofDays(30)), null);
        // Only an UNKNOWN-clock read: never becomes a visit.
        ingest(s.orgA().getId(), List.of(item(n.nodeId(), 1, "chip-empty", null, "UNKNOWN")));
        assertEquals(0, recompute(s.orgA().getId()).get("visitCount").asInt());

        JsonNode visits = listVisits(s.orgA().getId(), "?chipId=chip-empty&newestFirst=true");
        assertEquals(0, visits.size());
        JsonNode perSite = latestPerSite(s.orgA().getId(), "chip-empty");
        assertEquals(0, perSite.size());
    }

    @Test
    void latestPerSiteSummarizesMultipleFeedingsSites() throws Exception {
        Seed s = seed();
        login("a100-admin-a@example.org", "supersecret-password-a");
        NodeKeys n1 = claimNode(s.orgA().getId());
        NodeKeys n2 = claimNode(s.orgA().getId());
        UUID siteA = createSite(s.orgA().getId(), "Site Alpha");
        UUID siteB = createSite(s.orgA().getId(), "Site Beta");
        Instant from = Instant.now().minus(Duration.ofDays(30));
        createDeployment(s.orgA().getId(), n1.nodeId(), siteA, from, null);
        createDeployment(s.orgA().getId(), n2.nodeId(), siteB, from, null);
        long base = Instant.now().minus(Duration.ofDays(1)).toEpochMilli();
        List<Map<String, Object>> items = new ArrayList<>();
        // Site Alpha: two visits (base, base+10min). Site Beta: one visit (base+5min).
        items.add(item(n1.nodeId(), 1, "chip-multi", base, "SYNCED"));
        items.add(item(n1.nodeId(), 2, "chip-multi", base + 600_000, "SYNCED"));
        items.add(item(n2.nodeId(), 1, "chip-multi", base + 300_000, "SYNCED"));
        ingest(s.orgA().getId(), items);
        assertEquals(3, recompute(s.orgA().getId()).get("visitCount").asInt());

        JsonNode perSite = latestPerSite(s.orgA().getId(), "chip-multi");
        assertEquals(2, perSite.size());
        // Each feeding site appears exactly once with its latest reliable visit.
        assertEquals(siteA.toString(), perSite.get(0).get("feedingSiteId").asText());
        assertEquals("Site Alpha", perSite.get(0).get("feedingSiteName").asText());
        assertEquals(base + 600_000L, perSite.get(0).get("latestVisitStartAtMillis").asLong());
        assertEquals(siteB.toString(), perSite.get(1).get("feedingSiteId").asText());
        assertEquals("Site Beta", perSite.get(1).get("feedingSiteName").asText());
        assertEquals(base + 300_000L, perSite.get(1).get("latestVisitStartAtMillis").asLong());
    }

    @Test
    void nodeMoveKeepsFrozenSiteInHistoryAndLatestPerSite() throws Exception {
        Seed s = seed();
        login("a100-admin-a@example.org", "supersecret-password-a");
        NodeKeys n = claimNode(s.orgA().getId());
        UUID siteA = createSite(s.orgA().getId(), "Old Site");
        UUID siteB = createSite(s.orgA().getId(), "New Site");
        Instant t0 = Instant.now().minus(Duration.ofDays(20));
        Instant t1 = Instant.now().minus(Duration.ofDays(10));
        createDeployment(s.orgA().getId(), n.nodeId(), siteA, t0, t1);
        createDeployment(s.orgA().getId(), n.nodeId(), siteB, t1, null);
        long before = t0.plus(Duration.ofDays(1)).toEpochMilli();
        long after = t1.plus(Duration.ofDays(1)).toEpochMilli();
        ingest(s.orgA().getId(), List.of(
                item(n.nodeId(), 1, "chip-move", before, "SYNCED"),
                item(n.nodeId(), 2, "chip-move", after, "SYNCED")));
        assertEquals(2, recompute(s.orgA().getId()).get("visitCount").asInt());

        JsonNode visits = listVisits(s.orgA().getId(), "?chipId=chip-move&newestFirst=true");
        assertEquals(2, visits.size());
        // Frozen attribution: the older visit keeps the old site even though the
        // node now lives at the new one.
        assertEquals(siteB.toString(), visits.get(0).get("feedingSiteId").asText());
        assertEquals(siteA.toString(), visits.get(1).get("feedingSiteId").asText());

        JsonNode perSite = latestPerSite(s.orgA().getId(), "chip-move");
        assertEquals(2, perSite.size());
        // Ordered by site name: "New Site" sorts before "Old Site".
        assertEquals(siteB.toString(), perSite.get(0).get("feedingSiteId").asText());
        assertEquals(after, perSite.get(0).get("latestVisitStartAtMillis").asLong());
        assertEquals(siteA.toString(), perSite.get(1).get("feedingSiteId").asText());
        assertEquals(before, perSite.get(1).get("latestVisitStartAtMillis").asLong());
    }

    @Test
    void unknownClockReadsAreExcludedHonestly() throws Exception {
        Seed s = seed();
        login("a100-admin-a@example.org", "supersecret-password-a");
        NodeKeys n = claimNode(s.orgA().getId());
        UUID site = createSite(s.orgA().getId(), "Site");
        createDeployment(s.orgA().getId(), n.nodeId(), site,
                Instant.now().minus(Duration.ofDays(30)), null);
        long base = Instant.now().minus(Duration.ofDays(1)).toEpochMilli();
        Map<String, Object> unknown = item(n.nodeId(), 1, "chip-mixed", null, "UNKNOWN");
        unknown.put("monotonicMs", 42L);
        ingest(s.orgA().getId(), List.of(
                unknown, item(n.nodeId(), 2, "chip-mixed", base, "SYNCED")));
        JsonNode out = recompute(s.orgA().getId());
        assertEquals(1, out.get("excludedUnknownClock").asInt());
        assertEquals(1, out.get("visitCount").asInt());

        JsonNode perSite = latestPerSite(s.orgA().getId(), "chip-mixed");
        assertEquals(1, perSite.size());
        // Only the reliable read defines the latest visit time.
        assertEquals(base, perSite.get(0).get("latestVisitStartAtMillis").asLong());
    }

    @Test
    void visitTimesAreObservationTimesNotServerReceipt() throws Exception {
        Seed s = seed();
        login("a100-admin-a@example.org", "supersecret-password-a");
        NodeKeys n = claimNode(s.orgA().getId());
        UUID site = createSite(s.orgA().getId(), "Site");
        createDeployment(s.orgA().getId(), n.nodeId(), site,
                Instant.now().minus(Duration.ofDays(30)), null);
        long base = Instant.now().minus(Duration.ofDays(1)).toEpochMilli();
        ingest(s.orgA().getId(), List.of(item(n.nodeId(), 1, "chip-time", base, "SYNCED")));
        assertEquals(1, recompute(s.orgA().getId()).get("visitCount").asInt());

        JsonNode visits = listVisits(s.orgA().getId(), "?chipId=chip-time");
        assertEquals(1, visits.size());
        JsonNode visit = visits.get(0);
        // Visit timing is the animal-observed time, never the backend receipt.
        assertEquals(base, visit.get("startAtMillis").asLong());
        assertEquals(base, visit.get("endAtMillis").asLong());
        assertFalse(visit.has("receivedAt"), "VisitView must not expose a receipt time");
        assertFalse(visit.has("receivedAtMillis"), "VisitView must not expose a receipt time");
    }

    @Test
    void latestPerSiteRequiresChipIdAndStaysTenantScoped() throws Exception {
        Seed s = seed();
        login("a100-admin-a@example.org", "supersecret-password-a");
        NodeKeys n = claimNode(s.orgA().getId());
        UUID site = createSite(s.orgA().getId(), "Site");
        createDeployment(s.orgA().getId(), n.nodeId(), site,
                Instant.now().minus(Duration.ofDays(30)), null);
        long base = Instant.now().minus(Duration.ofDays(1)).toEpochMilli();
        ingest(s.orgA().getId(), List.of(item(n.nodeId(), 1, "chip-iso", base, "SYNCED")));
        assertEquals(1, recompute(s.orgA().getId()).get("visitCount").asInt());

        assertEquals(400, get("/api/v1/organizations/" + s.orgA().getId()
                + "/visits/latest-per-site?chipId=").statusCode());
        assertEquals(400, get("/api/v1/organizations/" + s.orgA().getId()
                + "/visits/latest-per-site").statusCode());

        // Another tenant cannot read this organization's visit history.
        login("a100-admin-b@example.org", "supersecret-password-b");
        assertEquals(403, get("/api/v1/organizations/" + s.orgA().getId()
                + "/visits?chipId=chip-iso").statusCode());
        assertEquals(403, get("/api/v1/organizations/" + s.orgA().getId()
                + "/visits/latest-per-site?chipId=chip-iso").statusCode());
        assertEquals(0, latestPerSite(s.orgB().getId(), "chip-iso").size());
    }

    @Test
    void activeMemberCanReadHistoryAndLatestPerSite() throws Exception {
        Seed s = seed();
        login("a100-admin-a@example.org", "supersecret-password-a");
        NodeKeys n = claimNode(s.orgA().getId());
        UUID site = createSite(s.orgA().getId(), "Site");
        createDeployment(s.orgA().getId(), n.nodeId(), site,
                Instant.now().minus(Duration.ofDays(30)), null);
        long base = Instant.now().minus(Duration.ofDays(1)).toEpochMilli();
        ingest(s.orgA().getId(), List.of(item(n.nodeId(), 1, "chip-member", base, "SYNCED")));
        assertEquals(1, recompute(s.orgA().getId()).get("visitCount").asInt());

        login("a100-member-a@example.org", "supersecret-password-m");
        assertEquals(1, listVisits(s.orgA().getId(), "?chipId=chip-member").size());
        assertEquals(1, latestPerSite(s.orgA().getId(), "chip-member").size());
    }
}
