package org.miezmerker.backend;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.miezmerker.backend.bootstrap.SystemRoleMaintenance;
import org.miezmerker.backend.domain.*;
import org.miezmerker.backend.repo.*;
import org.miezmerker.backend.security.AppUserDetails;
import org.miezmerker.backend.security.SystemAuthorizationService;
import org.miezmerker.backend.support.TestDatabaseCleaner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(SystemPrivilegeTest.ProbeConfiguration.class)
@Tag("auth")
@Tag("system")
@Tag("schema")
class SystemPrivilegeTest {
    @Value("${local.server.port}") int port;
    @Autowired AppUserRepository users;
    @Autowired OrganizationRepository organizations;
    @Autowired MembershipRepository memberships;
    @Autowired CatRepository cats;
    @Autowired PasswordEncoder passwords;
    @Autowired TestDatabaseCleaner cleaner;
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired SystemAuthorizationService system;
    @Autowired org.miezmerker.backend.crypto.OfflineAuthService offline;
    final ObjectMapper mapper = new ObjectMapper();
    HttpClient client;
    Organization a, b;
    AppUser user;

    // Test-only probes: no demonstration/business endpoint is shipped.
    @TestConfiguration
    static class ProbeConfiguration {
        @Bean Probe probe(SystemAuthorizationService system) { return new Probe(system); }
    }
    @RestController
    static class Probe {
        private final SystemAuthorizationService system;
        Probe(SystemAuthorizationService system) { this.system = system; }
        @RequestMapping(value = {"/admin/organizations", "/admin/organizations/probe"},
                method = {RequestMethod.GET, RequestMethod.POST})
        String boundary() { return "system boundary passed"; }
        @GetMapping("/api/v1/test/system-gate")
        String service(@AuthenticationPrincipal AppUserDetails principal) {
            system.requireSysadmin(principal);
            return "system service passed";
        }
    }

    @BeforeEach
    void seed() {
        cleaner.clean();
        a = organizations.save(new Organization("a", "A", null));
        b = organizations.save(new Organization("b", "B", null));
        user = users.save(new AppUser("operator@example.org", passwords.encode("normal-password")));
        client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
    }

    HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> post(String path, String body, boolean csrf) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json");
        if (csrf) request.header("X-XSRF-TOKEN", mapper.readTree(get("/api/v1/auth/csrf").body())
                .get("token").asText());
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
    void login() throws Exception {
        var response = post("/api/v1/auth/login",
                "{\"email\":\"operator@example.org\",\"password\":\"normal-password\"}", true);
        assertEquals(200, response.statusCode(), response.body());
    }
    boolean operation(UUID id, String action) throws Exception {
        try (var connection = dataSource.getConnection()) {
            return SystemRoleMaintenance.apply(connection, id, user.getId(), action, "operator-1", "issue91 test");
        }
    }
    void grant() throws Exception { assertTrue(operation(UUID.randomUUID(), "GRANT")); }
    void membership(Organization org, MembershipRole role, MembershipStatus status) {
        memberships.save(new OrganizationMembership(org, user, role, status));
    }
    List<String> dataPaths(Organization org) {
        String root = "/api/v1/organizations/" + org.getId();
        return List.of(root + "/cats", root + "/feeding-sites", root + "/visits",
                "/api/v1/nodes?organizationId=" + org.getId(),
                "/api/v1/observations?organizationId=" + org.getId());
    }
    void forbiddenData(Organization org) throws Exception {
        for (String path : dataPaths(org)) {
            var response = get(path);
            assertEquals(403, response.statusCode(), path + ": " + response.body());
        }
    }

    @Test
    void tenantRolesNeverReceiveSystemAuthority() throws Exception {
        membership(a, MembershipRole.ADMIN, MembershipStatus.ACTIVE);
        membership(b, MembershipRole.MEMBER, MembershipStatus.ACTIVE);
        login();
        assertFalse(system.isSysadmin(user.getId()));
        for (String path : List.of("/admin/organizations", "/admin/organizations/probe", "/api/v1/test/system-gate")) {
            assertEquals(403, get(path).statusCode(), path);
        }
        var admin = memberships.findByOrganizationIdAndUserId(a.getId(), user.getId()).orElseThrow();
        admin.setRole(MembershipRole.MEMBER);
        memberships.save(admin);
        assertEquals(403, get("/admin/organizations/probe").statusCode());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM app_user_system_roles", Integer.class));
    }

    @Test
    void explicitGrantPassesBothGatesInAnExistingNormalSession() throws Exception {
        login();
        assertEquals(403, get("/admin/organizations/probe").statusCode());
        grant();
        assertTrue(system.isSysadmin(user.getId()));
        assertEquals(200, get("/admin/organizations").statusCode());
        assertEquals(200, get("/admin/organizations/probe?organizationId=" + a.getId()).statusCode());
        assertEquals(200, get("/api/v1/test/system-gate").statusCode());
        assertEquals(403, get("/admin/cats").statusCode());
        forbiddenData(a);
        forbiddenData(b);
        assertEquals(0, mapper.readTree(get("/api/v1/organizations").body()).size());
    }

    @Test
    void sysadminKeepsOnlyMembershipPermissionsAndIsolatedContexts() throws Exception {
        membership(a, MembershipRole.MEMBER, MembershipStatus.ACTIVE);
        membership(b, MembershipRole.ADMIN, MembershipStatus.ACTIVE);
        var catA = cats.save(new Cat(a, "000123", "Only A", null, null));
        var catB = cats.save(new Cat(b, "000123", "Only B", null, null));
        grant();
        login();
        for (var org : List.of(a, b)) {
            for (String path : dataPaths(org)) assertEquals(200, get(path).statusCode(), path);
        }
        String rootA = "/api/v1/organizations/" + a.getId();
        String rootB = "/api/v1/organizations/" + b.getId();
        assertTrue(get(rootA + "/cats").body().contains("Only A"));
        assertFalse(get(rootA + "/cats").body().contains("Only B"));
        assertTrue(get(rootB + "/cats").body().contains("Only B"));
        assertFalse(get(rootB + "/cats").body().contains("Only A"));
        assertEquals(404, get(rootA + "/cats/" + catB.getId()).statusCode());
        assertEquals(404, get(rootB + "/cats/" + catA.getId()).statusCode());
        assertEquals(403, get(rootA + "/members").statusCode());
        assertEquals(200, get(rootB + "/members").statusCode());
        assertEquals(200, post(rootA + "/cats", "{\"chipId\":\"999\"}", true).statusCode());
        assertEquals(403, post(rootA + "/members",
                "{\"email\":\"new@example.org\",\"password\":\"normal-password\",\"role\":\"ADMIN\"}", true).statusCode());
        var active = memberships.findByOrganizationIdAndUserId(a.getId(), user.getId()).orElseThrow();
        active.disable();
        memberships.save(active);
        forbiddenData(a);
        assertEquals(200, get(rootB + "/cats").statusCode());
        assertEquals(200, get("/admin/organizations/probe").statusCode());
    }

    @Test
    void pendingAndDisabledMembershipsDoNotBecomeActiveThroughSysadmin() throws Exception {
        membership(a, MembershipRole.ADMIN, MembershipStatus.PENDING);
        membership(b, MembershipRole.ADMIN, MembershipStatus.DISABLED);
        grant();
        login();
        forbiddenData(a);
        forbiddenData(b);
    }

    @Test
    void revocationAndDisableApplyImmediatelyToExistingSessions() throws Exception {
        grant();
        login();
        assertTrue(operation(UUID.randomUUID(), "REVOKE"));
        assertEquals(403, get("/admin/organizations/probe").statusCode());
        assertEquals(403, get("/api/v1/test/system-gate").statusCode());
        grant();
        assertEquals(200, get("/admin/organizations/probe").statusCode());
        user.setStatus(UserStatus.DISABLED);
        users.save(user);
        assertFalse(system.isSysadmin(user.getId()));
        assertEquals(403, get("/admin/organizations/probe").statusCode());
        assertEquals(403, get("/api/v1/test/system-gate").statusCode());
        assertThrows(IllegalArgumentException.class, () -> operation(UUID.randomUUID(), "GRANT"));
        assertTrue(operation(UUID.randomUUID(), "REVOKE"));
        user.setStatus(UserStatus.ACTIVE);
        users.save(user);
        assertFalse(system.isSysadmin(user.getId()));
    }

    @Test
    void bootstrapReplayCannotRestoreRevokedGrantAndRecoveryRequiresFreshOperation() throws Exception {
        UUID initial = UUID.randomUUID();
        assertTrue(operation(initial, "GRANT"));
        assertFalse(operation(initial, "GRANT"));
        assertTrue(operation(UUID.randomUUID(), "REVOKE"));
        assertFalse(operation(initial, "GRANT"));
        assertFalse(system.isSysadmin(user.getId()));
        assertThrows(IllegalArgumentException.class, () -> operation(initial, "REVOKE"));
        grant();
        assertTrue(system.isSysadmin(user.getId()));
        assertFalse(operation(UUID.randomUUID(), "GRANT"));
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM app_user_system_role_audit", Integer.class));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM app_user_system_role_audit WHERE changed = TRUE", Integer.class));
    }

    @Test
    void invalidOperatorActionsFailClosedWithoutPartialWrites() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThrows(IllegalArgumentException.class, () -> SystemRoleMaintenance.apply(connection,
                    UUID.randomUUID(), UUID.randomUUID(), "GRANT", "operator", "reason"));
            assertThrows(IllegalArgumentException.class, () -> SystemRoleMaintenance.apply(connection,
                    UUID.randomUUID(), user.getId(), "GRANT", " ", "reason"));
            assertThrows(IllegalArgumentException.class, () -> SystemRoleMaintenance.apply(connection,
                    UUID.randomUUID(), user.getId(), "ORGAADMIN", "operator", "reason"));
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM app_user_system_role_audit", Integer.class));
        assertFalse(system.isSysadmin(user.getId()));
    }

    @Test
    void systemBoundaryRetainsCsrfAndNormalLogout() throws Exception {
        assertEquals(302, get("/admin/organizations/probe").statusCode());
        grant();
        login();
        assertEquals(403, post("/admin/organizations/probe", "{}", false).statusCode());
        assertEquals(200, post("/admin/organizations/probe", "{}", true).statusCode());
        assertEquals(200, post("/api/v1/auth/logout", "{}", true).statusCode());
        assertEquals(401, get("/api/v1/test/system-gate").statusCode());
        assertEquals(302, get("/admin/organizations/probe").statusCode());
    }

    @Test
    void globalPrivilegeNeverEntersOfflineCredentialsOrForeignNodeAuthorization() throws Exception {
        grant();
        login();
        var keys = org.miezmerker.backend.crypto.EcKeyUtils.generateP256();
        var publicKey = (java.security.interfaces.ECPublicKey) keys.getPublic();
        var registered = post("/api/v1/devices", mapper.writeValueAsString(java.util.Map.of(
                "publicKeyX", org.miezmerker.backend.crypto.EcKeyUtils.xOf(publicKey),
                "publicKeyY", org.miezmerker.backend.crypto.EcKeyUtils.yOf(publicKey))), true);
        assertEquals(200, registered.statusCode(), registered.body());
        String device = mapper.readTree(registered.body()).get("id").asText();
        String path = "/api/v1/devices/" + device + "/credentials";
        String requestA = mapper.writeValueAsString(java.util.Map.of("organizationId", a.getId()));
        String requestB = mapper.writeValueAsString(java.util.Map.of("organizationId", b.getId()));
        assertEquals(403, post(path, requestA, true).statusCode());
        membership(a, MembershipRole.MEMBER, MembershipStatus.ACTIVE);
        var issued = post(path, requestA, true);
        assertEquals(200, issued.statusCode(), issued.body());
        String credential = mapper.readTree(issued.body()).get("credential").asText();
        assertEquals("MEMBER", offline.verifyForOrganization(credential, a.getId()).role());
        byte[] challenge = offline.newChallenge();
        byte[] signature = org.miezmerker.backend.crypto.EcKeyUtils.signRaw(keys.getPrivate(), challenge);
        assertTrue(offline.authorizeSync(credential, a.getId(), challenge, signature));
        assertFalse(offline.authorizeSync(credential, b.getId(), challenge, signature));
        assertEquals(403, post(path, requestB, true).statusCode());
        var membership = memberships.findByOrganizationIdAndUserId(a.getId(), user.getId()).orElseThrow();
        membership.setRole(MembershipRole.ADMIN);
        memberships.save(membership);
        var adminIssued = post(path, requestA, true);
        assertEquals(200, adminIssued.statusCode(), adminIssued.body());
        assertEquals("ADMIN", offline.verifyForOrganization(
                mapper.readTree(adminIssued.body()).get("credential").asText(), a.getId()).role());
    }
}
