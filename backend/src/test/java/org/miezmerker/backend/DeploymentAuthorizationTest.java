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
import org.miezmerker.backend.repo.FeedingSiteRepository;
import org.miezmerker.backend.repo.MembershipRepository;
import org.miezmerker.backend.repo.NodeDeploymentRepository;
import org.miezmerker.backend.repo.NodeRepository;
import org.miezmerker.backend.repo.OrganizationRepository;
import org.miezmerker.backend.repo.RawObservationRepository;
import org.miezmerker.backend.service.DeploymentService;
import org.miezmerker.backend.support.TestDatabaseCleaner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Authorization acceptance tests for #51: every {@code NodeDeployment}
 * write (create, close/update, move, delete) requires ACTIVE ADMIN.
 * Reads stay available to ACTIVE MEMBER. The service layer fails closed
 * for direct calls, tenant boundaries hold, and an ADMIN move preserves
 * frozen historical observation/visit attribution.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Tag("node")
@Tag("auth")
class DeploymentAuthorizationTest {
    @Value("${local.server.port}") int port;

    @Autowired AppUserRepository users;
    @Autowired OrganizationRepository organizations;
    @Autowired MembershipRepository memberships;
    @Autowired NodeRepository nodes;
    @Autowired FeedingSiteRepository sites;
    @Autowired NodeDeploymentRepository deployments;
    @Autowired RawObservationRepository observations;
    @Autowired DeploymentService deploymentService;
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
        Organization orgA = organizations.save(new Organization("org-d51a", "Org A", null));
        Organization orgB = organizations.save(new Organization("org-d51b", "Org B", null));
        AppUser adminA = users.save(
                new AppUser("d51-admin-a@example.org", passwords.encode("supersecret-password-a")));
        AppUser memberA = users.save(new AppUser("d51-member-a@example.org",
                passwords.encode("supersecret-password-m")));
        AppUser adminB = users.save(
                new AppUser("d51-admin-b@example.org", passwords.encode("supersecret-password-b")));
        AppUser memberB = users.save(new AppUser("d51-member-b@example.org",
                passwords.encode("supersecret-password-n")));
        memberships.save(
                new OrganizationMembership(orgA, adminA, MembershipRole.ADMIN, MembershipStatus.ACTIVE));
        memberships.save(new OrganizationMembership(orgA, memberA, MembershipRole.MEMBER,
                MembershipStatus.ACTIVE));
        memberships.save(
                new OrganizationMembership(orgB, adminB, MembershipRole.ADMIN, MembershipStatus.ACTIVE));
        memberships.save(new OrganizationMembership(orgB, memberB, MembershipRole.MEMBER,
                MembershipStatus.ACTIVE));
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

    String deploymentBody(UUID nodeId, UUID siteId, Instant from, Instant until) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("nodeId", nodeId.toString());
        body.put("feedingSiteId", siteId.toString());
        body.put("validFrom", from.toString());
        if (until != null) {
            body.put("validUntil", until.toString());
        }
        return mapper.writeValueAsString(body);
    }

    UUID userId(String email) {
        return users.findByEmail(email).orElseThrow().getId();
    }

    void assertForbidden(ResponseStatusException e) {
        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode());
    }

    @Test
    void memberAndAdminCanReadDeploymentContext() throws Exception {
        Seed s = seed();
        login("d51-admin-a@example.org", "supersecret-password-a");
        NodeKeys n = claimNode(s.orgA().getId());
        UUID site = createSite(s.orgA().getId(), "Site");
        Instant from = Instant.now().minus(Duration.ofDays(5));
        var created = post("/api/v1/organizations/" + s.orgA().getId() + "/deployments",
                deploymentBody(n.nodeId(), site, from, null));
        assertEquals(200, created.statusCode(), created.body());
        UUID deploymentId = UUID.fromString(mapper.readTree(created.body()).get("id").asText());
        String orgPath = "/api/v1/organizations/" + s.orgA().getId();

        login("d51-member-a@example.org", "supersecret-password-m");
        assertEquals(200, get(orgPath + "/deployments").statusCode());
        assertEquals(200, get(orgPath + "/deployments/" + deploymentId).statusCode());
        assertEquals(200, get(orgPath + "/deployments?nodeId=" + n.nodeId()).statusCode());

        login("d51-admin-a@example.org", "supersecret-password-a");
        assertEquals(200, get(orgPath + "/deployments").statusCode());
        assertEquals(200, get(orgPath + "/deployments/" + deploymentId).statusCode());
    }

    @Test
    void createRequiresAdmin() throws Exception {
        Seed s = seed();
        login("d51-admin-a@example.org", "supersecret-password-a");
        NodeKeys n = claimNode(s.orgA().getId());
        UUID site = createSite(s.orgA().getId(), "Site");
        Instant from = Instant.now().minus(Duration.ofDays(5));
        String path = "/api/v1/organizations/" + s.orgA().getId() + "/deployments";

        login("d51-member-a@example.org", "supersecret-password-m");
        assertEquals(403, post(path, deploymentBody(n.nodeId(), site, from, null)).statusCode());
        assertEquals(0, deployments.findByNodeNodeId(n.nodeId()).size());

        login("d51-admin-a@example.org", "supersecret-password-a");
        var created = post(path, deploymentBody(n.nodeId(), site, from, null));
        assertEquals(200, created.statusCode(), created.body());
        assertEquals(1, deployments.findByNodeNodeId(n.nodeId()).size());
    }

    @Test
    void closeRequiresAdmin() throws Exception {
        Seed s = seed();
        login("d51-admin-a@example.org", "supersecret-password-a");
        NodeKeys n = claimNode(s.orgA().getId());
        UUID site = createSite(s.orgA().getId(), "Site");
        Instant from = Instant.now().minus(Duration.ofDays(5));
        var created = post("/api/v1/organizations/" + s.orgA().getId() + "/deployments",
                deploymentBody(n.nodeId(), site, from, null));
        UUID deploymentId = UUID.fromString(mapper.readTree(created.body()).get("id").asText());
        String path = "/api/v1/organizations/" + s.orgA().getId() + "/deployments/" + deploymentId;
        Instant closeAt = Instant.now().plus(Duration.ofDays(1));

        login("d51-member-a@example.org", "supersecret-password-m");
        assertEquals(403, patch(path,
                "{\"validUntil\":\"" + closeAt + "\"}").statusCode());
        assertNull(deployments.findById(deploymentId).orElseThrow().getValidUntil());

        login("d51-admin-a@example.org", "supersecret-password-a");
        assertEquals(200, patch(path,
                "{\"validUntil\":\"" + closeAt + "\"}").statusCode());
        assertEquals(closeAt.truncatedTo(java.time.temporal.ChronoUnit.MILLIS),
                deployments.findById(deploymentId).orElseThrow().getValidUntil());
    }

    @Test
    void moveRequiresAdmin() throws Exception {
        Seed s = seed();
        login("d51-admin-a@example.org", "supersecret-password-a");
        NodeKeys n = claimNode(s.orgA().getId());
        UUID siteA = createSite(s.orgA().getId(), "Site A");
        UUID siteB = createSite(s.orgA().getId(), "Site B");
        Instant from = Instant.now().minus(Duration.ofDays(5));
        var created = post("/api/v1/organizations/" + s.orgA().getId() + "/deployments",
                deploymentBody(n.nodeId(), siteA, from, null));
        UUID deploymentId = UUID.fromString(mapper.readTree(created.body()).get("id").asText());
        String path = "/api/v1/organizations/" + s.orgA().getId() + "/deployments/move";
        Instant moveAt = Instant.now();

        login("d51-member-a@example.org", "supersecret-password-m");
        assertEquals(403, post(path, mapper.writeValueAsString(Map.of(
                "nodeId", n.nodeId(), "feedingSiteId", siteB, "validFrom", moveAt.toString())))
                .statusCode());
        assertNull(deployments.findById(deploymentId).orElseThrow().getValidUntil());
        assertEquals(1, deployments.findByNodeNodeId(n.nodeId()).size());

        login("d51-admin-a@example.org", "supersecret-password-a");
        var moved = post(path, mapper.writeValueAsString(Map.of(
                "nodeId", n.nodeId(), "feedingSiteId", siteB, "validFrom", moveAt.toString())));
        assertEquals(200, moved.statusCode(), moved.body());
        assertEquals(2, deployments.findByNodeNodeId(n.nodeId()).size());
        assertNotNull(deployments.findById(deploymentId).orElseThrow().getValidUntil());
    }

    @Test
    void deleteRequiresAdmin() throws Exception {
        Seed s = seed();
        login("d51-admin-a@example.org", "supersecret-password-a");
        NodeKeys n = claimNode(s.orgA().getId());
        UUID site = createSite(s.orgA().getId(), "Site");
        Instant from = Instant.now().minus(Duration.ofDays(5));
        var created = post("/api/v1/organizations/" + s.orgA().getId() + "/deployments",
                deploymentBody(n.nodeId(), site, from, null));
        UUID deploymentId = UUID.fromString(mapper.readTree(created.body()).get("id").asText());
        String path = "/api/v1/organizations/" + s.orgA().getId() + "/deployments/" + deploymentId;

        login("d51-member-a@example.org", "supersecret-password-m");
        assertEquals(403, delete(path).statusCode());
        assertTrue(deployments.findById(deploymentId).isPresent());

        login("d51-admin-a@example.org", "supersecret-password-a");
        assertEquals(200, delete(path).statusCode());
        assertTrue(deployments.findById(deploymentId).isEmpty());
    }

    @Test
    void tenantIsolationForDeploymentWrites() throws Exception {
        Seed s = seed();
        login("d51-admin-a@example.org", "supersecret-password-a");
        NodeKeys nodeA = claimNode(s.orgA().getId());
        UUID siteA = createSite(s.orgA().getId(), "Site A");
        Instant from = Instant.now().minus(Duration.ofDays(5));
        var created = post("/api/v1/organizations/" + s.orgA().getId() + "/deployments",
                deploymentBody(nodeA.nodeId(), siteA, from, null));
        UUID deploymentA = UUID.fromString(mapper.readTree(created.body()).get("id").asText());

        // Foreign ADMIN has no membership in org A: the org-scoped gate rejects first.
        login("d51-admin-b@example.org", "supersecret-password-b");
        assertEquals(403, post("/api/v1/organizations/" + s.orgA().getId() + "/deployments",
                deploymentBody(nodeA.nodeId(), siteA, from.plusSeconds(3600), null)).statusCode());
        assertEquals(403, post("/api/v1/organizations/" + s.orgA().getId() + "/deployments/move",
                mapper.writeValueAsString(Map.of("nodeId", nodeA.nodeId(), "feedingSiteId", siteA,
                        "validFrom", Instant.now().toString()))).statusCode());
        assertEquals(403, patch("/api/v1/organizations/" + s.orgA().getId() + "/deployments/"
                + deploymentA, "{\"validUntil\":\"" + Instant.now().plusSeconds(3600) + "\"}")
                .statusCode());
        assertEquals(403, delete("/api/v1/organizations/" + s.orgA().getId() + "/deployments/"
                + deploymentA).statusCode());

        // Foreign MEMBER likewise cannot mutate, and learns nothing new.
        login("d51-member-b@example.org", "supersecret-password-n");
        assertEquals(403, post("/api/v1/organizations/" + s.orgA().getId() + "/deployments",
                deploymentBody(nodeA.nodeId(), siteA, from.plusSeconds(7200), null)).statusCode());
        assertEquals(403, delete("/api/v1/organizations/" + s.orgA().getId() + "/deployments/"
                + deploymentA).statusCode());
        // Foreign ids stay indistinguishable: unknown and foreign read the same.
        assertEquals(403, get("/api/v1/organizations/" + s.orgA().getId() + "/deployments/"
                + deploymentA).statusCode());

        // History is untouched by all rejected attempts.
        assertEquals(1, deployments.findByNodeNodeId(nodeA.nodeId()).size());
        assertNull(deployments.findById(deploymentA).orElseThrow().getValidUntil());

        // Same-org MEMBER cannot mutate either.
        login("d51-member-a@example.org", "supersecret-password-m");
        assertEquals(403, delete("/api/v1/organizations/" + s.orgA().getId() + "/deployments/"
                + deploymentA).statusCode());
        assertTrue(deployments.findById(deploymentA).isPresent());
    }

    @Test
    void adminMovePreservesHistoricalAttribution() throws Exception {
        Seed s = seed();
        login("d51-admin-a@example.org", "supersecret-password-a");
        String orgPath = "/api/v1/organizations/" + s.orgA().getId();
        NodeKeys node = claimNode(s.orgA().getId());
        UUID oldSite = createSite(s.orgA().getId(), "Old site");
        UUID newSite = createSite(s.orgA().getId(), "New site");
        Instant start = Instant.parse("2025-01-01T00:00:00Z");
        var created = post(orgPath + "/deployments",
                deploymentBody(node.nodeId(), oldSite, start, null));
        assertEquals(200, created.statusCode(), created.body());

        ingest(s.orgA().getId(), List.of(
                item(node.nodeId(), 1, "chip-history", start.plusSeconds(60).toEpochMilli(), "SYNCED")));
        assertEquals(200, post(orgPath + "/visits/recompute", "{}").statusCode());
        assertEquals(oldSite, observations.findByNodeNodeIdAndSequence(node.nodeId(), 1)
                .orElseThrow().getFeedingSite().getId());

        // MEMBER attempts the move first and is rejected; history is unchanged.
        login("d51-member-a@example.org", "supersecret-password-m");
        assertEquals(403, post(orgPath + "/deployments/move", mapper.writeValueAsString(Map.of(
                "nodeId", node.nodeId(), "feedingSiteId", newSite,
                "validFrom", start.plusSeconds(120).toString()))).statusCode());
        assertEquals(1, deployments.findByNodeNodeId(node.nodeId()).size());

        // ADMIN performs the valid move.
        login("d51-admin-a@example.org", "supersecret-password-a");
        var moved = post(orgPath + "/deployments/move", mapper.writeValueAsString(Map.of(
                "nodeId", node.nodeId(), "feedingSiteId", newSite,
                "validFrom", start.plusSeconds(120).toString())));
        assertEquals(200, moved.statusCode(), moved.body());
        assertEquals(2, deployments.findByNodeNodeId(node.nodeId()).size());

        // Frozen attributions still point at the old site.
        assertEquals(oldSite, observations.findByNodeNodeIdAndSequence(node.nodeId(), 1)
                .orElseThrow().getFeedingSite().getId());
        var visitsBefore = mapper.readTree(get(orgPath + "/visits").body());
        assertEquals(oldSite.toString(), visitsBefore.get(0).get("feedingSiteId").asText());

        // New records use Site B according to the existing timestamp rules.
        ingest(s.orgA().getId(), List.of(
                item(node.nodeId(), 2, "chip-history", start.plusSeconds(180).toEpochMilli(), "SYNCED")));
        assertEquals(newSite, observations.findByNodeNodeIdAndSequence(node.nodeId(), 2)
                .orElseThrow().getFeedingSite().getId());
        assertEquals(200, post(orgPath + "/visits/recompute", "{}").statusCode());
        var visitsAfter = mapper.readTree(get(orgPath + "/visits").body());
        assertEquals(2, visitsAfter.size());
        assertEquals(oldSite.toString(), visitsAfter.get(0).get("feedingSiteId").asText());
        assertEquals(newSite.toString(), visitsAfter.get(1).get("feedingSiteId").asText());

        // MEMBER can still read the deployment context.
        login("d51-member-a@example.org", "supersecret-password-m");
        assertEquals(200, get(orgPath + "/deployments").statusCode());
        assertEquals(200, get("/api/v1/observations?organizationId=" + s.orgA().getId()).statusCode());
    }

    @Test
    void serviceLayerFailsClosedForDirectCalls() throws Exception {
        Seed s = seed();
        login("d51-admin-a@example.org", "supersecret-password-a");
        NodeKeys n = claimNode(s.orgA().getId());
        UUID siteA = createSite(s.orgA().getId(), "Site A");
        UUID siteB = createSite(s.orgA().getId(), "Site B");
        UUID orgA = s.orgA().getId();
        UUID adminA = userId("d51-admin-a@example.org");
        UUID memberA = userId("d51-member-a@example.org");
        Instant from = Instant.now().minus(Duration.ofDays(5));

        // MEMBER direct service calls fail closed with 403.
        assertForbidden(assertThrows(ResponseStatusException.class,
                () -> deploymentService.create(memberA, orgA, n.nodeId(), siteA, from, null)));
        assertEquals(0, deployments.findByNodeNodeId(n.nodeId()).size());

        var created = deploymentService.create(adminA, orgA, n.nodeId(), siteA, from, null);
        assertForbidden(assertThrows(ResponseStatusException.class,
                () -> deploymentService.close(memberA, orgA, created.getId(),
                        Instant.now().plusSeconds(60))));
        assertNull(deployments.findById(created.getId()).orElseThrow().getValidUntil());

        assertForbidden(assertThrows(ResponseStatusException.class,
                () -> deploymentService.move(memberA, orgA, n.nodeId(), siteB, Instant.now())));
        assertEquals(1, deployments.findByNodeNodeId(n.nodeId()).size());

        assertForbidden(assertThrows(ResponseStatusException.class,
                () -> deploymentService.delete(memberA, orgA, created.getId())));
        assertTrue(deployments.findById(created.getId()).isPresent());

        // ADMIN direct service calls succeed.
        var moved = deploymentService.move(adminA, orgA, n.nodeId(), siteB, Instant.now());
        assertNotNull(moved.getId());
        assertEquals(2, deployments.findByNodeNodeId(n.nodeId()).size());
    }
}
