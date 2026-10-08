package org.miezmerker.backend;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.security.interfaces.ECPublicKey;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.miezmerker.backend.crypto.EcKeyUtils;
import org.miezmerker.backend.crypto.NodeClaimVerifier;
import org.miezmerker.backend.domain.AppUser;
import org.miezmerker.backend.domain.MembershipRole;
import org.miezmerker.backend.domain.MembershipStatus;
import org.miezmerker.backend.domain.NodeDevice;
import org.miezmerker.backend.domain.Organization;
import org.miezmerker.backend.domain.OrganizationMembership;
import org.miezmerker.backend.repo.AppUserRepository;
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
 * Acceptance tests for #50: optional human-readable bowl label
 * ({@code Node.displayName}) on the existing {@code Node} entity.
 *
 * <p>{@code node_id} remains the stable technical identity (BLE, claiming,
 * crypto, observations, deployments); the display name is product metadata
 * only: nullable, never unique, never part of protocol messages.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class NodeDisplayNameTest {
    @Value("${local.server.port}") int port;

    @Autowired AppUserRepository users;
    @Autowired OrganizationRepository organizations;
    @Autowired MembershipRepository memberships;
    @Autowired NodeRepository nodes;
    @Autowired RawObservationRepository observations;
    @Autowired NodeDeploymentRepository deployments;
    @Autowired FeedingSiteRepository sites;
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
        Organization orgA = organizations.save(
                new Organization("org-n50a", "Org A", null));
        Organization orgB = organizations.save(new Organization("org-n50b", "Org B", null));
        AppUser adminA = users.save(new AppUser("n50-admin-a@example.org",
                passwords.encode("supersecret-password-a")));
        AppUser memberA = users.save(new AppUser("n50-member-a@example.org",
                passwords.encode("supersecret-password-m")));
        AppUser adminB = users.save(new AppUser("n50-admin-b@example.org",
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

    @Test
    void migrationPreservesExistingNodesWithNullDisplayName() throws Exception {
        // Legacy organization/node/deployment/visit inserted before V10,
        // rather than into an already migrated DB.
        // Note: no legacy raw_observations row is inserted here on purpose.
        // Direct JDBC inserts into its IN-list constrained clock_status column
        // trip an H2 quirk ("database already closed" inside the CHECK
        // evaluation) in standalone-Flyway in-memory DBs; the same inserts
        // work through the API on H2 and on PostgreSQL. Observation
        // preservation across this purely additive migration is covered by the
        // API-level suites (Issue9/Issue10, H2 + PostgreSQL) and by the rename
        // test below, which asserts frozen attribution after a rename.
        String url = "jdbc:h2:mem:node-display-name-migration-" + UUID.randomUUID()
                + ";MODE=PostgreSQL";
        UUID orgId = UUID.randomUUID();
        UUID siteId = UUID.randomUUID();
        UUID nodeId = UUID.randomUUID();
        UUID deploymentId = UUID.randomUUID();
        UUID visitId = UUID.randomUUID();
        KeyPair kp = EcKeyUtils.generateP256();
        ECPublicKey pub = (ECPublicKey) kp.getPublic();
        String x = EcKeyUtils.xOf(pub);
        String y = EcKeyUtils.yOf(pub);
        String fingerprint = EcKeyUtils.fingerprintOfXY(x, y);
        Instant validFrom = Instant.parse("2026-01-01T00:00:00Z");
        try (var connection = DriverManager.getConnection(url, "sa", "")) {
            Flyway.configure().dataSource(url, "sa", "").target("8").load().migrate();
            try (var insertOrg = connection.prepareStatement(
                    "INSERT INTO organizations (id, slug, display_name, status)"
                            + " VALUES (?, ?, ?, ?)")) {
                insertOrg.setObject(1, orgId);
                insertOrg.setString(2, "legacy-org");
                insertOrg.setString(3, "Legacy Org");
                insertOrg.setString(4, "ACTIVE");
                insertOrg.executeUpdate();
            }
            try (var insertSite = connection.prepareStatement(
                    "INSERT INTO feeding_sites (id, organization_id, name)"
                            + " VALUES (?, ?, ?)")) {
                insertSite.setObject(1, siteId);
                insertSite.setObject(2, orgId);
                insertSite.setString(3, "Am Friedhof");
                insertSite.executeUpdate();
            }
            try (var insertNode = connection.prepareStatement(
                    "INSERT INTO nodes (node_id, public_key_x, public_key_y,"
                            + " fingerprint, organization_id, state, firmware_version)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                insertNode.setObject(1, nodeId);
                insertNode.setString(2, x);
                insertNode.setString(3, y);
                insertNode.setString(4, fingerprint);
                insertNode.setObject(5, orgId);
                insertNode.setString(6, "CLAIMED");
                insertNode.setString(7, "test-1");
                insertNode.executeUpdate();
            }
            try (var insertDeployment = connection.prepareStatement(
                    "INSERT INTO node_deployments (id, organization_id, node_id,"
                            + " feeding_site_id, valid_from) VALUES (?, ?, ?, ?, ?)")) {
                insertDeployment.setObject(1, deploymentId);
                insertDeployment.setObject(2, orgId);
                insertDeployment.setObject(3, nodeId);
                insertDeployment.setObject(4, siteId);
                insertDeployment.setObject(5,
                        validFrom.atOffset(java.time.ZoneOffset.UTC));
                insertDeployment.executeUpdate();
            }
            try (var insertVisit = connection.prepareStatement(
                    "INSERT INTO derived_visits (id, organization_id, feeding_site_id,"
                            + " chip_id, start_at, end_at, observation_count,"
                            + " algorithm_version, gap_seconds, first_observation_id,"
                            + " last_observation_id)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                insertVisit.setObject(1, visitId);
                insertVisit.setObject(2, orgId);
                insertVisit.setObject(3, siteId);
                insertVisit.setString(4, "CHIP1");
                insertVisit.setObject(5, Instant.parse("2026-02-01T12:00:00Z")
                        .atOffset(java.time.ZoneOffset.UTC));
                insertVisit.setObject(6, Instant.parse("2026-02-01T12:01:00Z")
                        .atOffset(java.time.ZoneOffset.UTC));
                insertVisit.setInt(7, 1);
                insertVisit.setString(8, "visit-gap-v1");
                insertVisit.setInt(9, 60);
                insertVisit.setObject(10, visitId);
                insertVisit.setObject(11, visitId);
                insertVisit.executeUpdate();
            }
            var result = Flyway.configure().dataSource(url, "sa", "").load().migrate();
            assertTrue(result.success);
            // V10 itself must have applied; later migrations may add to the count.
            try (var applied = connection.createStatement();
                    var versions = applied.executeQuery(
                            "SELECT count(*) FROM \"flyway_schema_history\""
                                    + " WHERE \"version\" = '10' AND \"success\" = true")) {
                assertTrue(versions.next());
                assertEquals(1, versions.getInt(1));
            }
            try (var query = connection.createStatement();
                    var row = query.executeQuery(
                            "SELECT node_id, organization_id, fingerprint,"
                                    + " firmware_version, display_name FROM nodes")) {
                assertTrue(row.next());
                assertEquals(nodeId, row.getObject("node_id", UUID.class));
                assertEquals(orgId, row.getObject("organization_id", UUID.class));
                assertEquals(fingerprint, row.getString("fingerprint"));
                assertEquals("test-1", row.getString("firmware_version"));
                assertNull(row.getString("display_name"));
                assertFalse(row.next());
            }
            // The new column is nullable (no backfill, no default names).
            try (var query = connection.createStatement();
                    var row = query.executeQuery(
                            "SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS"
                                    + " WHERE TABLE_NAME = 'NODES'"
                                    + " AND COLUMN_NAME = 'DISPLAY_NAME'")) {
                assertTrue(row.next());
                assertEquals("YES", row.getString(1));
            }
            // Deployment and visit survive with frozen attribution intact.
            try (var query = connection.createStatement();
                    var row = query.executeQuery(
                            "SELECT node_id, feeding_site_id FROM node_deployments")) {
                assertTrue(row.next());
                assertEquals(nodeId, row.getObject("node_id", UUID.class));
                assertEquals(siteId, row.getObject("feeding_site_id", UUID.class));
                assertFalse(row.next());
            }
            try (var query = connection.createStatement();
                    var row = query.executeQuery(
                            "SELECT feeding_site_id, chip_id, observation_count"
                                    + " FROM derived_visits")) {
                assertTrue(row.next());
                assertEquals(siteId, row.getObject("feeding_site_id", UUID.class));
                assertEquals("CHIP1", row.getString("chip_id"));
                assertEquals(1, row.getInt("observation_count"));
                assertFalse(row.next());
            }
        }
    }

    @Test
    void nodeResponsesIncludeNullDisplayNameByDefault() throws Exception {
        Seed s = seed();
        login("n50-admin-a@example.org", "supersecret-password-a");
        NodeKeys n = claimNode(s.orgA().getId());

        JsonNode detail = mapper.readTree(get("/api/v1/nodes/" + n.nodeId()).body());
        assertTrue(detail.get("displayName") == null || detail.get("displayName").isNull(),
                detail.toString());

        JsonNode list = mapper.readTree(
                get("/api/v1/nodes?organizationId=" + s.orgA().getId()).body());
        assertEquals(1, list.size());
        assertTrue(list.get(0).get("displayName") == null
                || list.get(0).get("displayName").isNull(), list.toString());
    }

    @Test
    void updateNameTrimsUnicodeAndBlankClearsToNull() throws Exception {
        Seed s = seed();
        login("n50-admin-a@example.org", "supersecret-password-a");
        NodeKeys n = claimNode(s.orgA().getId());

        var renamed = patch("/api/v1/nodes/" + n.nodeId(),
                mapper.writeValueAsString(Map.of("displayName", "  Der Grüne  ")));
        assertEquals(200, renamed.statusCode(), renamed.body());
        assertEquals("Der Grüne",
                mapper.readTree(renamed.body()).get("displayName").asText());
        assertEquals("Der Grüne",
                nodes.findById(n.nodeId()).orElseThrow().getDisplayName());

        var unicode = patch("/api/v1/nodes/" + n.nodeId(),
                mapper.writeValueAsString(Map.of("displayName", "Grüner Napf 🐈")));
        assertEquals(200, unicode.statusCode(), unicode.body());
        assertEquals("Grüner Napf 🐈",
                mapper.readTree(unicode.body()).get("displayName").asText());

        // Internal spacing is preserved; only the ends are normalized.
        var inner = patch("/api/v1/nodes/" + n.nodeId(),
                mapper.writeValueAsString(Map.of("displayName", "  Napf  2  ")));
        assertEquals(200, inner.statusCode(), inner.body());
        assertEquals("Napf  2",
                mapper.readTree(inner.body()).get("displayName").asText());

        var cleared = patch("/api/v1/nodes/" + n.nodeId(),
                mapper.writeValueAsString(Map.of("displayName", "   ")));
        assertEquals(200, cleared.statusCode(), cleared.body());
        assertTrue(mapper.readTree(cleared.body()).get("displayName").isNull(),
                cleared.body());
        assertNull(nodes.findById(n.nodeId()).orElseThrow().getDisplayName());
    }

    @Test
    void maxLengthIsEnforcedAfterTrimming() throws Exception {
        Seed s = seed();
        login("n50-admin-a@example.org", "supersecret-password-a");
        NodeKeys n = claimNode(s.orgA().getId());

        String boundary = "a".repeat(100);
        var ok = patch("/api/v1/nodes/" + n.nodeId(),
                mapper.writeValueAsString(Map.of("displayName", boundary)));
        assertEquals(200, ok.statusCode(), ok.body());
        assertEquals(boundary, mapper.readTree(ok.body()).get("displayName").asText());

        // Surrounding padding trims down to the 100-character boundary.
        var padded = patch("/api/v1/nodes/" + n.nodeId(), mapper.writeValueAsString(
                Map.of("displayName", "  " + "b".repeat(100) + "  ")));
        assertEquals(200, padded.statusCode(), padded.body());

        var tooLong = patch("/api/v1/nodes/" + n.nodeId(),
                mapper.writeValueAsString(Map.of("displayName", "c".repeat(101))));
        assertEquals(400, tooLong.statusCode(), tooLong.body());
        // Failed update leaves the stored name untouched.
        assertEquals("b".repeat(100),
                nodes.findById(n.nodeId()).orElseThrow().getDisplayName());
    }

    @Test
    void duplicateDisplayNamesAreAllowedInOneOrganization() throws Exception {
        Seed s = seed();
        login("n50-admin-a@example.org", "supersecret-password-a");
        NodeKeys first = claimNode(s.orgA().getId());
        NodeKeys second = claimNode(s.orgA().getId());

        assertEquals(200, patch("/api/v1/nodes/" + first.nodeId(),
                mapper.writeValueAsString(Map.of("displayName", "Silberner Napf")))
                .statusCode());
        var duplicate = patch("/api/v1/nodes/" + second.nodeId(),
                mapper.writeValueAsString(Map.of("displayName", "Silberner Napf")));
        assertEquals(200, duplicate.statusCode(), duplicate.body());

        assertEquals("Silberner Napf",
                nodes.findById(first.nodeId()).orElseThrow().getDisplayName());
        assertEquals("Silberner Napf",
                nodes.findById(second.nodeId()).orElseThrow().getDisplayName());
        assertNotEquals(first.nodeId(), second.nodeId());
    }

    @Test
    void foreignOrganizationCanNeitherRenameNorInspect() throws Exception {
        Seed s = seed();
        login("n50-admin-a@example.org", "supersecret-password-a");
        NodeKeys n = claimNode(s.orgA().getId());
        assertEquals(200, patch("/api/v1/nodes/" + n.nodeId(),
                mapper.writeValueAsString(Map.of("displayName", "Der Grüne")))
                .statusCode());

        login("n50-admin-b@example.org", "supersecret-password-b");
        // Direct rename of a foreign node: indistinguishable from unknown (404).
        assertEquals(404, patch("/api/v1/nodes/" + n.nodeId(),
                mapper.writeValueAsString(Map.of("displayName", "Hijacked")))
                .statusCode());
        // Direct read of a foreign node stays forbidden without leaking the name.
        var foreign = get("/api/v1/nodes/" + n.nodeId());
        assertEquals(403, foreign.statusCode(), foreign.body());
        assertFalse(foreign.body().contains("Der Grüne"), foreign.body());
        assertEquals(403,
                get("/api/v1/nodes?organizationId=" + s.orgA().getId()).statusCode());

        // The foreign name is unchanged.
        login("n50-admin-a@example.org", "supersecret-password-a");
        assertEquals("Der Grüne", mapper.readTree(
                get("/api/v1/nodes/" + n.nodeId()).body()).get("displayName").asText());
    }

    @Test
    void renamingPreservesTechnicalIdentityAndHistory() throws Exception {
        Seed s = seed();
        login("n50-admin-a@example.org", "supersecret-password-a");
        NodeKeys n = claimNode(s.orgA().getId());
        UUID site = UUID.fromString(mapper.readTree(post(
                "/api/v1/organizations/" + s.orgA().getId() + "/feeding-sites",
                mapper.writeValueAsString(
                        Map.of("name", "Am Friedhof"))).body()).get("id").asText());
        var deployment = post("/api/v1/organizations/" + s.orgA().getId() + "/deployments",
                mapper.writeValueAsString(Map.of("nodeId", n.nodeId().toString(),
                        "feedingSiteId", site.toString(),
                        "validFrom", Instant.now().minus(Duration.ofDays(1)).toString())));
        assertEquals(200, deployment.statusCode(), deployment.body());

        long at = Instant.now().toEpochMilli();
        var ingest = post("/api/v1/observations/ingest", mapper.writeValueAsString(Map.of(
                "organizationId", s.orgA().getId().toString(), "observations",
                List.of(Map.of("nodeId", n.nodeId().toString(), "sequence", "1",
                        "chipId", "CHIP1", "observedAtMillis", String.valueOf(at),
                        "clockStatus", "SYNCED")))));
        assertEquals(200, ingest.statusCode(), ingest.body());

        String fingerprintBefore = nodes.findById(n.nodeId()).orElseThrow().getFingerprint();
        var renamed = patch("/api/v1/nodes/" + n.nodeId(),
                mapper.writeValueAsString(Map.of("displayName", "Unterstand links")));
        assertEquals(200, renamed.statusCode(), renamed.body());

        NodeDevice reloaded = nodes.findById(n.nodeId()).orElseThrow();
        assertEquals(n.nodeId(), reloaded.getNodeId());
        assertEquals("CLAIMED", reloaded.getState().name());
        assertEquals(s.orgA().getId(), reloaded.getOrganization().getId());
        assertEquals(fingerprintBefore, reloaded.getFingerprint());
        assertEquals(n.x(), reloaded.getPublicKeyX());
        assertEquals(n.y(), reloaded.getPublicKeyY());
        assertEquals("Unterstand links", reloaded.getDisplayName());

        // Deployment history and frozen observation attribution are untouched.
        assertEquals(1, deployments.findByNodeNodeId(n.nodeId()).size());
        assertEquals(site, deployments.findByNodeNodeId(n.nodeId()).get(0)
                .getFeedingSite().getId());
        assertEquals(site, observations.findByNodeNodeIdAndSequence(n.nodeId(), 1L)
                .orElseThrow().getFeedingSite().getId());
        assertEquals("CHIP1", observations.findByNodeNodeIdAndSequence(n.nodeId(), 1L)
                .orElseThrow().getChipId());
    }

    @Test
    void openApiDescribesNullableNodeDisplayName() throws Exception {
        JsonNode schemas = mapper.readTree(get("/api/v1/openapi").body())
                .at("/components/schemas");
        for (String schema : List.of("NodeView", "UpdateNodeRequest")) {
            JsonNode type = schemas.get(schema).at("/properties/displayName/type");
            assertTrue(type.isArray(),
                    schema + " must accept both a string and null: " + type);
            assertTrue(type.toString().contains("\"string\""), schema);
            assertTrue(type.toString().contains("\"null\""), schema);
        }
    }

    @Test
    void memberCanMaintainDisplayNameWithoutTouchingDeployments() throws Exception {
        // Renaming stays on the existing ACTIVE-member metadata path (#50);
        // deployment permissions are unchanged by this ticket (not #51).
        Seed s = seed();
        login("n50-admin-a@example.org", "supersecret-password-a");
        NodeKeys n = claimNode(s.orgA().getId());
        login("n50-member-a@example.org", "supersecret-password-m");
        var renamed = patch("/api/v1/nodes/" + n.nodeId(),
                mapper.writeValueAsString(Map.of("displayName", "Napf 2")));
        assertEquals(200, renamed.statusCode(), renamed.body());
        assertEquals("Napf 2",
                mapper.readTree(renamed.body()).get("displayName").asText());
    }
}
