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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
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
 * Acceptance tests for #53: field-PWA initial bowl setup after claiming.
 *
 * <p>Claiming (#18) and business setup stay separate: CLAIMED with
 * {@code displayName=null} and no deployment is a valid resumable state.
 * Setup reuses the #50 displayName PATCH and the ADMIN-only #51 deployment
 * create/list; no new persistence or validation paths are introduced.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Tag("node")
@Tag("auth")
class NodeProvisionSetupTest {
    @Value("${local.server.port}") int port;

    @Autowired AppUserRepository users;
    @Autowired OrganizationRepository organizations;
    @Autowired MembershipRepository memberships;
    @Autowired NodeRepository nodes;
    @Autowired FeedingSiteRepository sites;
    @Autowired NodeDeploymentRepository deployments;
    @Autowired PasswordEncoder passwords;
    @Autowired TestDatabaseCleaner cleaner;

    final ObjectMapper mapper = new ObjectMapper();
    HttpClient client;
    CookieManager cookies;

    @BeforeEach
    void http() {
        cookies = new CookieManager();
        client = HttpClient.newBuilder().cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(5)).build();
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
        Organization orgA = organizations.save(new Organization("org-s53a", "Org A", null));
        Organization orgB = organizations.save(new Organization("org-s53b", "Org B", null));
        AppUser adminA = users.save(new AppUser("s53-admin-a@example.org",
                passwords.encode("supersecret-password-a")));
        AppUser memberA = users.save(new AppUser("s53-member-a@example.org",
                passwords.encode("supersecret-password-m")));
        AppUser adminB = users.save(new AppUser("s53-admin-b@example.org",
                passwords.encode("supersecret-password-b")));
        memberships.save(new OrganizationMembership(orgA, adminA, MembershipRole.ADMIN,
                MembershipStatus.ACTIVE));
        memberships.save(new OrganizationMembership(orgA, memberA, MembershipRole.MEMBER,
                MembershipStatus.ACTIVE));
        memberships.save(new OrganizationMembership(orgB, adminB, MembershipRole.ADMIN,
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
        String sig = NodeClaimVerifier.signClaim(n.kp().getPrivate(), n.nodeId(), n.x(),
                n.y(), ts);
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

    String deploymentBody(UUID nodeId, UUID siteId, Instant from) throws Exception {
        return mapper.writeValueAsString(Map.of("nodeId", nodeId.toString(),
                "feedingSiteId", siteId.toString(), "validFrom", from.toString()));
    }

    @Test
    void completeClaimNameSiteSetup() throws Exception {
        Seed s = seed();
        login("s53-admin-a@example.org", "supersecret-password-a");
        NodeKeys n = claimNode(s.orgA().getId());
        UUID site = createSite(s.orgA().getId(), "Am Friedhof");

        // Freshly claimed node is a valid incomplete state.
        JsonNode detail = mapper.readTree(get("/api/v1/nodes/" + n.nodeId()).body());
        assertTrue(detail.get("displayName") == null || detail.get("displayName").isNull());
        JsonNode emptyDeps = mapper.readTree(
                get("/api/v1/organizations/" + s.orgA().getId()
                        + "/deployments?nodeId=" + n.nodeId()).body());
        assertEquals(0, emptyDeps.size());

        var renamed = patch("/api/v1/nodes/" + n.nodeId(),
                mapper.writeValueAsString(Map.of("displayName", "Der Grüne")));
        assertEquals(200, renamed.statusCode(), renamed.body());
        assertEquals("Der Grüne", mapper.readTree(renamed.body()).get("displayName").asText());

        var created = post("/api/v1/organizations/" + s.orgA().getId() + "/deployments",
                deploymentBody(n.nodeId(), site, Instant.now().minus(Duration.ofHours(1))));
        assertEquals(200, created.statusCode(), created.body());

        JsonNode done = mapper.readTree(get("/api/v1/nodes/" + n.nodeId()).body());
        assertEquals("Der Grüne", done.get("displayName").asText());
        JsonNode deps = mapper.readTree(
                get("/api/v1/organizations/" + s.orgA().getId()
                        + "/deployments?nodeId=" + n.nodeId()).body());
        assertEquals(1, deps.size());
        assertEquals(site.toString(), deps.get(0).get("feedingSiteId").asText());
    }

    @Test
    void interruptedSetupResumesWithoutReclaiming() throws Exception {
        Seed s = seed();
        login("s53-admin-a@example.org", "supersecret-password-a");
        NodeKeys n = claimNode(s.orgA().getId());

        // Scenario A: claim ok, app closes before name/site. Node stays CLAIMED
        // with null name and no deployment; a later session resumes via plain
        // reads without another cryptographic claim.
        assertEquals("CLAIMED", nodes.findById(n.nodeId()).orElseThrow().getState().name());
        assertNull(nodes.findById(n.nodeId()).orElseThrow().getDisplayName());
        assertEquals(0, deployments.findByNodeNodeId(n.nodeId()).size());

        // Scenario B: name saved, deployment still missing. Name is preserved.
        assertEquals(200, patch("/api/v1/nodes/" + n.nodeId(),
                mapper.writeValueAsString(Map.of("displayName", "Der Grüne"))).statusCode());
        assertEquals(0, deployments.findByNodeNodeId(n.nodeId()).size());
        assertEquals("Der Grüne", nodes.findById(n.nodeId()).orElseThrow().getDisplayName());

        // Resume completes the initial assignment.
        UUID site = createSite(s.orgA().getId(), "Am Friedhof");
        var created = post("/api/v1/organizations/" + s.orgA().getId() + "/deployments",
                deploymentBody(n.nodeId(), site, Instant.now().minus(Duration.ofHours(1))));
        assertEquals(200, created.statusCode(), created.body());
        assertEquals(1, deployments.findByNodeNodeId(n.nodeId()).size());
    }

    @Test
    void lostResponseRetryDoesNotDuplicateInitialDeployment() throws Exception {
        Seed s = seed();
        login("s53-admin-a@example.org", "supersecret-password-a");
        NodeKeys n = claimNode(s.orgA().getId());
        UUID site = createSite(s.orgA().getId(), "Am Friedhof");
        Instant from = Instant.now().minus(Duration.ofHours(1));

        var first = post("/api/v1/organizations/" + s.orgA().getId() + "/deployments",
                deploymentBody(n.nodeId(), site, from));
        assertEquals(200, first.statusCode(), first.body());
        assertEquals(1, deployments.findByNodeNodeId(n.nodeId()).size());

        // Scenario C/D: backend created the deployment but the PWA lost the
        // response. A retry with an overlapping interval is rejected with 409;
        // the persisted assignment is reused instead of duplicating.
        var retry = post("/api/v1/organizations/" + s.orgA().getId() + "/deployments",
                deploymentBody(n.nodeId(), site, from.plusSeconds(60)));
        assertEquals(409, retry.statusCode(), retry.body());
        assertEquals(1, deployments.findByNodeNodeId(n.nodeId()).size());

        // Repeated setup requests never create a second open interval.
        var again = post("/api/v1/organizations/" + s.orgA().getId() + "/deployments",
                deploymentBody(n.nodeId(), site, Instant.now().plus(Duration.ofHours(1))));
        assertEquals(409, again.statusCode(), again.body());
        assertEquals(1, deployments.findByNodeNodeId(n.nodeId()).size());
    }

    @Test
    void existingActiveDeploymentIsNotOverwritten() throws Exception {
        Seed s = seed();
        login("s53-admin-a@example.org", "supersecret-password-a");
        NodeKeys n = claimNode(s.orgA().getId());
        UUID siteA = createSite(s.orgA().getId(), "Site A");
        UUID siteB = createSite(s.orgA().getId(), "Site B");
        Instant from = Instant.now().minus(Duration.ofDays(1));
        assertEquals(200, post("/api/v1/organizations/" + s.orgA().getId() + "/deployments",
                deploymentBody(n.nodeId(), siteA, from)).statusCode());

        // An initial-setup retry for another site conflicts; the open
        // assignment stays untouched. Later moves belong to Admin (#52).
        var conflicting = post("/api/v1/organizations/" + s.orgA().getId() + "/deployments",
                deploymentBody(n.nodeId(), siteB, from.plusSeconds(3600)));
        assertEquals(409, conflicting.statusCode(), conflicting.body());
        assertEquals(1, deployments.findByNodeNodeId(n.nodeId()).size());
        assertEquals(siteA, deployments.findByNodeNodeId(n.nodeId()).get(0)
                .getFeedingSite().getId());
    }

    @Test
    void duplicateBowlNamesRemainValid() throws Exception {
        Seed s = seed();
        login("s53-admin-a@example.org", "supersecret-password-a");
        NodeKeys first = claimNode(s.orgA().getId());
        NodeKeys second = claimNode(s.orgA().getId());
        assertEquals(200, patch("/api/v1/nodes/" + first.nodeId(),
                mapper.writeValueAsString(Map.of("displayName", "Silberner Napf"))).statusCode());
        var duplicate = patch("/api/v1/nodes/" + second.nodeId(),
                mapper.writeValueAsString(Map.of("displayName", "Silberner Napf")));
        assertEquals(200, duplicate.statusCode(), duplicate.body());
    }

    @Test
    void memberCannotPerformProvisioning() throws Exception {
        Seed s = seed();
        login("s53-admin-a@example.org", "supersecret-password-a");
        NodeKeys n = claimNode(s.orgA().getId());
        UUID site = createSite(s.orgA().getId(), "Am Friedhof");

        login("s53-member-a@example.org", "supersecret-password-m");
        // MEMBER cannot claim an UNCLAIMED node (covered by #18/#51) and cannot
        // create the initial deployment. A rejected setup leaves no partial
        // deployment behind.
        NodeKeys fresh = NodeKeys.fresh();
        long ts = Instant.now().toEpochMilli();
        String sig = NodeClaimVerifier.signClaim(fresh.kp().getPrivate(), fresh.nodeId(),
                fresh.x(), fresh.y(), ts);
        var claim = post("/api/v1/nodes/claim", "{\"nodeId\":\"" + fresh.nodeId()
                + "\",\"publicKeyX\":\"" + fresh.x() + "\",\"publicKeyY\":\"" + fresh.y()
                + "\",\"organizationId\":\"" + s.orgA().getId() + "\",\"timestampMillis\":" + ts
                + ",\"claimSignature\":\"" + sig + "\"}");
        assertEquals(403, claim.statusCode(), claim.body());

        var created = post("/api/v1/organizations/" + s.orgA().getId() + "/deployments",
                deploymentBody(n.nodeId(), site, Instant.now().minus(Duration.ofHours(1))));
        assertEquals(403, created.statusCode(), created.body());
        assertEquals(0, deployments.findByNodeNodeId(n.nodeId()).size());
    }

    @Test
    void foreignOrganizationIdsAreRejected() throws Exception {
        Seed s = seed();
        login("s53-admin-a@example.org", "supersecret-password-a");
        NodeKeys n = claimNode(s.orgA().getId());
        UUID siteA = createSite(s.orgA().getId(), "Site A");

        login("s53-admin-b@example.org", "supersecret-password-b");
        // Foreign ADMIN learns nothing and mutates nothing.
        assertEquals(403, get("/api/v1/nodes/" + n.nodeId()).statusCode());
        assertEquals(404, patch("/api/v1/nodes/" + n.nodeId(),
                mapper.writeValueAsString(Map.of("displayName", "Hijacked"))).statusCode());
        var foreignDeploy = post("/api/v1/organizations/" + s.orgA().getId() + "/deployments",
                deploymentBody(n.nodeId(), siteA, Instant.now().minus(Duration.ofHours(1))));
        assertEquals(403, foreignDeploy.statusCode(), foreignDeploy.body());
        assertEquals(0, deployments.findByNodeNodeId(n.nodeId()).size());

        // A foreign feeding-site id cannot be smuggled into the home org.
        login("s53-admin-b@example.org", "supersecret-password-b");
        UUID foreignSite = createSite(s.orgB().getId(), "Foreign");
        login("s53-admin-a@example.org", "supersecret-password-a");
        var smuggled = post("/api/v1/organizations/" + s.orgA().getId() + "/deployments",
                deploymentBody(n.nodeId(), foreignSite, Instant.now().minus(Duration.ofHours(1))));
        assertEquals(404, smuggled.statusCode(), smuggled.body());
        assertEquals(0, deployments.findByNodeNodeId(n.nodeId()).size());
    }

    @Test
    void emptySiteListAndUnicodeNames() throws Exception {
        Seed s = seed();
        login("s53-admin-a@example.org", "supersecret-password-a");
        JsonNode empty = mapper.readTree(
                get("/api/v1/organizations/" + s.orgA().getId() + "/feeding-sites").body());
        assertEquals(0, empty.size());

        NodeKeys n = claimNode(s.orgA().getId());
        var unicode = patch("/api/v1/nodes/" + n.nodeId(),
                mapper.writeValueAsString(Map.of("displayName", "Grüner Napf 🐈")));
        assertEquals(200, unicode.statusCode(), unicode.body());
        assertEquals("Grüner Napf 🐈",
                mapper.readTree(unicode.body()).get("displayName").asText());
        assertEquals(List.of(), deployments.findByNodeNodeId(n.nodeId()));
    }
}
