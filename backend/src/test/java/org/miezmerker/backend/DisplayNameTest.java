package org.miezmerker.backend;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.miezmerker.backend.bootstrap.BootstrapRunner;
import org.miezmerker.backend.domain.AppUser;
import org.miezmerker.backend.domain.MembershipRole;
import org.miezmerker.backend.domain.MembershipStatus;
import org.miezmerker.backend.domain.Organization;
import org.miezmerker.backend.domain.OrganizationMembership;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Acceptance tests for #32: global {@code AppUser.displayName}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class DisplayNameTest {
    @Value("${local.server.port}") int port;

    @Autowired AppUserRepository users;
    @Autowired OrganizationRepository organizations;
    @Autowired MembershipRepository memberships;
    @Autowired AppDeviceRepository devices;
    @Autowired NodeRepository nodes;
    @Autowired RawObservationRepository observations;
    @Autowired DerivedVisitRepository derivedVisits;
    @Autowired NodeDeploymentRepository deployments;
    @Autowired CatRepository cats;
    @Autowired FeedingSiteRepository sites;
    @Autowired PasswordEncoder passwords;
    @Autowired jakarta.validation.Validator validator;

    final ObjectMapper mapper = new ObjectMapper();
    HttpClient client;
    CookieManager cookies;

    @BeforeEach
    void http() {
        cookies = new CookieManager();
        client = HttpClient.newBuilder()
                .cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    String base(String path) {
        return "http://localhost:" + port + path;
    }

    String csrfToken() throws Exception {
        var res = client.send(HttpRequest.newBuilder(URI.create(base("/api/v1/auth/csrf")))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode(), res.body());
        return mapper.readTree(res.body()).get("token").asText();
    }

    HttpResponse<String> post(String path, String json) throws Exception {
        String token = csrfToken();
        return client.send(HttpRequest.newBuilder(URI.create(base(path)))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", token)
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> patch(String path, String json) throws Exception {
        String token = csrfToken();
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

    void clean() {
        derivedVisits.deleteAll();
        observations.deleteAll();
        deployments.deleteAll();
        cats.deleteAll();
        sites.deleteAll();
        devices.deleteAll();
        nodes.deleteAll();
        memberships.deleteAll();
        users.deleteAll();
        organizations.deleteAll();
    }

    record Fixture(Organization orgA, Organization orgB, AppUser adminA, AppUser adminB) {}

    Fixture seed() {
        clean();
        Organization orgA = organizations.save(new Organization("org-a", "Org A", null));
        Organization orgB = organizations.save(new Organization("org-b", "Org B", null));
        AppUser adminA = users.save(new AppUser("admin-a@example.org",
                passwords.encode("supersecret-password-a")));
        AppUser adminB = users.save(new AppUser("admin-b@example.org",
                passwords.encode("supersecret-password-b")));
        memberships.save(new OrganizationMembership(orgA, adminA, MembershipRole.ADMIN, MembershipStatus.ACTIVE));
        memberships.save(new OrganizationMembership(orgB, adminB, MembershipRole.ADMIN, MembershipStatus.ACTIVE));
        return new Fixture(orgA, orgB, adminA, adminB);
    }

    void login(String email, String password, int expected) throws Exception {
        var res = post("/api/v1/auth/login",
                "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
        assertEquals(expected, res.statusCode(), res.body());
    }

    @Test
    void migrationPreservesExistingUsersWithNullDisplayName() throws Exception {
        // Insert the legacy user before V8, rather than into an already migrated DB.
        String url = "jdbc:h2:mem:display-name-migration-" + UUID.randomUUID() + ";MODE=PostgreSQL";
        String hash = passwords.encode("supersecret-password-1");
        UUID legacyId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-10-01T10:00:00Z");
        Instant lastLoginAt = Instant.parse("2026-10-02T10:00:00Z");
        try (var connection = DriverManager.getConnection(url, "sa", "")) {
            Flyway.configure().dataSource(url, "sa", "").target("7").load().migrate();
            try (var insert = connection.prepareStatement("""
                    INSERT INTO app_users (id, email, password_hash, status, created_at, last_login_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """)) {
                insert.setObject(1, legacyId);
                insert.setString(2, "legacy@example.org");
                insert.setString(3, hash);
                insert.setString(4, "ACTIVE");
                insert.setObject(5, createdAt.atOffset(java.time.ZoneOffset.UTC));
                insert.setObject(6, lastLoginAt.atOffset(java.time.ZoneOffset.UTC));
                insert.executeUpdate();
            }
            var result = Flyway.configure().dataSource(url, "sa", "").load().migrate();
            assertEquals(1, result.migrationsExecuted);
            try (var query = connection.createStatement();
                    var row = query.executeQuery("SELECT * FROM app_users")) {
                assertTrue(row.next());
                assertEquals(legacyId, row.getObject("id", UUID.class));
                assertEquals("legacy@example.org", row.getString("email"));
                assertNull(row.getString("display_name"));
                assertEquals(hash, row.getString("password_hash"));
                assertTrue(passwords.matches("supersecret-password-1", row.getString("password_hash")));
                assertEquals("ACTIVE", row.getString("status"));
                assertEquals(createdAt, row.getTimestamp("created_at").toInstant());
                assertEquals(lastLoginAt, row.getTimestamp("last_login_at").toInstant());
                assertFalse(row.next());
            }
        }
    }

    @Test
    void bootstrapWithDisplayNameStoresTrimmedName() {
        clean();
        var runner = new BootstrapRunner(users, organizations, memberships, passwords, validator,
                "bootstrap-named@example.org", "supersecret-bootstrap-1", "  Ada Lovelace  ",
                "versuch", "Versuchsorganisation", null);
        runner.run(null);
        AppUser stored = users.findByEmail("bootstrap-named@example.org").orElseThrow();
        assertEquals("Ada Lovelace", stored.getDisplayName());
        assertTrue(passwords.matches("supersecret-bootstrap-1", stored.getPasswordHash()));
    }

    @Test
    void bootstrapWithoutDisplayNameStillWorksWithNull() {
        clean();
        var runner = new BootstrapRunner(users, organizations, memberships, passwords, validator,
                "bootstrap-plain@example.org", "supersecret-bootstrap-1", null,
                "versuch", "Versuchsorganisation", null);
        runner.run(null);
        AppUser stored = users.findByEmail("bootstrap-plain@example.org").orElseThrow();
        assertNull(stored.getDisplayName());
        assertTrue(passwords.matches("supersecret-bootstrap-1", stored.getPasswordHash()));

        // Blank-only bootstrap name also means "no name".
        clean();
        var blank = new BootstrapRunner(users, organizations, memberships, passwords, validator,
                "bootstrap-blank@example.org", "supersecret-bootstrap-1", "   ",
                "versuch", "Versuchsorganisation", null);
        blank.run(null);
        assertNull(users.findByEmail("bootstrap-blank@example.org").orElseThrow().getDisplayName());
    }

    @Test
    void bootstrapRejectsOverlongDisplayName() {
        clean();
        var runner = new BootstrapRunner(users, organizations, memberships, passwords, validator,
                "bootstrap-long@example.org", "supersecret-bootstrap-1", "a".repeat(256),
                "versuch", "Versuchsorganisation", null);
        assertThrows(IllegalStateException.class, () -> runner.run(null));
        assertEquals(0, users.count());
    }

    @Test
    void memberListingContainsDisplayNameAndSessionExposesIt() throws Exception {
        Fixture f = seed();
        login("admin-a@example.org", "supersecret-password-a", 200);
        var created = post("/api/v1/organizations/" + f.orgA().getId() + "/members",
                mapper.writeValueAsString(java.util.Map.of("email", "named@example.org",
                        "password", "supersecret-named-1", "role", "MEMBER", "displayName", "  Ada  ")));
        assertEquals(200, created.statusCode(), created.body());
        assertEquals("Ada", mapper.readTree(created.body()).get("displayName").asText());

        var list = get("/api/v1/organizations/" + f.orgA().getId() + "/members");
        assertEquals(200, list.statusCode(), list.body());
        JsonNode named = null;
        for (JsonNode row : mapper.readTree(list.body())) {
            if (row.get("email").asText().equals("named@example.org")) {
                named = row;
            }
        }
        assertNotNull(named, list.body());
        assertEquals("Ada", named.get("displayName").asText());
        assertEquals("MEMBER", named.get("role").asText());
        assertEquals("ACTIVE", named.get("status").asText());

        // Session exposes the same global name.
        login("named@example.org", "supersecret-named-1", 200);
        JsonNode session = mapper.readTree(get("/api/v1/auth/session").body());
        assertEquals("Ada", session.get("displayName").asText());
        assertEquals("named@example.org", session.get("email").asText());
    }

    @Test
    void fallbackWhenNameIsMissingIsNullNeverFabricated() throws Exception {
        Fixture f = seed();
        login("admin-a@example.org", "supersecret-password-a", 200);
        var created = post("/api/v1/organizations/" + f.orgA().getId() + "/members",
                "{\"email\":\"plain@example.org\",\"password\":\"supersecret-plain-1\",\"role\":\"MEMBER\"}");
        assertEquals(200, created.statusCode(), created.body());
        JsonNode body = mapper.readTree(created.body());
        assertTrue(body.get("displayName") == null || body.get("displayName").isNull(),
                created.body());

        var list = mapper.readTree(get("/api/v1/organizations/" + f.orgA().getId() + "/members").body());
        for (JsonNode row : list) {
            if (row.get("email").asText().equals("plain@example.org")) {
                assertTrue(row.get("displayName") == null || row.get("displayName").isNull(),
                        row.toString());
            }
            if (row.get("email").asText().equals("admin-a@example.org")) {
                assertTrue(row.get("displayName") == null || row.get("displayName").isNull(),
                        row.toString());
            }
        }

        login("plain@example.org", "supersecret-plain-1", 200);
        JsonNode session = mapper.readTree(get("/api/v1/auth/session").body());
        assertTrue(session.get("displayName") == null || session.get("displayName").isNull(),
                session.toString());
        // The stored value is null, not the email prefix.
        assertNull(users.findByEmail("plain@example.org").orElseThrow().getDisplayName());
    }

    @Test
    void whitespaceIsTrimmedAndBlankClearsViaPatch() throws Exception {
        Fixture f = seed();
        login("admin-a@example.org", "supersecret-password-a", 200);
        var created = post("/api/v1/organizations/" + f.orgA().getId() + "/members",
                mapper.writeValueAsString(java.util.Map.of("email", "ws@example.org",
                        "password", "supersecret-ws-123", "role", "MEMBER", "displayName", "  Ada  ")));
        assertEquals(200, created.statusCode(), created.body());
        UUID membershipId = UUID.fromString(mapper.readTree(created.body()).get("membershipId").asText());
        assertEquals("Ada", users.findByEmail("ws@example.org").orElseThrow().getDisplayName());

        var renamed = patch("/api/v1/organizations/" + f.orgA().getId() + "/members/" + membershipId,
                mapper.writeValueAsString(java.util.Map.of("displayName", "  Grace Hopper  ")));
        assertEquals(200, renamed.statusCode(), renamed.body());
        assertEquals("Grace Hopper", mapper.readTree(renamed.body()).get("displayName").asText());

        var cleared = patch("/api/v1/organizations/" + f.orgA().getId() + "/members/" + membershipId,
                mapper.writeValueAsString(java.util.Map.of("displayName", "   ")));
        assertEquals(200, cleared.statusCode(), cleared.body());
        assertTrue(cleared.body().contains("displayName")
                && (mapper.readTree(cleared.body()).get("displayName") == null
                        || mapper.readTree(cleared.body()).get("displayName").isNull()),
                cleared.body());
        assertNull(users.findByEmail("ws@example.org").orElseThrow().getDisplayName());
    }

    @Test
    void unicodeWhitespaceAndLengthAreNormalizedBeforeValidation() throws Exception {
        Fixture f = seed();
        login("admin-a@example.org", "supersecret-password-a", 200);
        String name = "a".repeat(255);
        String padding = "\u00a0\u2003\u202f\u3000\t";
        var created = post("/api/v1/organizations/" + f.orgA().getId() + "/members",
                mapper.writeValueAsString(java.util.Map.of("email", "unicode-space@example.org",
                        "password", "supersecret-space-1", "role", "MEMBER",
                        "displayName", padding + name + padding)));
        assertEquals(200, created.statusCode(), created.body());
        assertEquals(name, mapper.readTree(created.body()).get("displayName").asText());
        UUID membershipId = UUID.fromString(mapper.readTree(created.body()).get("membershipId").asText());
        String path = "/api/v1/organizations/" + f.orgA().getId() + "/members/" + membershipId;
        var renamed = patch(path, mapper.writeValueAsString(java.util.Map.of(
                "displayName", padding + "Ada\u00a0Lovelace" + padding)));
        assertEquals(200, renamed.statusCode(), renamed.body());
        assertEquals("Ada\u00a0Lovelace", mapper.readTree(renamed.body()).get("displayName").asText());
        var cleared = patch(path, mapper.writeValueAsString(java.util.Map.of("displayName", padding)));
        assertEquals(200, cleared.statusCode(), cleared.body());
        assertTrue(mapper.readTree(cleared.body()).get("displayName").isNull());
        assertNull(users.findByEmail("unicode-space@example.org").orElseThrow().getDisplayName());
    }

    @Test
    void bootstrapNormalizesUnicodeWhitespace() {
        clean();
        var runner = new BootstrapRunner(users, organizations, memberships, passwords, validator,
                "bootstrap-unicode@example.org", "supersecret-bootstrap-1", "\u2003\u00a0Ada\u202f\u3000",
                "versuch", "Versuchsorganisation", null);
        runner.run(null);
        assertEquals("Ada", users.findByEmail("bootstrap-unicode@example.org").orElseThrow().getDisplayName());
    }

    @Test
    void openApiDescribesNullableDisplayNames() throws Exception {
        JsonNode schemas = mapper.readTree(get("/api/v1/openapi").body()).at("/components/schemas");
        for (String schema : java.util.List.of("MemberView", "SessionView", "CreateMemberRequest", "UpdateMemberRequest")) {
            JsonNode type = schemas.get(schema).at("/properties/displayName/type");
            assertTrue(type.isArray(), schema + " must accept both a string and null: " + type);
            assertTrue(type.toString().contains("\"string\""), schema);
            assertTrue(type.toString().contains("\"null\""), schema);
        }
    }

    @Test
    void maxLengthIsRejectedAndUnicodeIsAccepted() throws Exception {
        Fixture f = seed();
        login("admin-a@example.org", "supersecret-password-a", 200);
        String tooLong = "a".repeat(256);
        var rejected = post("/api/v1/organizations/" + f.orgA().getId() + "/members",
                mapper.writeValueAsString(java.util.Map.of("email", "long@example.org",
                        "password", "supersecret-long-1", "role", "MEMBER", "displayName", tooLong)));
        assertEquals(400, rejected.statusCode(), rejected.body());
        assertTrue(users.findByEmail("long@example.org").isEmpty());

        String unicode = "Müller-Schön Özlem 日本語 🎉";
        var created = post("/api/v1/organizations/" + f.orgA().getId() + "/members",
                mapper.writeValueAsString(java.util.Map.of("email", "unicode@example.org",
                        "password", "supersecret-uni-12", "role", "MEMBER", "displayName", unicode)));
        assertEquals(200, created.statusCode(), created.body());
        assertEquals(unicode, mapper.readTree(created.body()).get("displayName").asText());
        assertEquals(unicode, users.findByEmail("unicode@example.org").orElseThrow().getDisplayName());

        UUID membershipId = UUID.fromString(mapper.readTree(created.body()).get("membershipId").asText());
        var patchRejected = patch("/api/v1/organizations/" + f.orgA().getId() + "/members/" + membershipId,
                mapper.writeValueAsString(java.util.Map.of("displayName", "b".repeat(256))));
        assertEquals(400, patchRejected.statusCode(), patchRejected.body());
        // Failed update leaves the stored name untouched.
        assertEquals(unicode, users.findByEmail("unicode@example.org").orElseThrow().getDisplayName());

        // Exactly 255 characters is accepted.
        var boundary = patch("/api/v1/organizations/" + f.orgA().getId() + "/members/" + membershipId,
                mapper.writeValueAsString(java.util.Map.of("displayName", "c".repeat(255))));
        assertEquals(200, boundary.statusCode(), boundary.body());
    }

    @Test
    void multiOrgUserSharesOneGlobalNameWithIndependentRoleAndStatus() throws Exception {
        Fixture f = seed();
        login("admin-a@example.org", "supersecret-password-a", 200);
        var created = post("/api/v1/organizations/" + f.orgA().getId() + "/members",
                mapper.writeValueAsString(java.util.Map.of("email", "multi2@example.org",
                        "password", "supersecret-multi-1", "role", "MEMBER", "displayName", "Multi Person")));
        assertEquals(200, created.statusCode(), created.body());

        // Same AppUser joins the second organization with a different role.
        login("admin-b@example.org", "supersecret-password-b", 200);
        var joined = post("/api/v1/organizations/" + f.orgB().getId() + "/members",
                mapper.writeValueAsString(java.util.Map.of("email", "multi2@example.org",
                        "role", "ADMIN")));
        assertEquals(200, joined.statusCode(), joined.body());
        // No name supplied on the second join: the global name is preserved, not cleared.
        assertEquals("Multi Person", mapper.readTree(joined.body()).get("displayName").asText());

        UUID membershipA = UUID.fromString(mapper.readTree(created.body()).get("membershipId").asText());
        UUID membershipB = UUID.fromString(mapper.readTree(joined.body()).get("membershipId").asText());

        // Rename from organization A: the same name appears in organization B.
        login("admin-a@example.org", "supersecret-password-a", 200);
        var renamed = patch("/api/v1/organizations/" + f.orgA().getId() + "/members/" + membershipA,
                mapper.writeValueAsString(java.util.Map.of("displayName", "Renamed Global")));
        assertEquals(200, renamed.statusCode(), renamed.body());

        login("admin-b@example.org", "supersecret-password-b", 200);
        JsonNode listB = mapper.readTree(get("/api/v1/organizations/" + f.orgB().getId() + "/members").body());
        String nameInB = null;
        String roleInB = null;
        for (JsonNode row : listB) {
            if (row.get("membershipId").asText().equals(membershipB.toString())) {
                nameInB = row.get("displayName").asText();
                roleInB = row.get("role").asText();
            }
        }
        assertEquals("Renamed Global", nameInB);
        assertEquals("ADMIN", roleInB);

        login("admin-a@example.org", "supersecret-password-a", 200);
        JsonNode listA = mapper.readTree(get("/api/v1/organizations/" + f.orgA().getId() + "/members").body());
        String nameInA = null;
        String roleInA = null;
        String statusInA = null;
        for (JsonNode row : listA) {
            if (row.get("membershipId").asText().equals(membershipA.toString())) {
                nameInA = row.get("displayName").asText();
                roleInA = row.get("role").asText();
                statusInA = row.get("status").asText();
            }
        }
        assertEquals("Renamed Global", nameInA);
        assertEquals("MEMBER", roleInA);
        assertEquals("ACTIVE", statusInA);

        // Role/status stay independent per membership: disable in A, B remains ACTIVE ADMIN.
        var disabled = patch("/api/v1/organizations/" + f.orgA().getId() + "/members/" + membershipA,
                "{\"status\":\"DISABLED\"}");
        assertEquals(200, disabled.statusCode(), disabled.body());
        assertEquals("DISABLED", mapper.readTree(disabled.body()).get("status").asText());
        var membershipReloadedB = memberships.findById(membershipB).orElseThrow();
        assertEquals(MembershipStatus.ACTIVE, membershipReloadedB.getStatus());
        assertEquals(MembershipRole.ADMIN, membershipReloadedB.getRole());
    }

    @Test
    void organizationACannotEnumerateUsersFromOrganizationB() throws Exception {
        Fixture f = seed();
        login("admin-a@example.org", "supersecret-password-a", 200);
        assertEquals(403, get("/api/v1/organizations/" + f.orgB().getId() + "/members").statusCode());
        UUID foreignMembership = memberships.findByOrganizationId(f.orgB().getId()).get(0).getId();
        var attempt = patch("/api/v1/organizations/" + f.orgA().getId() + "/members/" + foreignMembership,
                mapper.writeValueAsString(java.util.Map.of("displayName", "Hijacked")));
        assertEquals(404, attempt.statusCode(), attempt.body());
        // The foreign user's name is unchanged.
        AppUser adminB = users.findByEmail("admin-b@example.org").orElseThrow();
        assertNull(adminB.getDisplayName());
    }

    @Test
    void memberUpdateRequiresAdminAndExistingLoginKeepsWorkingWithUnchangedHash() throws Exception {
        Fixture f = seed();
        // MEMBER cannot rename even themselves via the admin path.
        AppUser member = users.save(new AppUser("member-x@example.org", passwords.encode("supersecret-member-1")));
        OrganizationMembership memberMembership = memberships.save(new OrganizationMembership(
                f.orgA(), member, MembershipRole.MEMBER, MembershipStatus.ACTIVE));
        login("member-x@example.org", "supersecret-member-1", 200);
        var denied = patch("/api/v1/organizations/" + f.orgA().getId() + "/members/" + memberMembership.getId(),
                mapper.writeValueAsString(java.util.Map.of("displayName", "Nope")));
        assertEquals(403, denied.statusCode(), denied.body());

        // ADMIN renames; password hash is untouched and login still works.
        login("admin-a@example.org", "supersecret-password-a", 200);
        String hashBefore = users.findByEmail("member-x@example.org").orElseThrow().getPasswordHash();
        var renamed = patch("/api/v1/organizations/" + f.orgA().getId() + "/members/" + memberMembership.getId(),
                mapper.writeValueAsString(java.util.Map.of("displayName", "Member X")));
        assertEquals(200, renamed.statusCode(), renamed.body());
        String hashAfter = users.findByEmail("member-x@example.org").orElseThrow().getPasswordHash();
        assertEquals(hashBefore, hashAfter);
        login("member-x@example.org", "supersecret-member-1", 200);
        JsonNode session = mapper.readTree(get("/api/v1/auth/session").body());
        assertEquals("Member X", session.get("displayName").asText());
    }
}
