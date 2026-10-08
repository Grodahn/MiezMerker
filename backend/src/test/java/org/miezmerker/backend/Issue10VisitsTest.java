package org.miezmerker.backend;

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
 * Acceptance tests for #10: deterministic backend-side visit aggregation
 * ({@code visit-gap-v1}) from immutable raw observations.
 *
 * <p>Covers grouping, gap boundary ({@code gap <= threshold}), clock policy,
 * historical deployment attribution, tenant isolation, determinism, late data,
 * idempotent recompute and table-driven thresholds.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class Issue10VisitsTest {
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
        Organization orgA = organizations.save(new Organization("org-a10", "Org A", null));
        Organization orgB = organizations.save(new Organization("org-b10", "Org B", null));
        AppUser adminA = users.save(
                new AppUser("a10-admin-a@example.org", passwords.encode("supersecret-password-a")));
        AppUser memberA = users.save(new AppUser("a10-member-a@example.org",
                passwords.encode("supersecret-password-m")));
        AppUser adminB = users.save(
                new AppUser("a10-admin-b@example.org", passwords.encode("supersecret-password-b")));
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

    JsonNode recompute(UUID orgId, Integer gapSeconds, String algorithm) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        if (gapSeconds != null) {
            body.put("gapSeconds", gapSeconds);
        }
        if (algorithm != null) {
            body.put("algorithmVersion", algorithm);
        }
        var res = post("/api/v1/organizations/" + orgId + "/visits/recompute",
                mapper.writeValueAsString(body));
        assertEquals(200, res.statusCode(), res.body());
        return mapper.readTree(res.body());
    }

    JsonNode recomputeDefault(UUID orgId) throws Exception {
        return recompute(orgId, null, null);
    }

    JsonNode listVisits(UUID orgId) throws Exception {
        var res = get("/api/v1/organizations/" + orgId + "/visits?limit=1000");
        assertEquals(200, res.statusCode(), res.body());
        return mapper.readTree(res.body());
    }

    // ---- core example ----

    @Test
    void issueExampleYieldsTwoVisitsAtSixtySeconds() throws Exception {
        Seed s = seed();
        login("a10-admin-a@example.org", "supersecret-password-a");
        NodeKeys n = claimNode(s.orgA().getId());
        UUID site = createSite(s.orgA().getId(), "Site");
        createDeployment(s.orgA().getId(), n.nodeId(), site,
                Instant.now().minus(Duration.ofDays(30)), null);
        long base = Instant.now().minus(Duration.ofDays(1)).toEpochMilli();
        // 19:21:01, +1s, +3s, +49s, +230s -> gaps 1,2,46,181 seconds.
        long t0 = base;
        List<Map<String, Object>> items = List.of(
                item(n.nodeId(), 1, "chip-A", t0, "SYNCED"),
                item(n.nodeId(), 2, "chip-A", t0 + 1_000, "SYNCED"),
                item(n.nodeId(), 3, "chip-A", t0 + 3_000, "SYNCED"),
                item(n.nodeId(), 4, "chip-A", t0 + 49_000, "SYNCED"),
                item(n.nodeId(), 5, "chip-A", t0 + 230_000, "SYNCED"));
        assertEquals(5, ingest(s.orgA().getId(), items).get("inserted").asInt());

        JsonNode out = recompute(s.orgA().getId(), 60, "visit-gap-v1");
        assertEquals("visit-gap-v1", out.get("algorithmVersion").asText());
        assertEquals(60, out.get("gapSeconds").asInt());
        assertEquals(2, out.get("visitCount").asInt());
        assertEquals(5, out.get("totalObservations").asInt());
        assertEquals(5, out.get("usableObservations").asInt());

        JsonNode visits = listVisits(s.orgA().getId());
        assertEquals(2, visits.size());
        assertEquals(4, visits.get(0).get("observationCount").asInt());
        assertEquals(1, visits.get(1).get("observationCount").asInt());
        assertEquals(t0, visits.get(0).get("startAtMillis").asLong());
        assertEquals(t0 + 49_000, visits.get(0).get("endAtMillis").asLong());
        assertEquals(t0 + 230_000, visits.get(1).get("startAtMillis").asLong());
        assertEquals("visit-gap-v1", visits.get(0).get("algorithmVersion").asText());
        assertEquals(60, visits.get(0).get("gapSeconds").asInt());
        assertEquals(site.toString(), visits.get(0).get("feedingSiteId").asText());
        assertEquals("CHIP-A", visits.get(0).get("chipId").asText());
        assertNotNull(visits.get(0).get("firstObservationId").asText());
        assertNotNull(visits.get(0).get("lastObservationId").asText());
        assertNotEquals(visits.get(0).get("firstObservationId").asText(),
                visits.get(1).get("firstObservationId").asText());
        assertTrue(visits.get(0).get("catId").isNull());

        // Raw observations unchanged by visit building.
        assertEquals(5, observations.count());
        var first = observations.findByNodeNodeIdAndSequence(n.nodeId(), 1).orElseThrow();
        assertEquals(t0, first.getObservedAtMs());
        assertEquals("SYNCED", first.getClockStatus());

        // Raw and visits are separately listable.
        var raw = get("/api/v1/observations?organizationId=" + s.orgA().getId());
        assertEquals(200, raw.statusCode());
        assertEquals(5, mapper.readTree(raw.body()).size());
    }

    @Test
    void tableDrivenThresholdsAreParametrized() throws Exception {
        // Gaps: 4s, 20s, 50s, 100s. Expected visits:
        // 5s->4, 30s->3, 60s->2, 90s->2, 120s->1.
        int[] gaps = {5, 30, 60, 90, 120};
        int[] expected = {4, 3, 2, 2, 1};
        for (int i = 0; i < gaps.length; i++) {
            Seed s = seed();
            login("a10-admin-a@example.org", "supersecret-password-a");
            NodeKeys n = claimNode(s.orgA().getId());
            UUID site = createSite(s.orgA().getId(), "Site");
            createDeployment(s.orgA().getId(), n.nodeId(), site,
                    Instant.now().minus(Duration.ofDays(30)), null);
            long base = Instant.now().minus(Duration.ofDays(1)).toEpochMilli();
            long[] times = {base, base + 4_000, base + 24_000, base + 74_000,
                    base + 174_000};
            List<Map<String, Object>> items = new ArrayList<>();
            for (int k = 0; k < times.length; k++) {
                items.add(item(n.nodeId(), k + 1, "chip-T", times[k], "SYNCED"));
            }
            ingest(s.orgA().getId(), items);
            JsonNode out = recompute(s.orgA().getId(), gaps[i], null);
            assertEquals(expected[i], out.get("visitCount").asInt(),
                    "gap " + gaps[i] + "s");
            assertEquals(gaps[i], out.get("gapSeconds").asInt());
        }
    }

    @Test
    void boundaryGapIsInclusive() throws Exception {
        Seed s = seed();
        login("a10-admin-a@example.org", "supersecret-password-a");
        NodeKeys n = claimNode(s.orgA().getId());
        UUID site = createSite(s.orgA().getId(), "Site");
        createDeployment(s.orgA().getId(), n.nodeId(), site,
                Instant.now().minus(Duration.ofDays(30)), null);
        long base = Instant.now().minus(Duration.ofDays(1)).toEpochMilli();

        // Exactly 60_000ms gap stays in the same visit (gap <= threshold).
        ingest(s.orgA().getId(), List.of(
                item(n.nodeId(), 1, "chip-B", base, "SYNCED"),
                item(n.nodeId(), 2, "chip-B", base + 60_000, "SYNCED")));
        assertEquals(1, recompute(s.orgA().getId(), 60, null).get("visitCount").asInt());
        JsonNode one = listVisits(s.orgA().getId());
        assertEquals(1, one.size());
        assertEquals(2, one.get(0).get("observationCount").asInt());

        // 60_001ms gap splits (gap > threshold).
        Seed s2 = seed();
        login("a10-admin-a@example.org", "supersecret-password-a");
        NodeKeys n2 = claimNode(s2.orgA().getId());
        UUID site2 = createSite(s2.orgA().getId(), "Site");
        createDeployment(s2.orgA().getId(), n2.nodeId(), site2,
                Instant.now().minus(Duration.ofDays(30)), null);
        ingest(s2.orgA().getId(), List.of(
                item(n2.nodeId(), 1, "chip-B", base, "SYNCED"),
                item(n2.nodeId(), 2, "chip-B", base + 60_001, "SYNCED")));
        assertEquals(2, recompute(s2.orgA().getId(), 60, null).get("visitCount").asInt());
    }

    @Test
    void alternatingCatsStayIndependent() throws Exception {
        Seed s = seed();
        login("a10-admin-a@example.org", "supersecret-password-a");
        NodeKeys n = claimNode(s.orgA().getId());
        UUID site = createSite(s.orgA().getId(), "Site");
        createDeployment(s.orgA().getId(), n.nodeId(), site,
                Instant.now().minus(Duration.ofDays(30)), null);
        long base = Instant.now().minus(Duration.ofDays(1)).toEpochMilli();
        List<Map<String, Object>> items = List.of(
                item(n.nodeId(), 1, "chip-A", base, "SYNCED"),
                item(n.nodeId(), 2, "chip-B", base + 5_000, "SYNCED"),
                item(n.nodeId(), 3, "chip-A", base + 10_000, "SYNCED"),
                item(n.nodeId(), 4, "chip-B", base + 15_000, "SYNCED"),
                item(n.nodeId(), 5, "chip-A", base + 20_000, "SYNCED"),
                item(n.nodeId(), 6, "chip-B", base + 25_000, "SYNCED"));
        ingest(s.orgA().getId(), items);
        JsonNode out = recompute(s.orgA().getId(), 60, null);
        assertEquals(2, out.get("visitCount").asInt());
        JsonNode visits = listVisits(s.orgA().getId());
        for (JsonNode v : visits) {
            assertEquals(3, v.get("observationCount").asInt());
        }
    }

    @Test
    void sameCatAtDifferentSitesStaysSeparate() throws Exception {
        Seed s = seed();
        login("a10-admin-a@example.org", "supersecret-password-a");
        NodeKeys n1 = claimNode(s.orgA().getId());
        NodeKeys n2 = claimNode(s.orgA().getId());
        UUID siteA = createSite(s.orgA().getId(), "Site A");
        UUID siteB = createSite(s.orgA().getId(), "Site B");
        Instant from = Instant.now().minus(Duration.ofDays(30));
        createDeployment(s.orgA().getId(), n1.nodeId(), siteA, from, null);
        createDeployment(s.orgA().getId(), n2.nodeId(), siteB, from, null);
        long base = Instant.now().minus(Duration.ofDays(1)).toEpochMilli();
        ingest(s.orgA().getId(), List.of(
                item(n1.nodeId(), 1, "chip-X", base, "SYNCED"),
                item(n2.nodeId(), 1, "chip-X", base + 5_000, "SYNCED"),
                item(n1.nodeId(), 2, "chip-X", base + 10_000, "SYNCED")));
        assertEquals(2, recompute(s.orgA().getId(), 60, null).get("visitCount").asInt());
        JsonNode visits = listVisits(s.orgA().getId());
        assertEquals(2, visits.size());
        // One visit per site even though times overlap.
        int atA = 0;
        for (JsonNode v : visits) {
            if (v.get("feedingSiteId").asText().equals(siteA.toString())) {
                atA++;
            }
        }
        assertEquals(1, atA);
    }

    @Test
    void sameChipInTwoOrganizationsStaysIndependent() throws Exception {
        Seed s = seed();
        login("a10-admin-a@example.org", "supersecret-password-a");
        NodeKeys nodeA = claimNode(s.orgA().getId());
        UUID siteA = createSite(s.orgA().getId(), "Site A");
        createDeployment(s.orgA().getId(), nodeA.nodeId(), siteA,
                Instant.now().minus(Duration.ofDays(30)), null);
        login("a10-admin-b@example.org", "supersecret-password-b");
        NodeKeys nodeB = claimNode(s.orgB().getId());
        UUID siteB = createSite(s.orgB().getId(), "Site B");
        createDeployment(s.orgB().getId(), nodeB.nodeId(), siteB,
                Instant.now().minus(Duration.ofDays(30)), null);
        long base = Instant.now().minus(Duration.ofDays(1)).toEpochMilli();
        login("a10-admin-a@example.org", "supersecret-password-a");
        ingest(s.orgA().getId(), List.of(
                item(nodeA.nodeId(), 1, "shared-chip", base, "SYNCED"),
                item(nodeA.nodeId(), 2, "shared-chip", base + 10_000, "SYNCED")));
        login("a10-admin-b@example.org", "supersecret-password-b");
        ingest(s.orgB().getId(), List.of(
                item(nodeB.nodeId(), 1, "shared-chip", base + 200_000, "SYNCED")));

        login("a10-admin-a@example.org", "supersecret-password-a");
        assertEquals(1, recompute(s.orgA().getId(), 60, null).get("visitCount").asInt());
        login("a10-admin-b@example.org", "supersecret-password-b");
        assertEquals(1, recompute(s.orgB().getId(), 60, null).get("visitCount").asInt());

        login("a10-admin-a@example.org", "supersecret-password-a");
        JsonNode visitsA = listVisits(s.orgA().getId());
        assertEquals(1, visitsA.size());
        assertEquals(2, visitsA.get(0).get("observationCount").asInt());
        assertEquals(siteA.toString(), visitsA.get(0).get("feedingSiteId").asText());
        login("a10-admin-b@example.org", "supersecret-password-b");
        JsonNode visitsB = listVisits(s.orgB().getId());
        assertEquals(1, visitsB.size());
        assertEquals(1, visitsB.get(0).get("observationCount").asInt());
        assertEquals(siteB.toString(), visitsB.get(0).get("feedingSiteId").asText());
    }

    @Test
    void nodeMoveKeepsHistoricalVisits() throws Exception {
        Seed s = seed();
        login("a10-admin-a@example.org", "supersecret-password-a");
        NodeKeys n = claimNode(s.orgA().getId());
        UUID siteA = createSite(s.orgA().getId(), "Site A");
        UUID siteB = createSite(s.orgA().getId(), "Site B");
        Instant t0 = Instant.now().minus(Duration.ofDays(20));
        Instant t1 = Instant.now().minus(Duration.ofDays(10));
        createDeployment(s.orgA().getId(), n.nodeId(), siteA, t0, t1);
        createDeployment(s.orgA().getId(), n.nodeId(), siteB, t1, null);
        long before = t0.plus(Duration.ofDays(1)).toEpochMilli();
        long after = t1.plus(Duration.ofDays(1)).toEpochMilli();
        ingest(s.orgA().getId(), List.of(
                item(n.nodeId(), 1, "chip-M", before, "SYNCED"),
                item(n.nodeId(), 2, "chip-M", before + 10_000, "SYNCED"),
                item(n.nodeId(), 3, "chip-M", after, "SYNCED")));
        assertEquals(2, recompute(s.orgA().getId(), 60, null).get("visitCount").asInt());
        JsonNode visits = listVisits(s.orgA().getId());
        assertEquals(2, visits.size());
        // Historical attribution frozen at ingest; recompute after the move
        // cannot reinterpret old rows via the current site.
        assertEquals(2, recompute(s.orgA().getId(), 60, null).get("visitCount").asInt());
        JsonNode again = listVisits(s.orgA().getId());
        assertEquals(2, again.size());
        assertEquals(siteA.toString(), again.get(0).get("feedingSiteId").asText());
        assertEquals(siteB.toString(), again.get(1).get("feedingSiteId").asText());
    }

    @Test
    void lateArrivalMergesIntoCorrectVisit() throws Exception {
        Seed s = seed();
        login("a10-admin-a@example.org", "supersecret-password-a");
        NodeKeys n = claimNode(s.orgA().getId());
        UUID site = createSite(s.orgA().getId(), "Site");
        createDeployment(s.orgA().getId(), n.nodeId(), site,
                Instant.now().minus(Duration.ofDays(30)), null);
        long base = Instant.now().minus(Duration.ofDays(1)).toEpochMilli();
        ingest(s.orgA().getId(), List.of(
                item(n.nodeId(), 1, "chip-L", base, "SYNCED"),
                item(n.nodeId(), 3, "chip-L", base + 120_000, "SYNCED")));
        assertEquals(2, recompute(s.orgA().getId(), 60, null).get("visitCount").asInt());
        // Late sequence arrives inside the earlier gap; recompute must merge.
        ingest(s.orgA().getId(),
                List.of(item(n.nodeId(), 2, "chip-L", base + 60_000, "SYNCED")));
        JsonNode out = recompute(s.orgA().getId(), 60, null);
        assertEquals(1, out.get("visitCount").asInt());
        JsonNode visits = listVisits(s.orgA().getId());
        assertEquals(1, visits.size());
        assertEquals(3, visits.get(0).get("observationCount").asInt());
        assertEquals(base, visits.get(0).get("startAtMillis").asLong());
        assertEquals(base + 120_000, visits.get(0).get("endAtMillis").asLong());
    }

    @Test
    void knownClockAliasParticipatesLikeSynced() throws Exception {
        Seed s = seed();
        login("a10-admin-a@example.org", "supersecret-password-a");
        NodeKeys n = claimNode(s.orgA().getId());
        UUID site = createSite(s.orgA().getId(), "Site");
        createDeployment(s.orgA().getId(), n.nodeId(), site,
                Instant.now().minus(Duration.ofDays(30)), null);
        long base = Instant.now().minus(Duration.ofDays(1)).toEpochMilli();
        // KNOWN is the stored BLE wire alias for a trustworthy clock (#9);
        // visit-gap-v1 treats it exactly like SYNCED/RTC_ONLY.
        ingest(s.orgA().getId(), List.of(
                item(n.nodeId(), 1, "chip-K", base, "KNOWN"),
                item(n.nodeId(), 2, "chip-K", base + 10_000, "RTC_ONLY"),
                item(n.nodeId(), 3, "chip-K", base + 20_000, "SYNCED")));
        JsonNode out = recompute(s.orgA().getId(), 60, null);
        assertEquals(3, out.get("usableObservations").asInt());
        assertEquals(0, out.get("excludedUnknownClock").asInt());
        assertEquals(1, out.get("visitCount").asInt());
        assertEquals(3, listVisits(s.orgA().getId()).get(0)
                .get("observationCount").asInt());
    }

    @Test
    void unknownClockIsExcludedAndVisible() throws Exception {
        Seed s = seed();
        login("a10-admin-a@example.org", "supersecret-password-a");
        NodeKeys n = claimNode(s.orgA().getId());
        UUID site = createSite(s.orgA().getId(), "Site");
        createDeployment(s.orgA().getId(), n.nodeId(), site,
                Instant.now().minus(Duration.ofDays(30)), null);
        long base = Instant.now().minus(Duration.ofDays(1)).toEpochMilli();
        Map<String, Object> unknown = item(n.nodeId(), 1, "chip-U", null, "UNKNOWN");
        unknown.put("monotonicMs", 12345L);
        ingest(s.orgA().getId(), List.of(
                item(n.nodeId(), 2, "chip-U", base, "SYNCED"), unknown));
        JsonNode out = recompute(s.orgA().getId(), 60, null);
        assertEquals(1, out.get("visitCount").asInt());
        assertEquals(2, out.get("totalObservations").asInt());
        assertEquals(1, out.get("usableObservations").asInt());
        assertEquals(1, out.get("excludedUnknownClock").asInt());
        JsonNode visits = listVisits(s.orgA().getId());
        assertEquals(1, visits.size());
        assertEquals(1, visits.get(0).get("observationCount").asInt());
        // Raw row preserved and listable, never silently assigned.
        var stored = observations.findByNodeNodeIdAndSequence(n.nodeId(), 1).orElseThrow();
        assertNull(stored.getObservedAtMs());
        assertNull(stored.getFeedingSite());
        assertEquals(2, observations.count());
    }

    @Test
    void unattributedKnownClockIsExcluded() throws Exception {
        Seed s = seed();
        login("a10-admin-a@example.org", "supersecret-password-a");
        NodeKeys n = claimNode(s.orgA().getId());
        UUID site = createSite(s.orgA().getId(), "Site");
        Instant t0 = Instant.now().minus(Duration.ofDays(20));
        Instant t1 = Instant.now().minus(Duration.ofDays(10));
        // Deployment covers only [t0, t1); observation before t0 has no cover.
        createDeployment(s.orgA().getId(), n.nodeId(), site, t0, t1);
        long outside = t0.minus(Duration.ofDays(1)).toEpochMilli();
        long inside = t0.plus(Duration.ofDays(1)).toEpochMilli();
        ingest(s.orgA().getId(), List.of(
                item(n.nodeId(), 1, "chip-N", outside, "SYNCED"),
                item(n.nodeId(), 2, "chip-N", inside, "SYNCED")));
        var first = observations.findByNodeNodeIdAndSequence(n.nodeId(), 1).orElseThrow();
        assertNull(first.getFeedingSite());
        JsonNode out = recompute(s.orgA().getId(), 60, null);
        assertEquals(1, out.get("excludedUnattributed").asInt());
        assertEquals(1, out.get("visitCount").asInt());
        assertEquals(1, listVisits(s.orgA().getId()).size());
    }

    @Test
    void longSequenceAndSingleRead() throws Exception {
        Seed s = seed();
        login("a10-admin-a@example.org", "supersecret-password-a");
        NodeKeys n = claimNode(s.orgA().getId());
        UUID site = createSite(s.orgA().getId(), "Site");
        createDeployment(s.orgA().getId(), n.nodeId(), site,
                Instant.now().minus(Duration.ofDays(30)), null);
        long base = Instant.now().minus(Duration.ofDays(1)).toEpochMilli();
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            items.add(item(n.nodeId(), i, "chip-Long", base + (long) i * 10_000, "SYNCED"));
        }
        ingest(s.orgA().getId(), items);
        JsonNode out = recompute(s.orgA().getId(), 60, null);
        assertEquals(1, out.get("visitCount").asInt());
        JsonNode visits = listVisits(s.orgA().getId());
        assertEquals(100, visits.get(0).get("observationCount").asInt());

        Seed s2 = seed();
        login("a10-admin-a@example.org", "supersecret-password-a");
        NodeKeys n2 = claimNode(s2.orgA().getId());
        UUID site2 = createSite(s2.orgA().getId(), "Site");
        createDeployment(s2.orgA().getId(), n2.nodeId(), site2,
                Instant.now().minus(Duration.ofDays(30)), null);
        ingest(s2.orgA().getId(),
                List.of(item(n2.nodeId(), 1, "chip-Solo", base, "RTC_ONLY")));
        assertEquals(1, recompute(s2.orgA().getId(), 60, null).get("visitCount").asInt());
        JsonNode solo = listVisits(s2.orgA().getId());
        assertEquals(1, solo.size());
        assertEquals(1, solo.get(0).get("observationCount").asInt());
        assertEquals(solo.get(0).get("startAtMillis").asLong(),
                solo.get(0).get("endAtMillis").asLong());
        assertEquals(solo.get(0).get("firstObservationId").asText(),
                solo.get(0).get("lastObservationId").asText());
    }

    @Test
    void recomputeIsIdempotentAndLeavesRawUntouched() throws Exception {
        Seed s = seed();
        login("a10-admin-a@example.org", "supersecret-password-a");
        NodeKeys n = claimNode(s.orgA().getId());
        UUID site = createSite(s.orgA().getId(), "Site");
        createDeployment(s.orgA().getId(), n.nodeId(), site,
                Instant.now().minus(Duration.ofDays(30)), null);
        long base = Instant.now().minus(Duration.ofDays(1)).toEpochMilli();
        ingest(s.orgA().getId(), List.of(
                item(n.nodeId(), 1, "chip-I", base, "SYNCED"),
                item(n.nodeId(), 2, "chip-I", base + 10_000, "SYNCED"),
                item(n.nodeId(), 3, "chip-I", base + 200_000, "SYNCED")));
        JsonNode first = recompute(s.orgA().getId(), 60, null);
        JsonNode second = recompute(s.orgA().getId(), 60, null);
        assertEquals(first.get("visitCount").asInt(), second.get("visitCount").asInt());
        assertEquals(2, second.get("visitCount").asInt());
        // Same business result, no duplicates from idempotent raw retries.
        assertEquals(2, listVisits(s.orgA().getId()).size());
        assertEquals(3, observations.count());
        // Identical retry cannot create duplicate visits.
        ingest(s.orgA().getId(), List.of(
                item(n.nodeId(), 1, "chip-I", base, "SYNCED")));
        assertEquals(2, recompute(s.orgA().getId(), 60, null).get("visitCount").asInt());
    }

    @Test
    void ingestOrderDoesNotAffectResult() throws Exception {
        Seed s = seed();
        login("a10-admin-a@example.org", "supersecret-password-a");
        NodeKeys n = claimNode(s.orgA().getId());
        UUID site = createSite(s.orgA().getId(), "Site");
        createDeployment(s.orgA().getId(), n.nodeId(), site,
                Instant.now().minus(Duration.ofDays(30)), null);
        long base = Instant.now().minus(Duration.ofDays(1)).toEpochMilli();
        // Ingest out of time order: sequences 1..5 map to shuffled times.
        long[] times = {base + 40_000, base, base + 20_000, base + 30_000, base + 10_000};
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < times.length; i++) {
            items.add(item(n.nodeId(), i + 1, "chip-D", times[i], "SYNCED"));
        }
        ingest(s.orgA().getId(), items);
        JsonNode out = recompute(s.orgA().getId(), 60, null);
        assertEquals(1, out.get("visitCount").asInt());
        JsonNode visits = listVisits(s.orgA().getId());
        assertEquals(5, visits.get(0).get("observationCount").asInt());
        assertEquals(base, visits.get(0).get("startAtMillis").asLong());
        assertEquals(base + 40_000, visits.get(0).get("endAtMillis").asLong());
    }

    @Test
    void tenantIsolationForVisits() throws Exception {
        Seed s = seed();
        login("a10-admin-a@example.org", "supersecret-password-a");
        NodeKeys nodeA = claimNode(s.orgA().getId());
        UUID siteA = createSite(s.orgA().getId(), "Site A");
        createDeployment(s.orgA().getId(), nodeA.nodeId(), siteA,
                Instant.now().minus(Duration.ofDays(30)), null);
        long base = Instant.now().minus(Duration.ofDays(1)).toEpochMilli();
        ingest(s.orgA().getId(),
                List.of(item(nodeA.nodeId(), 1, "chip-Z", base, "SYNCED")));
        assertEquals(1, recompute(s.orgA().getId(), 60, null).get("visitCount").asInt());
        UUID visitA = UUID.fromString(
                listVisits(s.orgA().getId()).get(0).get("id").asText());

        login("a10-admin-b@example.org", "supersecret-password-b");
        assertEquals(403, get("/api/v1/organizations/" + s.orgA().getId()
                + "/visits?limit=10").statusCode());
        assertEquals(404, get("/api/v1/organizations/" + s.orgB().getId() + "/visits/"
                + visitA).statusCode());
        assertEquals(403, post("/api/v1/organizations/" + s.orgA().getId()
                + "/visits/recompute", "{\"gapSeconds\":60}").statusCode());
        assertEquals(0, derivedVisits.countByOrganizationId(s.orgB().getId()));
    }

    @Test
    void memberCanReadButNotRecompute() throws Exception {
        Seed s = seed();
        login("a10-admin-a@example.org", "supersecret-password-a");
        NodeKeys n = claimNode(s.orgA().getId());
        UUID site = createSite(s.orgA().getId(), "Site");
        createDeployment(s.orgA().getId(), n.nodeId(), site,
                Instant.now().minus(Duration.ofDays(30)), null);
        long base = Instant.now().minus(Duration.ofDays(1)).toEpochMilli();
        ingest(s.orgA().getId(),
                List.of(item(n.nodeId(), 1, "chip-R", base, "SYNCED")));
        assertEquals(1, recompute(s.orgA().getId(), 60, null).get("visitCount").asInt());

        login("a10-member-a@example.org", "supersecret-password-m");
        assertEquals(200, get("/api/v1/organizations/" + s.orgA().getId()
                + "/visits?limit=10").statusCode());
        assertEquals(403, post("/api/v1/organizations/" + s.orgA().getId()
                + "/visits/recompute", "{\"gapSeconds\":60}").statusCode());
    }

    @Test
    void invalidGapAndAlgorithmLeaveStateUntouched() throws Exception {
        Seed s = seed();
        login("a10-admin-a@example.org", "supersecret-password-a");
        NodeKeys n = claimNode(s.orgA().getId());
        UUID site = createSite(s.orgA().getId(), "Site");
        createDeployment(s.orgA().getId(), n.nodeId(), site,
                Instant.now().minus(Duration.ofDays(30)), null);
        long base = Instant.now().minus(Duration.ofDays(1)).toEpochMilli();
        ingest(s.orgA().getId(),
                List.of(item(n.nodeId(), 1, "chip-E", base, "SYNCED")));
        assertEquals(1, recompute(s.orgA().getId(), 60, null).get("visitCount").asInt());

        for (String bad : List.of("{\"gapSeconds\":0}", "{\"gapSeconds\":-5}",
                "{\"gapSeconds\":86401}", "{\"algorithmVersion\":\"unknown-v9\"}")) {
            assertEquals(400, post("/api/v1/organizations/" + s.orgA().getId()
                    + "/visits/recompute", bad).statusCode(), bad);
        }
        // Failed recomputes never leave partial derived state.
        assertEquals(1, listVisits(s.orgA().getId()).size());
        assertEquals(1, observations.count());
    }

    @Test
    void defaultGapIsSixtyWhenOmitted() throws Exception {
        Seed s = seed();
        login("a10-admin-a@example.org", "supersecret-password-a");
        NodeKeys n = claimNode(s.orgA().getId());
        UUID site = createSite(s.orgA().getId(), "Site");
        createDeployment(s.orgA().getId(), n.nodeId(), site,
                Instant.now().minus(Duration.ofDays(30)), null);
        long base = Instant.now().minus(Duration.ofDays(1)).toEpochMilli();
        ingest(s.orgA().getId(), List.of(
                item(n.nodeId(), 1, "chip-G", base, "SYNCED"),
                item(n.nodeId(), 2, "chip-G", base + 61_000, "SYNCED")));
        JsonNode out = recomputeDefault(s.orgA().getId());
        assertEquals(60, out.get("gapSeconds").asInt());
        assertEquals(2, out.get("visitCount").asInt());
    }

    @Test
    void catRelationIsOptionalSnapshot() throws Exception {
        Seed s = seed();
        login("a10-admin-a@example.org", "supersecret-password-a");
        NodeKeys n = claimNode(s.orgA().getId());
        UUID site = createSite(s.orgA().getId(), "Site");
        createDeployment(s.orgA().getId(), n.nodeId(), site,
                Instant.now().minus(Duration.ofDays(30)), null);
        long base = Instant.now().minus(Duration.ofDays(1)).toEpochMilli();
        ingest(s.orgA().getId(),
                List.of(item(n.nodeId(), 1, "chip-Cat", base, "SYNCED")));
        // No cat yet: relation stays null.
        assertTrue(recompute(s.orgA().getId(), 60, null).get("visits").get(0)
                .get("catId").isNull());
        UUID cat = createCat(s.orgA().getId(), "chip-Cat");
        JsonNode out = recompute(s.orgA().getId(), 60, null);
        assertEquals(cat.toString(),
                out.get("visits").get(0).get("catId").asText());
        assertEquals("CHIP-CAT", out.get("visits").get(0).get("chipId").asText());
    }

    @Test
    void deploymentBoundariesAreInclusiveExclusive() throws Exception {
        Seed s = seed();
        login("a10-admin-a@example.org", "supersecret-password-a");
        NodeKeys n = claimNode(s.orgA().getId());
        UUID siteA = createSite(s.orgA().getId(), "Site A");
        UUID siteB = createSite(s.orgA().getId(), "Site B");
        Instant t0 = Instant.now().minus(Duration.ofDays(20));
        Instant t1 = Instant.now().minus(Duration.ofDays(10));
        createDeployment(s.orgA().getId(), n.nodeId(), siteA, t0, t1);
        createDeployment(s.orgA().getId(), n.nodeId(), siteB, t1, null);
        // valid_from inclusive, valid_until exclusive.
        ingest(s.orgA().getId(), List.of(
                item(n.nodeId(), 1, "chip-Db", t0.toEpochMilli(), "SYNCED"),
                item(n.nodeId(), 2, "chip-Db", t1.toEpochMilli(), "SYNCED")));
        JsonNode out = recompute(s.orgA().getId(), 3600, null);
        assertEquals(2, out.get("visitCount").asInt());
        JsonNode visits = listVisits(s.orgA().getId());
        assertEquals(siteA.toString(), visits.get(0).get("feedingSiteId").asText());
        assertEquals(siteB.toString(), visits.get(1).get("feedingSiteId").asText());
    }

    @Test
    void visitListingFiltersWork() throws Exception {
        Seed s = seed();
        login("a10-admin-a@example.org", "supersecret-password-a");
        NodeKeys n1 = claimNode(s.orgA().getId());
        NodeKeys n2 = claimNode(s.orgA().getId());
        UUID siteA = createSite(s.orgA().getId(), "Site A");
        UUID siteB = createSite(s.orgA().getId(), "Site B");
        Instant from = Instant.now().minus(Duration.ofDays(30));
        createDeployment(s.orgA().getId(), n1.nodeId(), siteA, from, null);
        createDeployment(s.orgA().getId(), n2.nodeId(), siteB, from, null);
        long base = Instant.now().minus(Duration.ofDays(1)).toEpochMilli();
        ingest(s.orgA().getId(), List.of(
                item(n1.nodeId(), 1, "chip-F1", base, "SYNCED"),
                item(n2.nodeId(), 1, "chip-F2", base + 5_000, "SYNCED"),
                item(n1.nodeId(), 2, "chip-F1", base + 500_000, "SYNCED")));
        assertEquals(3, recompute(s.orgA().getId(), 60, null).get("visitCount").asInt());

        // Chip filter is normalized server-side.
        var byChip = get("/api/v1/organizations/" + s.orgA().getId()
                + "/visits?chipId=chip-f1&limit=100");
        assertEquals(200, byChip.statusCode(), byChip.body());
        assertEquals(2, mapper.readTree(byChip.body()).size());

        // Site filter.
        var bySite = get("/api/v1/organizations/" + s.orgA().getId()
                + "/visits?feedingSiteId=" + siteB + "&limit=100");
        assertEquals(200, bySite.statusCode(), bySite.body());
        JsonNode siteRows = mapper.readTree(bySite.body());
        assertEquals(1, siteRows.size());
        assertEquals("CHIP-F2", siteRows.get(0).get("chipId").asText());

        // Time filter applies to visit start_at: fromMillis inclusive,
        // toMillis exclusive.
        var byTime = get("/api/v1/organizations/" + s.orgA().getId()
                + "/visits?fromMillis=" + (base + 100_000) + "&limit=100");
        assertEquals(200, byTime.statusCode(), byTime.body());
        assertEquals(1, mapper.readTree(byTime.body()).size());

        // Pagination is deterministic (start_at asc): limit/offset slices.
        var page1 = get("/api/v1/organizations/" + s.orgA().getId()
                + "/visits?limit=2&offset=0");
        var page2 = get("/api/v1/organizations/" + s.orgA().getId()
                + "/visits?limit=2&offset=2");
        assertEquals(200, page1.statusCode());
        assertEquals(200, page2.statusCode());
        assertEquals(2, mapper.readTree(page1.body()).size());
        assertEquals(1, mapper.readTree(page2.body()).size());

        // Foreign site ids are indistinguishable from absent ones.
        assertEquals(404, get("/api/v1/organizations/" + s.orgA().getId()
                + "/visits?feedingSiteId=" + UUID.randomUUID() + "&limit=10")
                .statusCode());
    }

    @Test
    void equalTimestampsAreDeterministic() throws Exception {
        Seed s = seed();
        login("a10-admin-a@example.org", "supersecret-password-a");
        NodeKeys n1 = claimNode(s.orgA().getId());
        NodeKeys n2 = claimNode(s.orgA().getId());
        UUID site = createSite(s.orgA().getId(), "Site");
        Instant from = Instant.now().minus(Duration.ofDays(30));
        createDeployment(s.orgA().getId(), n1.nodeId(), site, from, null);
        createDeployment(s.orgA().getId(), n2.nodeId(), site, from, null);
        long at = Instant.now().minus(Duration.ofDays(1)).toEpochMilli();
        ingest(s.orgA().getId(), List.of(
                item(n1.nodeId(), 2, "chip-Eq", at, "SYNCED"),
                item(n2.nodeId(), 1, "chip-Eq", at, "SYNCED")));
        JsonNode first = recompute(s.orgA().getId(), 60, null);
        JsonNode second = recompute(s.orgA().getId(), 60, null);
        assertEquals(1, first.get("visitCount").asInt());
        assertEquals(1, second.get("visitCount").asInt());
        JsonNode v1 = listVisits(s.orgA().getId());
        assertEquals(2, v1.get(0).get("observationCount").asInt());
        String firstId = v1.get(0).get("firstObservationId").asText();
        String lastId = v1.get(0).get("lastObservationId").asText();
        // Recompute is stable: same deterministic tie-break every time.
        recompute(s.orgA().getId(), 60, null);
        JsonNode v2 = listVisits(s.orgA().getId());
        assertEquals(firstId, v2.get(0).get("firstObservationId").asText());
        assertEquals(lastId, v2.get(0).get("lastObservationId").asText());
    }
}
