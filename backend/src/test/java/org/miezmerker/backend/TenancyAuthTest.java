package org.miezmerker.backend;

import org.junit.jupiter.api.Tag;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.miezmerker.backend.domain.AppUser;
import org.miezmerker.backend.domain.MembershipRole;
import org.miezmerker.backend.domain.MembershipStatus;
import org.miezmerker.backend.domain.Organization;
import org.miezmerker.backend.domain.OrganizationMembership;
import org.miezmerker.backend.domain.OrganizationStatus;
import org.miezmerker.backend.domain.UserStatus;
import org.miezmerker.backend.repo.AppUserRepository;
import org.miezmerker.backend.repo.AppDeviceRepository;
import org.miezmerker.backend.repo.CatRepository;
import org.miezmerker.backend.repo.DerivedVisitRepository;
import org.miezmerker.backend.repo.FeedingSiteRepository;
import org.miezmerker.backend.repo.NodeDeploymentRepository;
import org.miezmerker.backend.repo.NodeRepository;
import org.miezmerker.backend.repo.MembershipRepository;
import org.miezmerker.backend.repo.OrganizationRepository;
import org.miezmerker.backend.repo.RawObservationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Acceptance tests for #16: organizations, users, memberships and backend login.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TenancyAuthTest.SessionTestConfiguration.class)
@Tag("auth")
class TenancyAuthTest {
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

    final ObjectMapper mapper = new ObjectMapper();
    HttpClient client;
    CookieManager cookies;

    @TestConfiguration
    static class SessionTestConfiguration {
        @Bean ExpiryProbe expiryProbe() { return new ExpiryProbe(); }
    }

    @RestController
    static class ExpiryProbe {
        @GetMapping("/api/v1/test/expire-session")
        int expire(HttpServletRequest request) {
            var session = request.getSession(false);
            int configuredTimeout = session.getMaxInactiveInterval();
            session.setMaxInactiveInterval(1);
            return configuredTimeout;
        }
    }

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

    HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base(path))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    record Fixture(Organization orgA, Organization orgB, AppUser adminA, AppUser memberA,
            AppUser adminB, AppUser pendingA, AppUser disabledA, AppUser multi) {}

    Fixture seed() {
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

        Organization orgA = organizations.save(new Organization("org-a", "Org A", "contact-a@example.org"));
        Organization orgB = organizations.save(new Organization("org-b", "Org B", null));

        AppUser adminA = users.save(new AppUser("admin-a@example.org", passwords.encode("supersecret-password-a")));
        AppUser memberA = users.save(new AppUser("member-a@example.org", passwords.encode("supersecret-password-m")));
        AppUser adminB = users.save(new AppUser("admin-b@example.org", passwords.encode("supersecret-password-b")));
        AppUser pendingA = users.save(new AppUser("pending-a@example.org", passwords.encode("supersecret-password-p")));
        AppUser disabledA = users.save(new AppUser("disabled-a@example.org", passwords.encode("supersecret-password-d")));
        AppUser multi = users.save(new AppUser("multi@example.org", passwords.encode("supersecret-password-x")));

        memberships.save(new OrganizationMembership(orgA, adminA, MembershipRole.ADMIN, MembershipStatus.ACTIVE));
        memberships.save(new OrganizationMembership(orgA, memberA, MembershipRole.MEMBER, MembershipStatus.ACTIVE));
        memberships.save(new OrganizationMembership(orgB, adminB, MembershipRole.ADMIN, MembershipStatus.ACTIVE));
        memberships.save(new OrganizationMembership(orgA, pendingA, MembershipRole.MEMBER, MembershipStatus.PENDING));
        OrganizationMembership dis = new OrganizationMembership(orgA, disabledA, MembershipRole.MEMBER, MembershipStatus.ACTIVE);
        memberships.save(dis);
        dis.disable();
        memberships.save(dis);
        memberships.save(new OrganizationMembership(orgA, multi, MembershipRole.MEMBER, MembershipStatus.ACTIVE));
        memberships.save(new OrganizationMembership(orgB, multi, MembershipRole.ADMIN, MembershipStatus.ACTIVE));
        return new Fixture(orgA, orgB, adminA, memberA, adminB, pendingA, disabledA, multi);
    }

    void login(String email, String password, int expected) throws Exception {
        var res = post("/api/v1/auth/login",
                "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
        assertEquals(expected, res.statusCode(), res.body());
    }

    @Test
    void csrfProtectsStateChangingRequests() throws Exception {
        seed();
        // Establish a session first.
        login("admin-a@example.org", "supersecret-password-a", 200);
        // Authenticated POST without CSRF header must be rejected (never processed).
        var noCsrf = client.send(java.net.http.HttpRequest.newBuilder(
                        URI.create(base("/api/v1/auth/logout")))
                        .header("Content-Type", "application/json")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(403, noCsrf.statusCode(), noCsrf.body());
        // Session is still valid (the rejected logout did not invalidate it).
        assertEquals(200, get("/api/v1/auth/session").statusCode());
    }

    @Test
    void overlongLoginPasswordsReturnGenericUnauthorizedWithoutChangingTheSession() throws Exception {
        seed();
        for (String password : new String[] { "a".repeat(73), "ä".repeat(37) }) {
            var known = post("/api/v1/auth/login", mapper.writeValueAsString(
                    java.util.Map.of("email", "admin-a@example.org", "password", password)));
            var unknown = post("/api/v1/auth/login", mapper.writeValueAsString(
                    java.util.Map.of("email", "unknown@example.org", "password", password)));
            assertEquals(401, known.statusCode(), known.body());
            assertEquals(401, unknown.statusCode(), unknown.body());
            assertEquals(401, get("/api/v1/auth/session").statusCode());
        }
    }

    @Test
    void loginLogoutSessionAndPasswordHashing() throws Exception {
        Fixture f = seed();

        // Passwords are hashed with BCrypt, never plaintext.
        AppUser stored = users.findByEmail("admin-a@example.org").orElseThrow();
        assertTrue(stored.getPasswordHash().startsWith("$2a$")
                || stored.getPasswordHash().startsWith("$2b$"));
        assertNotEquals("supersecret-password-a", stored.getPasswordHash());
        assertTrue(passwords.matches("supersecret-password-a", stored.getPasswordHash()));

        // Wrong password -> generic 401, no enumeration.
        login("admin-a@example.org", "wrong-password-xyz", 401);
        // Unknown email -> same generic 401.
        login("unknown@example.org", "supersecret-password-a", 401);

        // Successful login establishes a session.
        login("admin-a@example.org", "supersecret-password-a", 200);
        var session = get("/api/v1/auth/session");
        assertEquals(200, session.statusCode(), session.body());
        JsonNode body = mapper.readTree(session.body());
        assertEquals("admin-a@example.org", body.get("email").asText());

        // Logout invalidates the session.
        var logout = post("/api/v1/auth/logout", "{}");
        assertEquals(200, logout.statusCode(), logout.body());
        var after = get("/api/v1/auth/session");
        assertEquals(401, after.statusCode());

        // Email normalization: uppercase login works.
        login("ADMIN-A@EXAMPLE.ORG", "supersecret-password-a", 200);
    }

    @Test
    void onlyActiveMembershipsGrantAccess() throws Exception {
        Fixture f = seed();
        UUID orgA = f.orgA().getId();

        // The member roster is an ADMIN endpoint.
        login("member-a@example.org", "supersecret-password-m", 200);
        var list = get("/api/v1/organizations/" + orgA + "/members");
        assertEquals(403, list.statusCode(), list.body());
        // MEMBER can read own org.
        var org = get("/api/v1/organizations/" + orgA);
        assertEquals(200, org.statusCode(), org.body());

        // PENDING gets no org access.
        login("pending-a@example.org", "supersecret-password-p", 200);
        assertEquals(403, get("/api/v1/organizations/" + orgA).statusCode());
        assertEquals(403, get("/api/v1/organizations/" + orgA + "/members").statusCode());

        // DISABLED membership gets no org access.
        login("disabled-a@example.org", "supersecret-password-d", 200);
        assertEquals(403, get("/api/v1/organizations/" + orgA).statusCode());

        // Unauthenticated gets 401, not tenant data.
        cookies = new CookieManager();
        client = HttpClient.newBuilder().cookieHandler(cookies).build();
        assertEquals(401, get("/api/v1/organizations/" + orgA).statusCode());
    }

    @Test
    void adminVsMemberAuthorization() throws Exception {
        Fixture f = seed();
        UUID orgA = f.orgA().getId();

        // MEMBER cannot create members.
        login("member-a@example.org", "supersecret-password-m", 200);
        var denied = post("/api/v1/organizations/" + orgA + "/members",
                "{\"email\":\"new@example.org\",\"password\":\"supersecret-new-1\",\"role\":\"MEMBER\"}");
        assertEquals(403, denied.statusCode(), denied.body());

        // ADMIN can create + disable + re-enable.
        login("admin-a@example.org", "supersecret-password-a", 200);
        var created = post("/api/v1/organizations/" + orgA + "/members",
                "{\"email\":\"new@example.org\",\"password\":\"supersecret-new-1\",\"role\":\"MEMBER\"}");
        assertEquals(200, created.statusCode(), created.body());
        UUID membershipId = UUID.fromString(mapper.readTree(created.body()).get("membershipId").asText());

        String token = csrfToken();
        var disabled = client.send(HttpRequest.newBuilder(
                        URI.create(base("/api/v1/organizations/" + orgA + "/members/" + membershipId)))
                        .header("Content-Type", "application/json")
                        .header("X-XSRF-TOKEN", token)
                        .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"status\":\"DISABLED\"}"))
                        .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, disabled.statusCode(), disabled.body());
        assertEquals("DISABLED", mapper.readTree(disabled.body()).get("status").asText());

        // New user exists with hashed password and can no longer access the org.
        assertTrue(users.findByEmail("new@example.org").isPresent());
        login("new@example.org", "supersecret-new-1", 200);
        assertEquals(403, get("/api/v1/organizations/" + orgA).statusCode());
    }

    @Test
    void tenantIsolationAversusB() throws Exception {
        Fixture f = seed();
        UUID orgA = f.orgA().getId();
        UUID orgB = f.orgB().getId();

        login("admin-a@example.org", "supersecret-password-a", 200);
        // A cannot read B's org or roster.
        assertEquals(403, get("/api/v1/organizations/" + orgB).statusCode());
        assertEquals(403, get("/api/v1/organizations/" + orgB + "/members").statusCode());
        // A cannot mutate B's memberships (path confusion yields 404, never cross-tenant write).
        var otherMembership = memberships.findByOrganizationId(orgB).get(0).getId();
        String token = csrfToken();
        var attempt = client.send(HttpRequest.newBuilder(
                        URI.create(base("/api/v1/organizations/" + orgA + "/members/" + otherMembership)))
                        .header("Content-Type", "application/json")
                        .header("X-XSRF-TOKEN", token)
                        .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"role\":\"MEMBER\"}"))
                        .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(404, attempt.statusCode(), attempt.body());

        // A can list own orgs; multi-org user sees both.
        login("multi@example.org", "supersecret-password-x", 200);
        var session = mapper.readTree(get("/api/v1/auth/session").body());
        assertEquals(2, session.get("memberships").size());
        assertEquals(200, get("/api/v1/organizations/" + orgA).statusCode());
        assertEquals(200, get("/api/v1/organizations/" + orgB).statusCode());
    }

    @Test
    void selfSignupIsDisabled() throws Exception {
        seed();
        // No public registration endpoint exists.
        assertEquals(401, get("/api/v1/organizations").statusCode());
        var res = post("/api/v1/organizations/nope/members",
                "{\"email\":\"x@example.org\",\"password\":\"supersecret-new-1\",\"role\":\"MEMBER\"}");
        assertTrue(res.statusCode() == 401 || res.statusCode() == 403 || res.statusCode() == 404,
                "unexpected: " + res.statusCode());
    }

    @Test
    void pendingIsStatusNotRole() {
        Fixture f = seed();
        var m = memberships.findByOrganizationIdAndUserId(f.orgA().getId(), f.pendingA().getId());
        assertTrue(m.isPresent());
        assertEquals(MembershipStatus.PENDING, m.get().getStatus());
        // Role is still ADMIN|MEMBER, never PENDING.
        assertTrue(m.get().getRole() == MembershipRole.ADMIN || m.get().getRole() == MembershipRole.MEMBER);
    }

    @Test
    void organizationEndpointsRejectInactiveMembershipsAndDisabledOrganizations() throws Exception {
        Fixture f = seed();
        login("pending-a@example.org", "supersecret-password-p", 200);
        assertEquals(0, mapper.readTree(get("/api/v1/organizations").body()).size());
        login("disabled-a@example.org", "supersecret-password-d", 200);
        assertEquals(0, mapper.readTree(get("/api/v1/organizations").body()).size());
        login("admin-a@example.org", "supersecret-password-a", 200);
        f.orgA().setStatus(OrganizationStatus.DISABLED);
        organizations.save(f.orgA());
        assertEquals(403, get("/api/v1/organizations/" + f.orgA().getId()).statusCode());
        assertEquals(403, get("/api/v1/organizations/" + f.orgA().getId() + "/members").statusCode());
        assertEquals(0, mapper.readTree(get("/api/v1/organizations").body()).size());
        assertEquals(0, mapper.readTree(get("/api/v1/auth/session").body()).get("memberships").size());
    }

    @Test
    void disabledAccountCannotReuseItsSessionOrLogin() throws Exception {
        Fixture f = seed();
        login("admin-a@example.org", "supersecret-password-a", 200);
        f.adminA().setStatus(UserStatus.DISABLED);
        users.save(f.adminA());
        assertEquals(401, get("/api/v1/auth/session").statusCode());
        assertEquals(403, get("/api/v1/organizations").statusCode());
        assertEquals(403, get("/api/v1/organizations/" + f.orgA().getId()).statusCode());
        assertEquals(403, post("/api/v1/organizations",
                "{\"slug\":\"bypass\",\"displayName\":\"Bypass\"}").statusCode());
        assertEquals(2, organizations.count());
        login("admin-a@example.org", "supersecret-password-a", 401);
    }

    @Test
    void sessionExpiresAndUsesTheConfiguredTwelveHourTimeout() throws Exception {
        seed();
        login("admin-a@example.org", "supersecret-password-a", 200);
        var probe = get("/api/v1/test/expire-session");
        assertEquals(200, probe.statusCode(), probe.body());
        assertEquals(12 * 60 * 60, Integer.parseInt(probe.body()));
        Thread.sleep(1200);
        assertEquals(401, get("/api/v1/auth/session").statusCode());
        assertEquals(401, get("/api/v1/organizations").statusCode());
    }

    @Test
    void loginRotatesSessionIdAndCsrfAndLogoutClearsCsrf() throws Exception {
        seed();
        login("admin-a@example.org", "supersecret-password-a", 200);
        String beforeId = cookies.getCookieStore().getCookies().stream()
                .filter(c -> c.getName().equals("JSESSIONID")).findFirst().orElseThrow().getValue();
        String beforeCsrf = csrfToken();
        login("member-a@example.org", "supersecret-password-m", 200);
        String afterId = cookies.getCookieStore().getCookies().stream()
                .filter(c -> c.getName().equals("JSESSIONID")).findFirst().orElseThrow().getValue();
        assertNotEquals(beforeId, afterId);
        assertNotEquals(beforeCsrf, csrfToken());
        assertEquals("member-a@example.org", mapper.readTree(get("/api/v1/auth/session").body()).get("email").asText());
        var stale = client.send(HttpRequest.newBuilder(URI.create(base("/api/v1/auth/logout")))
                .header("X-XSRF-TOKEN", beforeCsrf).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(403, stale.statusCode());
        var signedOut = post("/api/v1/auth/logout", "{}");
        assertEquals(200, signedOut.statusCode());
        assertTrue(signedOut.headers().allValues("set-cookie").stream()
                .anyMatch(cookie -> cookie.startsWith("XSRF-TOKEN=;")
                        && java.net.HttpCookie.parse(cookie).get(0).hasExpired()),
                signedOut.headers().allValues("set-cookie").toString());
        assertEquals(401, get("/api/v1/auth/session").statusCode());
    }

    @Test
    void membershipRevocationAndRoleChangesApplyToExistingSessions() throws Exception {
        Fixture f = seed();
        login("admin-a@example.org", "supersecret-password-a", 200);
        var membership = memberships.findByOrganizationIdAndUserId(f.orgA().getId(), f.adminA().getId()).orElseThrow();
        membership.setRole(MembershipRole.MEMBER);
        memberships.save(membership);
        assertEquals(403, get("/api/v1/organizations/" + f.orgA().getId() + "/members").statusCode());
        assertEquals(200, get("/api/v1/organizations/" + f.orgA().getId()).statusCode());
        membership.disable();
        memberships.save(membership);
        assertEquals(403, get("/api/v1/organizations/" + f.orgA().getId()).statusCode());
        assertEquals(0, mapper.readTree(get("/api/v1/organizations").body()).size());
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void invalidBcryptPasswordsAreRejectedWithoutWritingOrLoggingSecrets(CapturedOutput output) throws Exception {
        Fixture f = seed();
        login("admin-a@example.org", "supersecret-password-a", 200);
        for (String password : new String[] { "a".repeat(73), "ä".repeat(37), "weak-secret" }) {
            var response = post("/api/v1/organizations/" + f.orgA().getId() + "/members",
                    mapper.writeValueAsString(java.util.Map.of("email", "too-long@example.org", "password", password, "role", "MEMBER")));
            assertEquals(400, response.statusCode(), response.body());
            assertTrue(users.findByEmail("too-long@example.org").isEmpty());
            assertFalse(output.getAll().contains(password), "rejected password must not be logged");
        }
    }
}
