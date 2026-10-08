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
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Acceptance tests for #9: organization-scoped feeding sites, nodes,
 * deployments, cats and idempotent raw observation ingest.
 *
 * <p>Runs on H2 by default and against PostgreSQL in CI (same suite, both
 * databases) so tenant, uniqueness and concurrency guarantees are not H2-only
 * behavior.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Tag("ingest")
class Issue9IngestTest {
    @Value("${local.server.port}") int port;

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

    HttpResponse<String> patch(String path, String json) throws Exception {
        String token = csrf();
        return client.send(HttpRequest.newBuilder(URI.create(base(path)))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", token)
                .method("PATCH", HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> delete(String path) throws Exception {
        String token = csrf();
        return client.send(HttpRequest.newBuilder(URI.create(base(path)))
                .header("X-XSRF-TOKEN", token)
                .DELETE().build(), HttpResponse.BodyHandlers.ofString());
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
        Organization orgA = organizations.save(new Organization("org-a9", "Org A", null));
        Organization orgB = organizations.save(new Organization("org-b9", "Org B", null));
        AppUser adminA = users.save(
                new AppUser("a9-admin-a@example.org", passwords.encode("supersecret-password-a")));
        AppUser memberA = users.save(new AppUser("a9-member-a@example.org",
                passwords.encode("supersecret-password-m")));
        AppUser adminB = users.save(
                new AppUser("a9-admin-b@example.org", passwords.encode("supersecret-password-b")));
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

    // ---- ingest behavior ----

    @Test
    void emptyBatchIsAccepted() throws Exception {
        Seed s = seed();
        login("a9-admin-a@example.org", "supersecret-password-a");
        NodeKeys n = claimNode(s.orgA().getId());
        JsonNode out = ingest(s.orgA().getId(), List.of());
        assertEquals(0, out.get("inserted").asInt());
        assertEquals(0, out.get("results").size());
        assertEquals(0, observations.count());
    }

    @Test
    void singleObservationRoundTrip() throws Exception {
        Seed s = seed();
        login("a9-admin-a@example.org", "supersecret-password-a");
        NodeKeys n = claimNode(s.orgA().getId());
        UUID site = createSite(s.orgA().getId(), "Futterstelle Nord");
        Instant from = Instant.now().minus(Duration.ofDays(30));
        createDeployment(s.orgA().getId(), n.nodeId(), site, from, null);

        long at = Instant.now().toEpochMilli();
        JsonNode out = ingest(s.orgA().getId(),
                List.of(item(n.nodeId(), 1, "276098106000001", at, "SYNCED")));
        assertEquals(1, out.get("inserted").asInt());
        assertEquals("CREATED", out.get("results").get(0).get("status").asText());

        var list = get("/api/v1/observations?organizationId=" + s.orgA().getId());
        assertEquals(200, list.statusCode(), list.body());
        JsonNode rows = mapper.readTree(list.body());
        assertEquals(1, rows.size());
        assertEquals(n.nodeId().toString(), rows.get(0).get("nodeId").asText());
        assertEquals(1, rows.get(0).get("sequence").asInt());
        assertEquals("276098106000001", rows.get(0).get("chipId").asText());
        assertEquals(at, rows.get(0).get("observedAtMillis").asLong());
        assertEquals("SYNCED", rows.get(0).get("clockStatus").asText());
        assertEquals(site.toString(), rows.get(0).get("feedingSiteId").asText());
        assertNotNull(rows.get(0).get("receivedAt").asText());

        UUID id = UUID.fromString(rows.get(0).get("id").asText());
        var single = get("/api/v1/observations/" + id);
        assertEquals(200, single.statusCode(), single.body());
        assertEquals("276098106000001",
                mapper.readTree(single.body()).get("chipId").asText());
    }

    @Test
    void largeBatch() throws Exception {
        Seed s = seed();
        login("a9-admin-a@example.org", "supersecret-password-a");
        NodeKeys n = claimNode(s.orgA().getId());
        long at = Instant.now().toEpochMilli();
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 1; i <= 500; i++) {
            items.add(item(n.nodeId(), i, "chip-" + (i % 25), at + i, "RTC_ONLY"));
        }
        JsonNode out = ingest(s.orgA().getId(), items);
        assertEquals(500, out.get("inserted").asInt());
        assertEquals(0, out.get("conflicts").asInt());
        assertEquals(500, observations.count());
    }

    @Test
    void sameBatchTwiceIsIdempotent() throws Exception {
        Seed s = seed();
        login("a9-admin-a@example.org", "supersecret-password-a");
        NodeKeys n = claimNode(s.orgA().getId());
        long at = Instant.now().toEpochMilli();
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            items.add(item(n.nodeId(), i, "chip-1", at + i, "SYNCED"));
        }
        JsonNode first = ingest(s.orgA().getId(), items);
        assertEquals(20, first.get("inserted").asInt());
        JsonNode second = ingest(s.orgA().getId(), items);
        assertEquals(0, second.get("inserted").asInt());
        assertEquals(20, second.get("duplicates").asInt());
        assertEquals(0, second.get("conflicts").asInt());
        assertEquals(20, observations.count());
    }

    @Test
    void partiallyExistingBatch() throws Exception {
        Seed s = seed();
        login("a9-admin-a@example.org", "supersecret-password-a");
        NodeKeys n = claimNode(s.orgA().getId());
        long at = Instant.now().toEpochMilli();
        List<Map<String, Object>> firstHalf = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            firstHalf.add(item(n.nodeId(), i, "chip-1", at + i, "SYNCED"));
        }
        assertEquals(10, ingest(s.orgA().getId(), firstHalf).get("inserted").asInt());
        List<Map<String, Object>> full = new ArrayList<>(firstHalf);
        for (int i = 11; i <= 20; i++) {
            full.add(item(n.nodeId(), i, "chip-1", at + i, "SYNCED"));
        }
        JsonNode out = ingest(s.orgA().getId(), full);
        assertEquals(10, out.get("inserted").asInt());
        assertEquals(10, out.get("duplicates").asInt());
        assertEquals(20, observations.count());
    }

    @Test
    void conflictingPayloadIsRejectedAndOriginalKept() throws Exception {
        Seed s = seed();
        login("a9-admin-a@example.org", "supersecret-password-a");
        NodeKeys n = claimNode(s.orgA().getId());
        long at = Instant.now().toEpochMilli();
        JsonNode first = ingest(s.orgA().getId(),
                List.of(item(n.nodeId(), 7, "chip-original", at, "SYNCED")));
        assertEquals(1, first.get("inserted").asInt());

        // Same key, different chip: conflict, no overwrite.
        JsonNode conflict = ingest(s.orgA().getId(),
                List.of(item(n.nodeId(), 7, "chip-other", at, "SYNCED")));
        assertEquals(1, conflict.get("conflicts").asInt());
        assertEquals("CONFLICT", conflict.get("results").get(0).get("status").asText());

        // Same key, different timestamp: also a conflict.
        JsonNode conflict2 = ingest(s.orgA().getId(),
                List.of(item(n.nodeId(), 7, "chip-original", at + 9999, "SYNCED")));
        assertEquals(1, conflict2.get("conflicts").asInt());

        // The stored row is still the original payload.
        var stored = observations.findByNodeNodeIdAndSequence(n.nodeId(), 7).orElseThrow();
        assertEquals("CHIP-ORIGINAL", stored.getChipId());
        assertEquals(at, stored.getObservedAtMs());
        assertEquals("SYNCED", stored.getClockStatus());
        assertEquals(1, observations.count());
    }

    @Test
    void parallelDuplicateUploadsStaySafe() throws Exception {
        Seed s = seed();
        login("a9-admin-a@example.org", "supersecret-password-a");
        NodeKeys n = claimNode(s.orgA().getId());
        long at = Instant.now().toEpochMilli();
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            items.add(item(n.nodeId(), i, "chip-p", at + i, "SYNCED"));
        }
        String payload = mapper.writeValueAsString(
                Map.of("organizationId", s.orgA().getId().toString(), "observations", items));
        String token = csrf();
        HttpClient shared = client;
        int threads = 8;
        try (var pool = Executors.newFixedThreadPool(threads)) {
            List<Callable<HttpResponse<String>>> jobs = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                jobs.add(() -> shared.send(HttpRequest.newBuilder(
                        URI.create(base("/api/v1/observations/ingest")))
                        .header("Content-Type", "application/json")
                        .header("X-XSRF-TOKEN", token)
                        .POST(HttpRequest.BodyPublishers.ofString(payload)).build(),
                        HttpResponse.BodyHandlers.ofString()));
            }
            List<Future<HttpResponse<String>>> futures = pool.invokeAll(jobs);
            pool.shutdown();
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));
            int created = 0;
            int duplicates = 0;
            for (Future<HttpResponse<String>> f : futures) {
                HttpResponse<String> res = f.get(30, TimeUnit.SECONDS);
                assertEquals(200, res.statusCode(), res.body());
                JsonNode out = mapper.readTree(res.body());
                assertEquals(0, out.get("conflicts").asInt(), res.body());
                created += out.get("inserted").asInt();
                duplicates += out.get("duplicates").asInt();
            }
            // Exactly one winner per sequence across all concurrent uploads.
            assertEquals(30, created);
            assertEquals(30 * (threads - 1), duplicates);
        }
        assertEquals(30, observations.count());
    }

    @Test
    void unknownNodeIsRejectedByPolicy() throws Exception {
        Seed s = seed();
        login("a9-admin-a@example.org", "supersecret-password-a");
        claimNode(s.orgA().getId());
        JsonNode out = ingest(s.orgA().getId(),
                List.of(item(UUID.randomUUID(), 1, "chip-x", Instant.now().toEpochMilli(),
                        "SYNCED")));
        assertEquals("UNKNOWN_NODE", out.get("results").get(0).get("status").asText());
        assertEquals(1, out.get("rejected").asInt());
        assertEquals(0, observations.count());
    }

    @Test
    void invalidChipAndTimestampAreRejected() throws Exception {
        Seed s = seed();
        login("a9-admin-a@example.org", "supersecret-password-a");
        NodeKeys n = claimNode(s.orgA().getId());
        long at = Instant.now().toEpochMilli();
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(item(n.nodeId(), 1, "   ", at, "SYNCED")); // blank chip
        items.add(item(n.nodeId(), 0, "chip-1", at, "SYNCED")); // reserved sequence 0
        items.add(item(n.nodeId(), 2, "chip-1", at, "UNKNOWN")); // UNKNOWN with timestamp
        items.add(item(n.nodeId(), 3, "chip-1", null, "SYNCED")); // missing timestamp
        items.add(item(n.nodeId(), 4, "chip-1", at, "BROKEN-CLOCK")); // bad status
        items.add(item(n.nodeId(), 5, "x".repeat(65), at, "SYNCED")); // overlong chip
        JsonNode out = ingest(s.orgA().getId(), items);
        assertEquals(0, out.get("inserted").asInt());
        assertEquals(6, out.get("rejected").asInt());
        for (JsonNode r : out.get("results")) {
            assertEquals("INVALID", r.get("status").asText(), r.toString());
        }
        assertEquals(0, observations.count());
    }

    // ---- tenant isolation ----

    @Test
    void timestampOutsideDatabaseRangeRejectsOnlyItsItem() throws Exception {
        Seed s = seed();
        login("a9-admin-a@example.org", "supersecret-password-a");
        NodeKeys n = claimNode(s.orgA().getId());
        JsonNode out = ingest(s.orgA().getId(), List.of(
                item(n.nodeId(), 1, "chip-1", 10_000_000_000_000_000L, "RTC_ONLY"),
                item(n.nodeId(), 2, "chip-2", 0L, "SYNCED"),
                item(n.nodeId(), 3, "chip-3", Instant.now().toEpochMilli(), "SYNCED"),
                item(n.nodeId(), 4, "chip-4", 9_224_318_015_999_999L, "RTC_ONLY"),
                item(n.nodeId(), 5, "chip-5", 9_224_318_016_000_000L, "RTC_ONLY")));
        assertEquals(2, out.get("inserted").asInt());
        assertEquals(3, out.get("rejected").asInt());
        assertEquals("INVALID", out.get("results").get(0).get("status").asText());
        assertEquals("CREATED", out.get("results").get(2).get("status").asText());
        assertEquals("CREATED", out.get("results").get(3).get("status").asText());
        assertEquals("INVALID", out.get("results").get(4).get("status").asText());
    }

    @Test
    void rawIntegerFieldsRoundTripAsDecimalStringsWithoutPrecisionLoss() throws Exception {
        Seed s = seed();
        login("a9-admin-a@example.org", "supersecret-password-a");
        NodeKeys n = claimNode(s.orgA().getId());
        var record = item(n.nodeId(), 9_007_199_254_740_993L, "chip-1", null, "UNKNOWN");
        record.put("sequence", "9007199254740993");
        record.put("monotonicMs", Long.toString(Long.MAX_VALUE));
        JsonNode created = ingest(s.orgA().getId(), List.of(record));
        assertTrue(created.get("results").get(0).get("sequence").isString());
        assertEquals("9007199254740993", created.get("results").get(0).get("sequence").asText());
        JsonNode rows = mapper.readTree(get("/api/v1/observations?organizationId=" + s.orgA().getId()).body());
        assertTrue(rows.get(0).get("sequence").isString());
        assertEquals("9007199254740993", rows.get(0).get("sequence").asText());
        assertTrue(rows.get(0).get("monotonicMs").isString());
        assertEquals(Long.toString(Long.MAX_VALUE), rows.get(0).get("monotonicMs").asText());
        assertEquals(1, ingest(s.orgA().getId(), List.of(record)).get("duplicates").asInt());
        var invalid = ingest(s.orgA().getId(), List.of(item(n.nodeId(), -1, "chip-1", null, "UNKNOWN")));
        assertEquals("INVALID", invalid.get("results").get(0).get("status").asText());
        assertEquals("-1", invalid.get("results").get(0).get("sequence").asText());
    }

    @Test
    void organizationACannotReadOrganizationBResources() throws Exception {
        Seed s = seed();
        login("a9-admin-a@example.org", "supersecret-password-a");
        NodeKeys nodeA = claimNode(s.orgA().getId());
        UUID siteA = createSite(s.orgA().getId(), "Site A");
        UUID catA = createCat(s.orgA().getId(), "chip-shared-1");
        createDeployment(s.orgA().getId(), nodeA.nodeId(), siteA,
                Instant.now().minus(Duration.ofDays(10)), null);
        long at = Instant.now().toEpochMilli();
        ingest(s.orgA().getId(), List.of(item(nodeA.nodeId(), 1, "chip-shared-1", at, "SYNCED")));
        UUID obsA = observations.findByNodeNodeIdAndSequence(nodeA.nodeId(), 1).orElseThrow()
                .getId();

        login("a9-admin-b@example.org", "supersecret-password-b");
        // Direct id guessing across tenants: no membership in org A, so the
        // tenant gate rejects with 403 before any data could leak.
        assertEquals(403,
                get("/api/v1/organizations/" + s.orgA().getId() + "/feeding-sites/"
                        + siteA).statusCode());
        assertEquals(403,
                get("/api/v1/organizations/" + s.orgA().getId() + "/cats/" + catA)
                        .statusCode());
        assertEquals(404, get("/api/v1/observations/" + obsA).statusCode());
        assertEquals(404, get("/api/v1/observations/" + UUID.randomUUID()).statusCode());
        assertEquals(403,
                get("/api/v1/observations?organizationId=" + s.orgA().getId()).statusCode());
        // Node of the other organization is not readable in full.
        assertEquals(403, get("/api/v1/nodes/" + nodeA.nodeId()).statusCode());
        assertEquals(403,
                get("/api/v1/nodes?organizationId=" + s.orgA().getId()).statusCode());
        assertEquals(0, observations.countByOrganizationId(s.orgB().getId()));
    }

    @Test
    void organizationACannotIngestForOrganizationBNode() throws Exception {
        Seed s = seed();
        login("a9-admin-b@example.org", "supersecret-password-b");
        NodeKeys nodeB = claimNode(s.orgB().getId());

        login("a9-admin-a@example.org", "supersecret-password-a");
        JsonNode out = ingest(s.orgA().getId(),
                List.of(item(nodeB.nodeId(), 1, "chip-1", Instant.now().toEpochMilli(),
                        "SYNCED")));
        assertEquals("UNKNOWN_NODE", out.get("results").get(0).get("status").asText());
        JsonNode unknown = ingest(s.orgA().getId(), List.of(item(UUID.randomUUID(), 1,
                "chip-1", Instant.now().toEpochMilli(), "SYNCED")));
        assertEquals(unknown.get("results").get(0).get("message"),
                out.get("results").get(0).get("message"));
        assertEquals(0, observations.count());

        // Mixed batch: own item succeeds, foreign item is reported, nothing leaks.
        NodeKeys nodeA = claimNode(s.orgA().getId());
        long at = Instant.now().toEpochMilli();
        JsonNode mixed = ingest(s.orgA().getId(), List.of(
                item(nodeA.nodeId(), 1, "chip-1", at, "SYNCED"),
                item(nodeB.nodeId(), 2, "chip-1", at, "SYNCED")));
        assertEquals(1, mixed.get("inserted").asInt());
        assertEquals("UNKNOWN_NODE", mixed.get("results").get(1).get("status").asText());
        assertEquals(1, observations.count());
        assertEquals(1, observations.countByOrganizationId(s.orgA().getId()));
        assertEquals(0, observations.countByOrganizationId(s.orgB().getId()));
    }

    @Test
    void sameChipIdInTwoOrganizationsStaysIndependent() throws Exception {
        Seed s = seed();
        login("a9-admin-a@example.org", "supersecret-password-a");
        NodeKeys nodeA = claimNode(s.orgA().getId());
        UUID catA = createCat(s.orgA().getId(), " 276098106000001 ");
        login("a9-admin-b@example.org", "supersecret-password-b");
        NodeKeys nodeB = claimNode(s.orgB().getId());
        UUID catB = createCat(s.orgB().getId(), "276098106000001");
        assertNotEquals(catA, catB);

        long at = Instant.now().toEpochMilli();
        login("a9-admin-a@example.org", "supersecret-password-a");
        ingest(s.orgA().getId(), List.of(item(nodeA.nodeId(), 1, "276098106000001", at, "SYNCED")));
        login("a9-admin-b@example.org", "supersecret-password-b");
        ingest(s.orgB().getId(), List.of(item(nodeB.nodeId(), 1, "276098106000001", at, "SYNCED")));

        // Chip normalization is visible but scoped per organization.
        login("a9-admin-a@example.org", "supersecret-password-a");
        JsonNode rowsA = mapper.readTree(
                get("/api/v1/observations?organizationId=" + s.orgA().getId()
                        + "&chipId=276098106000001").body());
        assertEquals(1, rowsA.size());
        assertEquals(nodeA.nodeId().toString(), rowsA.get(0).get("nodeId").asText());
        login("a9-admin-b@example.org", "supersecret-password-b");
        JsonNode rowsB = mapper.readTree(
                get("/api/v1/observations?organizationId=" + s.orgB().getId()
                        + "&chipId=276098106000001").body());
        assertEquals(1, rowsB.size());
        assertEquals(nodeB.nodeId().toString(), rowsB.get(0).get("nodeId").asText());
    }

    // ---- deployment history ----

    @Test
    void nodeMoveKeepsHistoricalAttribution() throws Exception {
        Seed s = seed();
        login("a9-admin-a@example.org", "supersecret-password-a");
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
                item(n.nodeId(), 1, "chip-1", before, "SYNCED"),
                item(n.nodeId(), 2, "chip-1", after, "SYNCED")));

        var first = observations.findByNodeNodeIdAndSequence(n.nodeId(), 1).orElseThrow();
        var second = observations.findByNodeNodeIdAndSequence(n.nodeId(), 2).orElseThrow();
        assertEquals(siteA, first.getFeedingSite().getId());
        assertEquals(siteB, second.getFeedingSite().getId());

        // Filter by historical site resolves through the frozen attribution.
        JsonNode atA = mapper.readTree(get("/api/v1/observations?organizationId="
                + s.orgA().getId() + "&feedingSiteId=" + siteA).body());
        assertEquals(1, atA.size());
        assertEquals(1, atA.get(0).get("sequence").asInt());
        JsonNode atB = mapper.readTree(get("/api/v1/observations?organizationId="
                + s.orgA().getId() + "&feedingSiteId=" + siteB).body());
        assertEquals(1, atB.size());
        assertEquals(2, atB.get(0).get("sequence").asInt());
    }

    @Test
    void deploymentBoundaryCases() throws Exception {
        Seed s = seed();
        login("a9-admin-a@example.org", "supersecret-password-a");
        NodeKeys n = claimNode(s.orgA().getId());
        UUID siteA = createSite(s.orgA().getId(), "Site A");
        UUID siteB = createSite(s.orgA().getId(), "Site B");
        Instant t0 = Instant.now().minus(Duration.ofDays(20));
        Instant t1 = Instant.now().minus(Duration.ofDays(10));
        createDeployment(s.orgA().getId(), n.nodeId(), siteA, t0, t1);
        createDeployment(s.orgA().getId(), n.nodeId(), siteB, t1, null);

        // valid_from inclusive, valid_until exclusive.
        ingest(s.orgA().getId(), List.of(
                item(n.nodeId(), 1, "chip-1", t0.toEpochMilli(), "SYNCED"),
                item(n.nodeId(), 2, "chip-1", t1.toEpochMilli(), "SYNCED"),
                item(n.nodeId(), 3, "chip-1", t1.minusMillis(1).toEpochMilli(), "SYNCED")));
        assertEquals(siteA, observations.findByNodeNodeIdAndSequence(n.nodeId(), 1)
                .orElseThrow().getFeedingSite().getId());
        assertEquals(siteB, observations.findByNodeNodeIdAndSequence(n.nodeId(), 2)
                .orElseThrow().getFeedingSite().getId());
        assertEquals(siteA, observations.findByNodeNodeIdAndSequence(n.nodeId(), 3)
                .orElseThrow().getFeedingSite().getId());

        // Overlapping deployment is rejected; adjacent deployment is allowed.
        Map<String, Object> overlap = new LinkedHashMap<>();
        overlap.put("nodeId", n.nodeId().toString());
        overlap.put("feedingSiteId", siteA.toString());
        overlap.put("validFrom", t0.plus(Duration.ofDays(1)).toString());
        overlap.put("validUntil", t1.plus(Duration.ofDays(1)).toString());
        assertEquals(409, post("/api/v1/organizations/" + s.orgA().getId() + "/deployments",
                mapper.writeValueAsString(overlap)).statusCode());

        // Inverted range is rejected.
        Map<String, Object> inverted = new LinkedHashMap<>(overlap);
        inverted.put("validFrom", t1.toString());
        inverted.put("validUntil", t0.toString());
        assertEquals(400, post("/api/v1/organizations/" + s.orgA().getId() + "/deployments",
                mapper.writeValueAsString(inverted)).statusCode());
        assertEquals(2, deployments.findByNodeNodeId(n.nodeId()).size());
    }

    @Test
    void unknownClockObservationsAreStoredWithoutAttribution() throws Exception {
        Seed s = seed();
        login("a9-admin-a@example.org", "supersecret-password-a");
        NodeKeys n = claimNode(s.orgA().getId());
        UUID site = createSite(s.orgA().getId(), "Site");
        createDeployment(s.orgA().getId(), n.nodeId(), site,
                Instant.now().minus(Duration.ofDays(30)), null);

        Map<String, Object> unknown = item(n.nodeId(), 1, "chip-u", null, "UNKNOWN");
        unknown.put("monotonicMs", 12345L);
        unknown.put("bootCounter", 3);
        JsonNode out = ingest(s.orgA().getId(), List.of(unknown));
        assertEquals(1, out.get("inserted").asInt());

        var stored = observations.findByNodeNodeIdAndSequence(n.nodeId(), 1).orElseThrow();
        assertNull(stored.getObservedAtMs());
        assertEquals("UNKNOWN", stored.getClockStatus());
        assertNull(stored.getFeedingSite());
        assertNull(stored.getDeployment());
        assertEquals(12345L, stored.getMonotonicMs());
        assertEquals(3, stored.getBootCounter());

        // Time-range filters only match timestamped rows; the UNKNOWN row is listed
        // unfiltered but never guessed into the deployment.
        JsonNode ranged = mapper.readTree(get("/api/v1/observations?organizationId="
                + s.orgA().getId() + "&fromMillis=1").body());
        assertEquals(0, ranged.size());
        JsonNode all = mapper.readTree(
                get("/api/v1/observations?organizationId=" + s.orgA().getId()).body());
        assertEquals(1, all.size());
    }

    @Test
    void rawTimestampAndClockStatusStayImmutable() throws Exception {
        Seed s = seed();
        login("a9-admin-a@example.org", "supersecret-password-a");
        NodeKeys n = claimNode(s.orgA().getId());
        long at = Instant.now().toEpochMilli();
        ingest(s.orgA().getId(), List.of(item(n.nodeId(), 1, "chip-1", at, "RTC_ONLY")));

        // Any retry with a different raw timestamp or clock status is a conflict.
        JsonNode retry = ingest(s.orgA().getId(),
                List.of(item(n.nodeId(), 1, "chip-1", at + 60000, "SYNCED")));
        assertEquals(1, retry.get("conflicts").asInt());
        var stored = observations.findByNodeNodeIdAndSequence(n.nodeId(), 1).orElseThrow();
        assertEquals(at, stored.getObservedAtMs());
        assertEquals("RTC_ONLY", stored.getClockStatus());

        // Observations expose no mutation endpoint: PUT is rejected, not applied.
        var put = client.send(HttpRequest.newBuilder(
                URI.create(base("/api/v1/observations/" + stored.getId())))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", csrf())
                .PUT(HttpRequest.BodyPublishers.ofString("{\"chipId\":\"chip-2\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(put.statusCode() == 403 || put.statusCode() == 405, "" + put.statusCode());
        assertEquals("chip-1",
                observations.findByNodeNodeIdAndSequence(n.nodeId(), 1).orElseThrow()
                        .getChipId().equals("CHIP-1") ? "chip-1" : "unexpected");
    }

    // ---- roles ----

    @Test
    void memberCanReadIngestAndProvideRoutineCare() throws Exception {
        Seed s = seed();
        login("a9-admin-a@example.org", "supersecret-password-a");
        NodeKeys n = claimNode(s.orgA().getId());
        UUID site = createSite(s.orgA().getId(), "Site");
        createDeployment(s.orgA().getId(), n.nodeId(), site,
                Instant.now().minus(Duration.ofDays(5)), null);

        login("a9-member-a@example.org", "supersecret-password-m");
        assertEquals(200,
                get("/api/v1/organizations/" + s.orgA().getId() + "/feeding-sites")
                        .statusCode());
        assertEquals(200,
                get("/api/v1/organizations/" + s.orgA().getId() + "/cats").statusCode());
        assertEquals(200,
                get("/api/v1/organizations/" + s.orgA().getId() + "/deployments").statusCode());
        assertEquals(200,
                get("/api/v1/observations?organizationId=" + s.orgA().getId()).statusCode());
        long at = Instant.now().toEpochMilli();
        assertEquals(1, ingest(s.orgA().getId(),
                List.of(item(n.nodeId(), 1, "chip-m", at, "SYNCED"))).get("inserted").asInt());

        // #11: normal care is available to ACTIVE MEMBER; administration stays ADMIN.
        // #51: deployment writes are ADMIN-only (reads stay MEMBER).
        UUID memberSite = createSite(s.orgA().getId(), "Member site");
        UUID memberCat = createCat(s.orgA().getId(), "chip-member");
        String orgPath = "/api/v1/organizations/" + s.orgA().getId();
        assertEquals(200, patch(orgPath + "/feeding-sites/" + memberSite,
                "{\"description\":\"Member description\"}").statusCode());
        assertEquals(200, patch(orgPath + "/cats/" + memberCat,
                "{\"name\":\"Miez\",\"notes\":\"Member note\"}").statusCode());
        assertEquals(200, patch("/api/v1/nodes/" + n.nodeId(),
                "{\"statusNote\":\"member note\"}").statusCode());
        assertEquals(403, post(orgPath + "/deployments/move", mapper.writeValueAsString(Map.of(
                "nodeId", n.nodeId(), "feedingSiteId", memberSite, "validFrom", Instant.now().toString()))).statusCode());
        assertEquals(403, delete(orgPath + "/feeding-sites/" + memberSite).statusCode());
        assertEquals(403, delete(orgPath + "/cats/" + memberCat).statusCode());
        assertEquals(403, get(orgPath + "/members").statusCode());
        assertEquals(403, post(orgPath + "/visits/recompute", "{}").statusCode());

    }

    @Test
    void nodeMetadataManagementIsTenantScoped() throws Exception {
        Seed s = seed();
        login("a9-admin-a@example.org", "supersecret-password-a");
        NodeKeys nodeA = claimNode(s.orgA().getId());
        login("a9-admin-b@example.org", "supersecret-password-b");
        NodeKeys nodeB = claimNode(s.orgB().getId());

        login("a9-admin-a@example.org", "supersecret-password-a");
        var updated = patch("/api/v1/nodes/" + nodeA.nodeId(),
                "{\"firmwareVersion\":\"fw-2\",\"protocolVersion\":\"ble-v1\","
                        + "\"statusNote\":\"Dachboden\"}");
        assertEquals(200, updated.statusCode(), updated.body());
        JsonNode view = mapper.readTree(updated.body());
        assertEquals("fw-2", view.get("firmwareVersion").asText());
        assertEquals("ble-v1", view.get("protocolVersion").asText());
        assertEquals("Dachboden", view.get("statusNote").asText());

        // Foreign admin cannot manage another organization's node.
        login("a9-admin-b@example.org", "supersecret-password-b");
        assertEquals(404, patch("/api/v1/nodes/" + nodeA.nodeId(),
                "{\"statusNote\":\"hijack\"}").statusCode());
        // Managing an unknown node id is 404, never cross-tenant data.
        assertEquals(404, patch("/api/v1/nodes/" + UUID.randomUUID(),
                "{\"statusNote\":\"ghost\"}").statusCode());
        assertEquals("Dachboden", nodes.findById(nodeA.nodeId()).orElseThrow().getStatusNote());
    }

    @Test
    void deploymentsRequireClaimedNodeOfSameOrganization() throws Exception {
        Seed s = seed();
        login("a9-admin-a@example.org", "supersecret-password-a");
        UUID siteA = createSite(s.orgA().getId(), "Site A");
        NodeKeys nodeA = claimNode(s.orgA().getId());

        // Foreign site for own node: 404, no leak.
        login("a9-admin-b@example.org", "supersecret-password-b");
        UUID siteB = createSite(s.orgB().getId(), "Site B");
        login("a9-admin-a@example.org", "supersecret-password-a");
        Map<String, Object> foreignSite = new LinkedHashMap<>();
        foreignSite.put("nodeId", nodeA.nodeId().toString());
        foreignSite.put("feedingSiteId", siteB.toString());
        foreignSite.put("validFrom", Instant.now().minus(Duration.ofDays(1)).toString());
        assertEquals(404, post("/api/v1/organizations/" + s.orgA().getId() + "/deployments",
                mapper.writeValueAsString(foreignSite)).statusCode());

        // Foreign node for own site is indistinguishable from an unknown node.
        login("a9-admin-b@example.org", "supersecret-password-b");
        NodeKeys nodeB = claimNode(s.orgB().getId());
        login("a9-admin-a@example.org", "supersecret-password-a");
        Map<String, Object> foreignNode = new LinkedHashMap<>();
        foreignNode.put("nodeId", nodeB.nodeId().toString());
        foreignNode.put("feedingSiteId", siteA.toString());
        foreignNode.put("validFrom", Instant.now().minus(Duration.ofDays(1)).toString());
        assertEquals(404, post("/api/v1/organizations/" + s.orgA().getId() + "/deployments",
                mapper.writeValueAsString(foreignNode)).statusCode());
        assertEquals(0, deployments.findByNodeNodeId(nodeB.nodeId()).size());
    }

    @Test
    void filtersCombineOrganizationNodeChipAndTime() throws Exception {
        Seed s = seed();
        login("a9-admin-a@example.org", "supersecret-password-a");
        NodeKeys n = claimNode(s.orgA().getId());
        long base = Instant.now().minus(Duration.ofDays(1)).toEpochMilli();
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            items.add(item(n.nodeId(), i, i % 2 == 0 ? "chip-even" : "chip-odd",
                    base + i * 1000, "SYNCED"));
        }
        ingest(s.orgA().getId(), items);

        JsonNode byChip = mapper.readTree(get("/api/v1/observations?organizationId="
                + s.orgA().getId() + "&chipId=chip-even").body());
        assertEquals(3, byChip.size());
        JsonNode byTime = mapper.readTree(get("/api/v1/observations?organizationId="
                + s.orgA().getId() + "&fromMillis=" + (base + 2500) + "&toMillis="
                + (base + 5500)).body());
        assertEquals(3, byTime.size());
        JsonNode byNode = mapper.readTree(get("/api/v1/organizations/" + s.orgA().getId()
                + "/nodes/" + n.nodeId() + "/observations?limit=2&offset=1").body());
        assertEquals(2, byNode.size());
        assertEquals(2, byNode.get(0).get("sequence").asInt());
    }

    @Test
    void nullEntryAndMissingOrganizationAreRejected() throws Exception {
        Seed s = seed();
        login("a9-admin-a@example.org", "supersecret-password-a");
        claimNode(s.orgA().getId());
        // A null array entry is a client bug: fail the batch instead of
        // silently dropping the entry.
        List<Map<String, Object>> withNull = new ArrayList<>();
        withNull.add(null);
        Map<String, Object> nullBody = new LinkedHashMap<>();
        nullBody.put("organizationId", s.orgA().getId().toString());
        nullBody.put("observations", withNull);
        assertEquals(400,
                post("/api/v1/observations/ingest", mapper.writeValueAsString(nullBody))
                        .statusCode());
        // Missing organizationId is rejected; the server never guesses a tenant.
        assertEquals(400, post("/api/v1/observations/ingest", "{\"observations\":[]}")
                .statusCode());
        assertEquals(0, observations.count());
    }

    @Test
    void nodeFiltersDoNotDiscloseForeignIdentities() throws Exception {
        Seed s = seed();
        login("a9-admin-a@example.org", "supersecret-password-a");
        NodeKeys nodeA = claimNode(s.orgA().getId());
        long at = Instant.now().toEpochMilli();
        ingest(s.orgA().getId(), List.of(item(nodeA.nodeId(), 1, "chip-1", at, "SYNCED")));

        // An own-node filter keeps working.
        assertEquals(200, get("/api/v1/observations?organizationId=" + s.orgA().getId()
                + "&nodeId=" + nodeA.nodeId()).statusCode());

        login("a9-admin-b@example.org", "supersecret-password-b");
        // Foreign and absent IDs return the same response.
        assertEquals(404, get("/api/v1/observations?organizationId=" + s.orgB().getId()
                + "&nodeId=" + nodeA.nodeId()).statusCode());
        assertEquals(404, get("/api/v1/observations?organizationId=" + s.orgB().getId()
                + "&nodeId=" + UUID.randomUUID()).statusCode());
        assertEquals(404, get("/api/v1/organizations/" + s.orgB().getId() + "/nodes/"
                + nodeA.nodeId() + "/observations").statusCode());
    }

    @Test
    void overlongMetadataUpdatesAreRejected() throws Exception {
        Seed s = seed();
        login("a9-admin-a@example.org", "supersecret-password-a");
        NodeKeys n = claimNode(s.orgA().getId());
        UUID site = createSite(s.orgA().getId(), "Site");
        // Bean validation applies to updates as well (400, not a 500 from the DB).
        assertEquals(400, patch("/api/v1/nodes/" + n.nodeId(),
                "{\"statusNote\":\"" + "x".repeat(501) + "\"}").statusCode());
        assertEquals(400,
                patch("/api/v1/organizations/" + s.orgA().getId() + "/feeding-sites/"
                        + site, "{\"name\":\"" + "x".repeat(256) + "\"}").statusCode());
    }

    @Test
    void nonFiniteCoordinatesAreRejectedWithoutChangingSites() throws Exception {
        Seed s = seed();
        login("a9-admin-a@example.org", "supersecret-password-a");
        UUID site = createSite(s.orgA().getId(), "Site");
        String path = "/api/v1/organizations/" + s.orgA().getId() + "/feeding-sites";
        for (String invalid : List.of("NaN", "Infinity", "-Infinity")) {
            for (String coordinate : List.of("locationLat", "locationLng")) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("name", "Invalid site");
                body.put("locationLat", 0);
                body.put("locationLng", 0);
                body.put(coordinate, invalid);
                String json = mapper.writeValueAsString(body);
                assertEquals(400, post(path, json).statusCode());
                assertEquals(400, patch(path + "/" + site, json).statusCode());
            }
        }
        JsonNode sites = mapper.readTree(get(path).body());
        assertEquals(1, sites.size());
        assertEquals("Site", sites.get(0).get("name").asText());
        assertTrue(sites.get(0).get("locationLat").isNull());
        assertTrue(sites.get(0).get("locationLng").isNull());
    }

    @Test
    void referencedDeploymentDeleteReturnsConflictAndKeepsHistory() throws Exception {
        Seed s = seed();
        login("a9-admin-a@example.org", "supersecret-password-a");
        NodeKeys n = claimNode(s.orgA().getId());
        UUID site = createSite(s.orgA().getId(), "Historical site");
        long at = Instant.now().toEpochMilli();
        UUID deployment = createDeployment(s.orgA().getId(), n.nodeId(), site,
                Instant.ofEpochMilli(at - 1000), null);
        ingest(s.orgA().getId(), List.of(item(n.nodeId(), 1, "chip-a", at, "SYNCED")));
        String path = "/api/v1/organizations/" + s.orgA().getId() + "/deployments/" + deployment;
        assertEquals(409, delete(path).statusCode());
        assertEquals(200, get(path).statusCode());
        var view = mapper.readTree(get("/api/v1/observations?organizationId=" + s.orgA().getId()).body()).get(0);
        assertEquals(deployment.toString(), view.get("deploymentId").asText());
        assertEquals(site.toString(), view.get("feedingSiteId").asText());
    }

    @Test
    void normalizationOverflowRejectsOnlyTheInvalidItem() throws Exception {
        Seed s = seed();
        login("a9-admin-a@example.org", "supersecret-password-a");
        NodeKeys n = claimNode(s.orgA().getId());
        long at = Instant.now().toEpochMilli();
        var result = ingest(s.orgA().getId(), List.of(
                item(n.nodeId(), 1, "ß".repeat(64), at, "SYNCED"),
                item(n.nodeId(), 2, "chip-a", at, "SYNCED")));
        assertEquals(1, result.get("rejected").asInt());
        assertEquals("INVALID", result.get("results").get(0).get("status").asText());
        assertEquals(1, result.get("inserted").asInt());
        assertEquals(400, post("/api/v1/organizations/" + s.orgA().getId() + "/cats",
                mapper.writeValueAsString(Map.of("chipId", "ß".repeat(64)))).statusCode());
    }

    // #11 additions extend the #9 HTTP contract without replacing its tenant tests.
    @Test
    void atomicMoveKeepsHistoricalObservationsAndVisits() throws Exception {
        Seed s = seed();
        login("a9-admin-a@example.org", "supersecret-password-a");
        String orgPath = "/api/v1/organizations/" + s.orgA().getId();
        NodeKeys node = claimNode(s.orgA().getId());
        UUID oldSite = createSite(s.orgA().getId(), "Old site");
        UUID newSite = createSite(s.orgA().getId(), "New site");
        Instant start = Instant.parse("2025-01-01T00:00:00Z");
        UUID oldDeployment = createDeployment(s.orgA().getId(), node.nodeId(), oldSite, start, null);
        ingest(s.orgA().getId(), List.of(item(node.nodeId(), 1, "chip-history",
                start.plusSeconds(60).toEpochMilli(), "SYNCED")));
        assertEquals(200, post(orgPath + "/visits/recompute", "{}").statusCode());
        // #51: the move itself is ACTIVE ADMIN; MEMBER reads below still work.
        var moved = post(orgPath + "/deployments/move", mapper.writeValueAsString(Map.of(
                "nodeId", node.nodeId(), "feedingSiteId", newSite,
                "validFrom", start.plusSeconds(120).toString())));
        assertEquals(200, moved.statusCode(), moved.body());
        assertEquals(start.plusSeconds(120), deployments.findById(oldDeployment).orElseThrow().getValidUntil());
        assertEquals(2, deployments.findByNodeNodeId(node.nodeId()).size());
        var raw = mapper.readTree(get("/api/v1/observations?organizationId=" + s.orgA().getId()).body());
        assertEquals(oldSite.toString(), raw.get(0).get("feedingSiteId").asText());
        var visits = mapper.readTree(get(orgPath + "/visits").body());
        assertEquals(oldSite.toString(), visits.get(0).get("feedingSiteId").asText());
        // The boundary belongs to the new interval; late old data still uses the old one.
        ingest(s.orgA().getId(), List.of(
                item(node.nodeId(), 2, "chip-history", start.plusSeconds(120).toEpochMilli(), "SYNCED"),
                item(node.nodeId(), 3, "chip-history", start.plusSeconds(90).toEpochMilli(), "SYNCED")));
        var boundary = mapper.readTree(get("/api/v1/observations?organizationId=" + s.orgA().getId()).body());
        assertEquals(newSite.toString(), boundary.get(1).get("feedingSiteId").asText());
        assertEquals(oldSite.toString(), boundary.get(2).get("feedingSiteId").asText());
        login("a9-admin-a@example.org", "supersecret-password-a");
        assertEquals(200, post(orgPath + "/visits/recompute", "{}").statusCode());
        assertEquals(2, mapper.readTree(get(orgPath + "/visits").body()).size());
    }

    @Test
    void failedMoveDoesNotCloseExistingAssignmentOrExposeForeignResources() throws Exception {
        Seed s = seed();
        login("a9-admin-b@example.org", "supersecret-password-b");
        UUID foreignSite = createSite(s.orgB().getId(), "Foreign");
        NodeKeys foreignNode = claimNode(s.orgB().getId());
        login("a9-admin-a@example.org", "supersecret-password-a");
        UUID site = createSite(s.orgA().getId(), "Own");
        UUID target = createSite(s.orgA().getId(), "Target");
        NodeKeys node = claimNode(s.orgA().getId());
        Instant start = Instant.parse("2025-01-01T00:00:00Z");
        UUID deployment = createDeployment(s.orgA().getId(), node.nodeId(), site, start, null);
        // #51: interval/tenant validation runs as ACTIVE ADMIN.
        String path = "/api/v1/organizations/" + s.orgA().getId() + "/deployments/move";
        assertEquals(404, post(path, mapper.writeValueAsString(Map.of("nodeId", node.nodeId(),
                "feedingSiteId", foreignSite, "validFrom", start.plusSeconds(60).toString()))).statusCode());
        assertEquals(404, post(path, mapper.writeValueAsString(Map.of("nodeId", foreignNode.nodeId(),
                "feedingSiteId", target, "validFrom", start.plusSeconds(60).toString()))).statusCode());
        assertEquals(409, post(path, mapper.writeValueAsString(Map.of("nodeId", node.nodeId(),
                "feedingSiteId", target, "validFrom", start.toString()))).statusCode());
        assertEquals(409, post(path, mapper.writeValueAsString(Map.of("nodeId", node.nodeId(),
                "feedingSiteId", site, "validFrom", start.plusSeconds(60).toString()))).statusCode());
        assertNull(deployments.findById(deployment).orElseThrow().getValidUntil());
        assertEquals(1, deployments.findByNodeNodeId(node.nodeId()).size());
        // #51: MEMBER of the same org is rejected before any validation runs.
        login("a9-member-a@example.org", "supersecret-password-m");
        assertEquals(403, post(path, mapper.writeValueAsString(Map.of("nodeId", node.nodeId(),
                "feedingSiteId", target, "validFrom", start.plusSeconds(60).toString()))).statusCode());
        assertNull(deployments.findById(deployment).orElseThrow().getValidUntil());
        assertEquals(403, post("/api/v1/organizations/" + s.orgB().getId() + "/deployments/move",
                mapper.writeValueAsString(Map.of("nodeId", foreignNode.nodeId(), "feedingSiteId", foreignSite,
                        "validFrom", start.toString()))).statusCode());
    }

    @Test
    void concurrentMovesCannotCreateOverlappingAssignments() throws Exception {
        Seed s = seed();
        login("a9-admin-a@example.org", "supersecret-password-a");
        UUID site = createSite(s.orgA().getId(), "Old");
        UUID targetA = createSite(s.orgA().getId(), "Target A");
        UUID targetB = createSite(s.orgA().getId(), "Target B");
        NodeKeys node = claimNode(s.orgA().getId());
        Instant start = Instant.parse("2025-01-01T00:00:00Z");
        createDeployment(s.orgA().getId(), node.nodeId(), site, start, null);
        String path = "/api/v1/organizations/" + s.orgA().getId() + "/deployments/move";
        try (var executor = Executors.newFixedThreadPool(2)) {
            var tasks = List.<Callable<Integer>>of(
                    () -> post(path, mapper.writeValueAsString(Map.of("nodeId", node.nodeId(),
                            "feedingSiteId", targetA, "validFrom", start.plusSeconds(60).toString()))).statusCode(),
                    () -> post(path, mapper.writeValueAsString(Map.of("nodeId", node.nodeId(),
                            "feedingSiteId", targetB, "validFrom", start.plusSeconds(60).toString()))).statusCode());
            var statuses = executor.invokeAll(tasks).stream().map(future -> {
                try { return future.get(); } catch (Exception e) { throw new RuntimeException(e); }
            }).sorted().toList();
            assertEquals(List.of(200, 409), statuses);
        }
        var history = deployments.findByNodeNodeId(node.nodeId());
        assertEquals(2, history.size());
        assertEquals(1, history.stream().filter(d -> d.getValidUntil() == null).count());
    }

    @Test
    void chipActivityUsesSightingTimeAndFrozenSitesWithoutFabricatingUnknownTime() throws Exception {
        Seed s = seed();
        String path = "/api/v1/organizations/" + s.orgA().getId() + "/chip-activity";
        assertEquals(401, get(path).statusCode());
        login("a9-admin-a@example.org", "supersecret-password-a");
        assertEquals("[]", get(path).body());
        NodeKeys node = claimNode(s.orgA().getId());
        UUID site = createSite(s.orgA().getId(), "Own site");
        Instant start = Instant.parse("2025-01-01T00:00:00Z");
        createDeployment(s.orgA().getId(), node.nodeId(), site, start, null);
        ingest(s.orgA().getId(), List.of(item(node.nodeId(), 1, "chip-new", start.plusSeconds(120).toEpochMilli(), "SYNCED")));
        ingest(s.orgA().getId(), List.of(
                item(node.nodeId(), 2, "chip-new", start.plusSeconds(60).toEpochMilli(), "RTC_ONLY"),
                item(node.nodeId(), 3, "chip-new", null, "UNKNOWN"),
                item(node.nodeId(), 4, "chip-unknown", null, "UNKNOWN")));
        login("a9-member-a@example.org", "supersecret-password-m");
        var response = get(path);
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        JsonNode activity = mapper.readTree(response.body());
        assertEquals(2, activity.size());
        assertEquals("CHIP-NEW", activity.get(0).get("chipId").asText());
        assertEquals(Long.toString(start.plusSeconds(120).toEpochMilli()), activity.get(0).get("lastSeenAtMillis").asText());
        assertEquals(3, activity.get(0).get("observationCount").asInt());
        assertEquals(1, activity.get(0).get("uncertainClockCount").asInt());
        assertEquals(site.toString(), activity.get(0).get("feedingSiteIds").get(0).asText());
        assertTrue(activity.get(1).get("lastSeenAtMillis").isNull());
        assertEquals(0, activity.get(1).get("feedingSiteIds").size());
        assertEquals(403, get("/api/v1/organizations/" + s.orgB().getId() + "/chip-activity").statusCode());
        // Paginate in reverse receipt order without changing the existing default.
        var newest = mapper.readTree(get("/api/v1/observations?organizationId=" + s.orgA().getId()
                + "&newestFirst=true&limit=1").body());
        assertEquals("4", newest.get(0).get("sequence").asText());
        assertEquals("1", mapper.readTree(get("/api/v1/observations?organizationId=" + s.orgA().getId()
                + "&limit=1").body()).get(0).get("sequence").asText());
        login("a9-admin-b@example.org", "supersecret-password-b");
        assertEquals(403, get(path).statusCode());
    }
}
