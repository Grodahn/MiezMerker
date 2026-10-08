package org.miezmerker.backend;

import org.mockito.Mockito;
import org.mockito.ArgumentMatchers;
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
 * HTTP acceptance tests for #54 using normal ingest and visit recompute.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class FeedingSiteCatActivityTest {
    @Autowired jakarta.persistence.EntityManagerFactory entityManagerFactory;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc;
    @Autowired AppUserRepository users;
    @Autowired OrganizationRepository organizations;
    @Autowired MembershipRepository memberships;
    @Autowired NodeRepository nodes;
    @Autowired FeedingSiteRepository sites;
    @Autowired CatRepository cats;
    @Autowired NodeDeploymentRepository deployments;
    @Autowired RawObservationRepository observations;
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

    String activityPath(UUID org, UUID site) {
        return "/api/v1/organizations/" + org + "/feeding-sites/" + site + "/cat-activity";
    }

    JsonNode activity(UUID org, UUID site) throws Exception {
        var response = get(activityPath(org, site));
        assertEquals(200, response.statusCode(), response.body());
        return mapper.readTree(response.body());
    }

    @Test void knownUnknownOrderingPaginationAndCurrentCatMetadata() throws Exception {
        var s = seed();
        UUID org = s.orgA().getId();
        login("a10-admin-a@example.org", "supersecret-password-a");
        var n = claimNode(org);
        UUID site = createSite(org, "Site");
        createDeployment(org, n.nodeId(), site, Instant.parse("2026-01-01T00:00:00Z"), null);
        long t = Instant.parse("2026-05-01T12:00:00Z").toEpochMilli();
        UUID luna = createCat(org, "LUNA");
        createCat(org, "MINKA");
        ingest(org, List.of(item(n.nodeId(), 1, "LUNA", t, "KNOWN"),
                item(n.nodeId(), 2, "LUNA", t + 30_000, "SYNCED"),
                item(n.nodeId(), 3, "MINKA", t + 90_000, "RTC_ONLY"),
                item(n.nodeId(), 4, "UNKNOWN-CHIP", t + 90_000, "KNOWN")));
        // Raw-only chips appear immediately, without asserting a reliable visit before recompute.
        var pending = activity(org, site);
        assertEquals(3, pending.size());
        assertTrue(pending.get(0).get("lastReliableSightingAt").isNull());
        assertEquals(0, pending.get(0).get("visitCount").asInt());
        recomputeDefault(org);
        // Resolve live Cat metadata, not the optional Cat snapshot in DerivedVisit.
        var cat = cats.findById(luna).orElseThrow();
        cat.setName("Luna renamed");
        cats.save(cat);
        var rows = activity(org, site);
        assertEquals(List.of("MINKA", "UNKNOWN-CHIP", "LUNA"),
                java.util.stream.StreamSupport.stream(rows.spliterator(), false)
                        .map(row -> row.get("chipId").asText()).toList());
        assertEquals(Instant.ofEpochMilli(t + 30_000),
                Instant.parse(rows.get(2).get("lastReliableSightingAt").asText()));
        assertEquals("Luna renamed", rows.get(2).get("catName").asText());
        assertEquals(luna.toString(), rows.get(2).get("catId").asText());
        assertEquals(1, rows.get(2).get("visitCount").asInt());
        assertTrue(rows.get(1).get("catId").isNull());
        assertTrue(rows.get(1).get("catName").isNull());
        var page = get(activityPath(org, site) + "?limit=1&offset=1");
        assertEquals(200, page.statusCode());
        assertEquals(rows.get(1), mapper.readTree(page.body()).get(0));
        assertEquals(0, mapper.readTree(get(activityPath(org, site) + "?offset=100").body()).size());
        for (String query : List.of("limit=0", "limit=1001", "offset=-1", "limit=abc")) {
            assertEquals(400, get(activityPath(org, site) + "?" + query).statusCode());
        }
        // A Cat registered after recompute resolves immediately, even when the visit snapshot is null.
        UUID registered = createCat(org, "UNKNOWN-CHIP");
        assertEquals(registered.toString(), activity(org, site).get(1).get("catId").asText());
    }

    @Test void nodeMoveSameChipAcrossSitesAndLateRecompute() throws Exception {
        var s = seed();
        UUID org = s.orgA().getId();
        login("a10-admin-a@example.org", "supersecret-password-a");
        var n = claimNode(org);
        UUID a = createSite(org, "Old site"), b = createSite(org, "New site");
        Instant move = Instant.parse("2026-07-01T00:00:00Z");
        createDeployment(org, n.nodeId(), a, Instant.parse("2026-01-01T00:00:00Z"), move);
        long may = Instant.parse("2026-05-01T12:00:00Z").toEpochMilli();
        ingest(org, List.of(item(n.nodeId(), 1, "SAME", may, "KNOWN")));
        recomputeDefault(org);
        createDeployment(org, n.nodeId(), b, move, null);
        ingest(org, List.of(item(n.nodeId(), 2, "SAME", move.toEpochMilli(), "SYNCED")));
        recomputeDefault(org);
        assertEquals(Instant.ofEpochMilli(may), Instant.parse(activity(org, a).get(0)
                .get("lastReliableSightingAt").asText()));
        assertEquals(move, Instant.parse(activity(org, b).get(0).get("lastReliableSightingAt").asText()));
        // Late arrival extends Site A only after the existing recompute path runs.
        ingest(org, List.of(item(n.nodeId(), 3, "SAME", may + 30_000, "KNOWN")));
        assertEquals(Instant.ofEpochMilli(may), Instant.parse(activity(org, a).get(0)
                .get("lastReliableSightingAt").asText()));
        recomputeDefault(org);
        var first = activity(org, a).get(0);
        assertEquals(Instant.ofEpochMilli(may + 30_000), Instant.parse(first.get("lastReliableSightingAt").asText()));
        assertEquals(1, first.get("visitCount").asInt());
        assertEquals(move, Instant.parse(activity(org, b).get(0).get("lastReliableSightingAt").asText()));
    }

    @Test void unreliableClocksNeverInventSiteOrSightingAndNullRowsSortByChip() throws Exception {
        var s = seed();
        UUID org = s.orgA().getId();
        login("a10-admin-a@example.org", "supersecret-password-a");
        var n = claimNode(org);
        UUID site = createSite(org, "Site");
        createDeployment(org, n.nodeId(), site, Instant.EPOCH, null);
        ingest(org, List.of(item(n.nodeId(), 1, "UNATTRIBUTED", null, "UNKNOWN")));
        assertEquals(0, activity(org, site).size());
        var node = nodes.findById(n.nodeId()).orElseThrow();
        var frozenSite = sites.findById(site).orElseThrow();
        // Defensive coverage for stored uncertain rows with frozen attribution (not produced by ingest).
        observations.save(new org.miezmerker.backend.domain.RawObservation(s.orgA(), node, 2,
                "Z-UNCERTAIN", null, "UNKNOWN", null, null, null, frozenSite, null));
        observations.save(new org.miezmerker.backend.domain.RawObservation(s.orgA(), node, 3,
                "A-IMPLAUSIBLE", 0L, "KNOWN", null, null, null, frozenSite, null));
        recomputeDefault(org);
        var rows = activity(org, site);
        assertEquals(2, rows.size());
        assertEquals("A-IMPLAUSIBLE", rows.get(0).get("chipId").asText());
        assertEquals("Z-UNCERTAIN", rows.get(1).get("chipId").asText());
        for (var row : rows) {
            assertTrue(row.get("lastReliableSightingAt").isNull());
            assertFalse(row.get("lastReceivedAt").isNull());
            assertEquals(0, row.get("visitCount").asInt());
        }
    }

    @Test void tenantIsolationForeignGuessedSitesAndInactiveMembership() throws Exception {
        var s = seed();
        UUID orgA = s.orgA().getId(), orgB = s.orgB().getId();
        login("a10-admin-a@example.org", "supersecret-password-a");
        var na = claimNode(orgA);
        UUID a = createSite(orgA, "A");
        createDeployment(orgA, na.nodeId(), a, Instant.EPOCH, null);
        createCat(orgA, "SHARED");
        ingest(orgA, List.of(item(na.nodeId(), 1, "SHARED", 1000L, "KNOWN")));
        recomputeDefault(orgA);
        login("a10-admin-b@example.org", "supersecret-password-b");
        var nb = claimNode(orgB);
        UUID b = createSite(orgB, "B");
        createDeployment(orgB, nb.nodeId(), b, Instant.EPOCH, null);
        UUID catB = createCat(orgB, "SHARED");
        ingest(orgB, List.of(item(nb.nodeId(), 1, "SHARED", 9000L, "KNOWN")));
        recomputeDefault(orgB);
        assertEquals(catB.toString(), activity(orgB, b).get(0).get("catId").asText());
        login("a10-member-a@example.org", "supersecret-password-m");
        var rows = activity(orgA, a);
        assertEquals(1, rows.size());
        assertEquals(Instant.ofEpochMilli(1000), Instant.parse(rows.get(0).get("lastReliableSightingAt").asText()));
        assertNotEquals(catB.toString(), rows.get(0).get("catId").asText());
        for (UUID hidden : List.of(b, UUID.randomUUID())) {
            var response = get(activityPath(orgA, hidden));
            assertEquals(404, response.statusCode(), response.body());
            assertFalse(response.body().contains("SHARED"));
            assertFalse(response.body().contains(catB.toString()));
        }
        assertEquals(403, get(activityPath(orgB, b)).statusCode());
        var member = users.findByEmail("a10-member-a@example.org").orElseThrow();
        var membership = memberships.findByOrganizationIdAndUserId(orgA, member.getId()).orElseThrow();
        membership.disable();
        memberships.save(membership);
        assertEquals(403, get(activityPath(orgA, a)).statusCode());
        http(); // discard session
        assertEquals(401, get(activityPath(orgA, a)).statusCode());
    }

    @Test void queryCountDoesNotGrowWithResultRowsAndDefaultIsBounded() throws Exception {
        var s = seed();
        UUID org = s.orgA().getId();
        login("a10-admin-a@example.org", "supersecret-password-a");
        var n = claimNode(org);
        UUID site = createSite(org, "Site");
        createDeployment(org, n.nodeId(), site, Instant.EPOCH, null);
        ingest(org, List.of(item(n.nodeId(), 1, "CHIP000", 1000L, "KNOWN")));
        var stats = entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        try {
            stats.clear();
            Mockito.clearInvocations(jdbc);
            assertEquals(1, activity(org, site).size());
            long small = stats.getPrepareStatementCount();
            Mockito.verify(jdbc, Mockito.times(1)).query(
                    ArgumentMatchers.anyString(), ArgumentMatchers.anyMap(),
                    ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<org.miezmerker.backend.web.FeedingSiteCatActivityView>>any());
            var items = new ArrayList<Map<String, Object>>();
            for (int i = 1; i <= 104; i++) {
                items.add(item(n.nodeId(), i + 1, "CHIP" + String.format("%03d", i), 1000L, "KNOWN"));
            }
            ingest(org, items);
            stats.clear();
            Mockito.clearInvocations(jdbc);
            assertEquals(100, activity(org, site).size());
            assertEquals(small, stats.getPrepareStatementCount(), "JPA query count must remain constant");
            Mockito.verify(jdbc, Mockito.times(1)).query(
                    ArgumentMatchers.anyString(), ArgumentMatchers.anyMap(),
                    ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<org.miezmerker.backend.web.FeedingSiteCatActivityView>>any());
        } finally {
            stats.setStatisticsEnabled(false);
        }
    }
}
