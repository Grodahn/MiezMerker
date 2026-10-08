package org.miezmerker.backend;

import jakarta.servlet.http.HttpServletRequest;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.miezmerker.backend.domain.AppUser;
import org.miezmerker.backend.domain.MembershipRole;
import org.miezmerker.backend.domain.MembershipStatus;
import org.miezmerker.backend.domain.Organization;
import org.miezmerker.backend.domain.OrganizationMembership;
import org.miezmerker.backend.domain.UserStatus;
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
import org.miezmerker.backend.support.TestDatabaseCleaner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Acceptance tests for #33: server-rendered ADMIN-only backoffice shell.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(AdminShellTest.SessionTestConfiguration.class)
class AdminShellTest {
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
    @Autowired TestDatabaseCleaner cleaner;

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
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    String base(String path) {
        return "http://localhost:" + port + path;
    }

    HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base(path))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    String extractCsrf(String html) {
        // Thymeleaf renders: <input type="hidden" name="_csrf" value="..."/>
        Pattern p = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"");
        Matcher m = p.matcher(html);
        if (m.find()) {
            return m.group(1);
        }
        // Fallback: _csrf parameter style.
        Pattern p2 = Pattern.compile("name=\"([^\"]*csrf[^\"]*)\"[^>]*value=\"([^\"]+)\"",
                Pattern.CASE_INSENSITIVE);
        Matcher m2 = p2.matcher(html);
        if (m2.find()) {
            return m2.group(2);
        }
        return null;
    }

    String loginPageCsrf() throws Exception {
        var res = get("/admin/login");
        assertEquals(200, res.statusCode(), res.body());
        String token = extractCsrf(res.body());
        assertNotNull(token, "login page must expose CSRF token: " + res.body().substring(0,
                Math.min(2000, res.body().length())));
        return token;
    }

    HttpResponse<String> formLogin(String email, String password) throws Exception {
        String csrf = loginPageCsrf();
        String body = "email=" + URLEncoder.encode(email, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8)
                + "&_csrf=" + URLEncoder.encode(csrf, StandardCharsets.UTF_8);
        return client.send(HttpRequest.newBuilder(URI.create(base("/admin/login")))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    void assertLoginSuccess(HttpResponse<String> res) {
        assertEquals(302, res.statusCode(), res.body());
        String loc = res.headers().firstValue("location").orElse("");
        assertTrue(loc.contains("/admin/"), "login should redirect to admin shell, got: " + loc);
    }

    String apiCsrfToken() throws Exception {
        var res = get("/api/v1/auth/csrf");
        assertEquals(200, res.statusCode(), res.body());
        return mapper.readTree(res.body()).get("token").asText();
    }

    HttpResponse<String> apiLogin(String email, String password) throws Exception {
        String token = apiCsrfToken();
        return client.send(HttpRequest.newBuilder(URI.create(base("/api/v1/auth/login")))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", token)
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    record Fixture(Organization orgA, Organization orgB, AppUser adminA, AppUser memberA,
            AppUser adminB, AppUser pendingA, AppUser disabledMemberA, AppUser disabledAccount,
            AppUser multiAdmin) {}

    Fixture seed() {
        cleaner.clean();

        Organization orgA = organizations.save(new Organization("org-a", "Org A", null));
        Organization orgB = organizations.save(new Organization("org-b", "Org B", null));

        AppUser adminA = users.save(new AppUser("admin-a@example.org",
                passwords.encode("supersecret-password-a"), "Ada Admin"));
        AppUser memberA = users.save(new AppUser("member-a@example.org",
                passwords.encode("supersecret-password-m")));
        AppUser adminB = users.save(new AppUser("admin-b@example.org",
                passwords.encode("supersecret-password-b")));
        AppUser pendingA = users.save(new AppUser("pending-a@example.org",
                passwords.encode("supersecret-password-p")));
        AppUser disabledMemberA = users.save(new AppUser("disabled-a@example.org",
                passwords.encode("supersecret-password-d")));
        AppUser disabledAccount = users.save(new AppUser("locked@example.org",
                passwords.encode("supersecret-password-l")));
        disabledAccount.setStatus(UserStatus.DISABLED);
        users.save(disabledAccount);
        AppUser multiAdmin = users.save(new AppUser("multi-admin@example.org",
                passwords.encode("supersecret-password-x"), "Multi Admin"));

        memberships.save(new OrganizationMembership(orgA, adminA, MembershipRole.ADMIN,
                MembershipStatus.ACTIVE));
        memberships.save(new OrganizationMembership(orgA, memberA, MembershipRole.MEMBER,
                MembershipStatus.ACTIVE));
        memberships.save(new OrganizationMembership(orgB, adminB, MembershipRole.ADMIN,
                MembershipStatus.ACTIVE));
        memberships.save(new OrganizationMembership(orgA, pendingA, MembershipRole.MEMBER,
                MembershipStatus.PENDING));
        OrganizationMembership dis = new OrganizationMembership(orgA, disabledMemberA,
                MembershipRole.ADMIN, MembershipStatus.ACTIVE);
        memberships.save(dis);
        dis.disable();
        memberships.save(dis);
        memberships.save(new OrganizationMembership(orgA, multiAdmin, MembershipRole.ADMIN,
                MembershipStatus.ACTIVE));
        memberships.save(new OrganizationMembership(orgB, multiAdmin, MembershipRole.ADMIN,
                MembershipStatus.ACTIVE));
        return new Fixture(orgA, orgB, adminA, memberA, adminB, pendingA, disabledMemberA,
                disabledAccount, multiAdmin);
    }

    @Test
    void apiRequestBeforeAdminLoginDoesNotChangeLandingPage() throws Exception {
        seed();
        assertEquals(401, get("/api/v1/organizations").statusCode());
        var login = formLogin("admin-a@example.org", "supersecret-password-a");
        assertTrue(login.headers().firstValue("location").orElse("").endsWith("/admin/"));
        assertEquals(200, get("/admin/").statusCode());
    }

    @Test
    void deniedMutationMethodsRenderForbiddenPage() throws Exception {
        seed();
        formLogin("admin-a@example.org", "supersecret-password-a");
        for (String method : new String[] {"PUT", "PATCH", "DELETE"}) {
            var denied = client.send(HttpRequest.newBuilder(URI.create(base("/admin/org")))
                    .method(method, HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(403, denied.statusCode(), method + ": " + denied.body());
            assertTrue(denied.body().contains("Kein Admin-Zugang"), method);
        }
        assertEquals(200, get("/admin/").statusCode());
    }

    @Test
    void loginPageIsPublicWithoutPwaShell() throws Exception {
        seed();
        var res = get("/admin/login");
        assertEquals(200, res.statusCode(), res.body());
        assertTrue(res.body().contains("Admin-Login"), res.body());
        assertTrue(res.body().contains("name=\"email\""), res.body());
        assertTrue(res.body().contains("name=\"password\""), res.body());
        // No PWA shell navigation leaking into the admin login.
        assertFalse(res.body().contains("/sync"), res.body());
    }

    @Test
    void unauthenticatedAdminRootRedirectsToLogin() throws Exception {
        seed();
        var res = get("/admin/");
        assertEquals(302, res.statusCode(), res.body());
        assertTrue(res.headers().firstValue("location").orElse("").contains("/admin/login"),
                res.headers().firstValue("location").orElse(""));
    }

    @Test
    void adminLoginSuccessAndDashboardListsAllAreas() throws Exception {
        seed();
        var login = formLogin("admin-a@example.org", "supersecret-password-a");
        assertLoginSuccess(login);
        var dash = get("/admin/");
        assertEquals(200, dash.statusCode(), dash.body());
        // All backoffice areas listed.
        assertTrue(dash.body().contains("Mitglieder"), dash.body());
        assertTrue(dash.body().contains("Futterstellen"), dash.body());
        assertTrue(dash.body().contains("Katzen"), dash.body());
        assertTrue(dash.body().contains("Rohbeobachtungen"), dash.body());
        assertTrue(dash.body().contains("Besuche"), dash.body());
        // Active org + user (displayName from #32) + logout visible.
        assertTrue(dash.body().contains("Org A"), dash.body());
        assertTrue(dash.body().contains("Ada Admin"), dash.body());
        assertTrue(dash.body().contains("/admin/logout"), dash.body());
        // Reusable navigation links.
        assertTrue(dash.body().contains("/admin/members"), dash.body());
        assertTrue(dash.body().contains("/admin/sites"), dash.body());
        assertTrue(dash.body().contains("/admin/cats"), dash.body());
        assertTrue(dash.body().contains("/admin/observations"), dash.body());
        assertTrue(dash.body().contains("/admin/visits"), dash.body());
        // Same users as PWA: API login works with identical credentials.
        http();
        var api = apiLogin("admin-a@example.org", "supersecret-password-a");
        assertEquals(200, api.statusCode(), api.body());
    }

    @Test
    void allBackofficeRoutesRenderServerHtmlWithoutThePwaShell() throws Exception {
        seed();
        assertLoginSuccess(formLogin("admin-a@example.org", "supersecret-password-a"));
        for (String route : new String[] {"/admin/", "/admin/members", "/admin/sites",
                "/admin/cats", "/admin/observations", "/admin/visits"}) {
            var response = get(route);
            assertEquals(200, response.statusCode(), route + ": " + response.body());
            assertTrue(response.headers().firstValue("content-type").orElse("").contains("text/html"), route);
            assertTrue(response.body().contains("/admin/logout"), route);
            assertFalse(response.body().contains("id=\"root\""), route);
            assertFalse(response.body().contains("/assets/index-"), route);
        }
    }

    @Test
    void memberOnlyLoginIsDenied() throws Exception {
        seed();
        var login = formLogin("member-a@example.org", "supersecret-password-m");
        // Form authentication succeeds (same password store), but admin gate denies.
        assertEquals(302, login.statusCode(), login.body());
        var dash = get("/admin/");
        assertEquals(403, dash.statusCode(), dash.body());
        assertTrue(dash.body().contains("Kein Admin-Zugang"), dash.body());
        // No tenant data leaks to MEMBER: neither org name may appear.
        assertFalse(dash.body().contains("Org A"), dash.body());
        assertFalse(dash.body().contains("Org B"), dash.body());
    }

    @Test
    void pendingAndDisabledMembershipsAreDenied() throws Exception {
        seed();
        var pendingLogin = formLogin("pending-a@example.org", "supersecret-password-p");
        assertEquals(302, pendingLogin.statusCode(), pendingLogin.body());
        assertEquals(403, get("/admin/").statusCode());

        http();
        var disabledLogin = formLogin("disabled-a@example.org", "supersecret-password-d");
        assertEquals(302, disabledLogin.statusCode(), disabledLogin.body());
        assertEquals(403, get("/admin/").statusCode());
    }

    @Test
    void disabledAccountCannotLoginToAdmin() throws Exception {
        seed();
        var login = formLogin("locked@example.org", "supersecret-password-l");
        // Spring Security rejects DISABLED accounts: back to login with error.
        assertEquals(302, login.statusCode(), login.body());
        assertTrue(login.headers().firstValue("location").orElse("").contains("error"),
                login.headers().firstValue("location").orElse(""));
        assertEquals(302, get("/admin/").statusCode());
    }

    @Test
    void multiOrgAdminCanSwitchOrganization() throws Exception {
        Fixture f = seed();
        var login = formLogin("multi-admin@example.org", "supersecret-password-x");
        assertLoginSuccess(login);
        // Multiple ADMIN orgs with no session context: dashboard defers to selection.
        var dash = get("/admin/");
        assertEquals(302, dash.statusCode(), dash.body());
        assertTrue(dash.headers().firstValue("location").orElse("").contains("/admin/org"),
                dash.headers().firstValue("location").orElse(""));
        var sel = get("/admin/org");
        assertEquals(200, sel.statusCode(), sel.body());
        assertTrue(sel.body().contains("Org A") && sel.body().contains("Org B"), sel.body());
        // Validated switch to A.
        String csrf = extractCsrf(get("/admin/org").body());
        assertNotNull(csrf);
        var switchA = client.send(HttpRequest.newBuilder(URI.create(base("/admin/org")))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("organizationId=" + f.orgA().getId()
                        + "&_csrf=" + URLEncoder.encode(csrf, StandardCharsets.UTF_8)))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(302, switchA.statusCode(), switchA.body());
        var dashA = get("/admin/");
        assertEquals(200, dashA.statusCode(), dashA.body());
        assertTrue(dashA.body().contains("Org A"), dashA.body());

        // Switch to B.
        String csrf2 = extractCsrf(get("/admin/org").body());
        var switchB = client.send(HttpRequest.newBuilder(URI.create(base("/admin/org")))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("organizationId=" + f.orgB().getId()
                        + "&_csrf=" + URLEncoder.encode(csrf2, StandardCharsets.UTF_8)))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(302, switchB.statusCode(), switchB.body());
        var dashB = get("/admin/");
        assertEquals(200, dashB.statusCode(), dashB.body());
        assertTrue(dashB.body().contains("Org B"), dashB.body());
    }

    @Test
    void foreignOrgSwitchIsRejectedWithoutLeak() throws Exception {
        Fixture f = seed();
        var login = formLogin("admin-a@example.org", "supersecret-password-a");
        assertLoginSuccess(login);
        var dashA = get("/admin/");
        assertEquals(200, dashA.statusCode());
        assertTrue(dashA.body().contains("Org A"), dashA.body());

        // Direct foreign org id must never bypass tenant gates.
        String csrf = extractCsrf(get("/admin/org").body());
        // /admin/org for single-org admins redirects; fetch CSRF from dashboard instead.
        if (csrf == null) {
            csrf = extractCsrf(dashA.body());
        }
        // Dashboard has logout CSRF; if still null, use API CSRF with header.
        HttpResponse<String> attempt;
        if (csrf != null) {
            attempt = client.send(HttpRequest.newBuilder(URI.create(base("/admin/org")))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("organizationId=" + f.orgB().getId()
                            + "&_csrf=" + URLEncoder.encode(csrf, StandardCharsets.UTF_8)))
                    .build(), HttpResponse.BodyHandlers.ofString());
        } else {
            String token = apiCsrfToken();
            attempt = client.send(HttpRequest.newBuilder(URI.create(base("/admin/org")))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("X-XSRF-TOKEN", token)
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "organizationId=" + f.orgB().getId()))
                    .build(), HttpResponse.BodyHandlers.ofString());
        }
        // TenantService.requireAdmin rejects the foreign org with 403.
        assertEquals(403, attempt.statusCode(),
                "foreign org switch must fail, got: " + attempt.statusCode() + " " + attempt.body());
        // Active context still A, never B.
        var stillA = get("/admin/");
        assertEquals(200, stillA.statusCode(), stillA.body());
        assertTrue(stillA.body().contains("Org A"), stillA.body());
        assertFalse(stillA.body().contains("Org B"), stillA.body());
    }

    @Test
    void logoutInvalidatesAdminSession() throws Exception {
        seed();
        var login = formLogin("admin-a@example.org", "supersecret-password-a");
        assertLoginSuccess(login);
        assertEquals(200, get("/admin/").statusCode());
        // Logout requires CSRF.
        String csrf = extractCsrf(get("/admin/").body());
        if (csrf == null) {
            csrf = apiCsrfToken();
        }
        var logout = client.send(HttpRequest.newBuilder(URI.create(base("/admin/logout")))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers
                        .ofString("_csrf=" + URLEncoder.encode(csrf, StandardCharsets.UTF_8)))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(302, logout.statusCode(), logout.body());
        assertTrue(logout.headers().firstValue("location").orElse("").contains("login"),
                logout.headers().firstValue("location").orElse(""));
        // Session is gone: admin root redirects to login again.
        assertEquals(302, get("/admin/").statusCode());
    }

    @Test
    void csrfProtectsAdminMutations() throws Exception {
        seed();
        var login = formLogin("admin-a@example.org", "supersecret-password-a");
        assertLoginSuccess(login);
        assertEquals(200, get("/admin/").statusCode());
        // POST without CSRF must be rejected and must not change state.
        var noCsrfLogout = client.send(HttpRequest.newBuilder(URI.create(base("/admin/logout")))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(""))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, noCsrfLogout.statusCode(), noCsrfLogout.body());
        // Session still valid after rejected mutation.
        assertEquals(200, get("/admin/").statusCode());

        var noCsrfOrg = client.send(HttpRequest.newBuilder(URI.create(base("/admin/org")))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "organizationId=" + UUID.randomUUID()))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, noCsrfOrg.statusCode(), noCsrfOrg.body());
    }

    @Test
    void sessionExpiryReturnsToLogin() throws Exception {
        seed();
        var login = formLogin("admin-a@example.org", "supersecret-password-a");
        assertLoginSuccess(login);
        assertEquals(200, get("/admin/").statusCode());
        var probe = get("/api/v1/test/expire-session");
        assertEquals(200, probe.statusCode(), probe.body());
        Thread.sleep(1200);
        var after = get("/admin/");
        // Expired admin session (stale JSESSIONID sent): dedicated expired state.
        assertEquals(302, after.statusCode(), after.body());
        assertTrue(after.headers().firstValue("location").orElse("").contains("/admin/login?expired"),
                after.headers().firstValue("location").orElse(""));
    }

    @Test
    void placeholderSectionsReuseLayoutAndRequireAdmin() throws Exception {
        seed();
        var login = formLogin("admin-a@example.org", "supersecret-password-a");
        assertLoginSuccess(login);
        for (String section : new String[] {"members", "sites", "cats", "observations", "visits"}) {
            var res = get("/admin/" + section);
            assertEquals(200, res.statusCode(), section + ": " + res.body());
            // Reusable navigation present on every stub.
            assertTrue(res.body().contains("/admin/members"), section);
            assertTrue(res.body().contains("Org A"), section);
            assertTrue(res.body().contains("/admin/logout"), section);
        }
        // MEMBER cannot reach stubs.
        http();
        formLogin("member-a@example.org", "supersecret-password-m");
        assertEquals(403, get("/admin/members").statusCode());
        assertEquals(403, get("/admin/sites").statusCode());
    }
}
