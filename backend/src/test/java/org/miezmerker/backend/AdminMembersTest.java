package org.miezmerker.backend;

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
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Acceptance tests for #34: server-rendered {@code /admin/members}.
 * Reuses the #33 shell (login, org context, layout) and #32 displayName semantics.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AdminMembersTest {
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
        Pattern p = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"");
        Matcher m = p.matcher(html);
        if (m.find()) {
            return m.group(1);
        }
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
        assertNotNull(token, "login page must expose CSRF token");
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
        assertTrue(res.headers().firstValue("location").orElse("").contains("/admin/"),
                res.headers().firstValue("location").orElse(""));
    }

    String pageCsrf(String path) throws Exception {
        var res = get(path);
        assertEquals(200, res.statusCode(), res.body());
        String token = extractCsrf(res.body());
        assertNotNull(token, path + " must expose CSRF token");
        return token;
    }

    void apiLogin(String email, String password) throws Exception {
        var csrf = get("/api/v1/auth/csrf");
        assertEquals(200, csrf.statusCode(), csrf.body());
        String token = mapper.readTree(csrf.body()).get("token").asText();
        var login = client.send(HttpRequest.newBuilder(URI.create(base("/api/v1/auth/login")))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", token)
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, login.statusCode(), login.body());
    }

    HttpResponse<String> postMemberForm(UUID membershipId, String formBodyWithoutCsrf,
            String csrf) throws Exception {
        String body = formBodyWithoutCsrf.isEmpty() ? "_csrf=" + URLEncoder.encode(csrf, StandardCharsets.UTF_8)
                : formBodyWithoutCsrf + "&_csrf=" + URLEncoder.encode(csrf, StandardCharsets.UTF_8);
        return client.send(HttpRequest.newBuilder(URI.create(base("/admin/members/" + membershipId)))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> postOrgSwitch(UUID orgId, String csrf) throws Exception {
        String body = "organizationId=" + orgId
                + "&_csrf=" + URLEncoder.encode(csrf, StandardCharsets.UTF_8);
        return client.send(HttpRequest.newBuilder(URI.create(base("/admin/org")))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    record Fixture(Organization orgA, Organization orgB,
            AppUser adminA, AppUser memberA, AppUser adminB, AppUser noNameA,
            AppUser pendingA, AppUser disabledA, AppUser multi,
            OrganizationMembership memberAMembership, OrganizationMembership memberBMembership) {}

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

        Organization orgA = organizations.save(new Organization("adm-a", "Org A", null));
        Organization orgB = organizations.save(new Organization("adm-b", "Org B", null));

        AppUser adminA = users.save(new AppUser("adm-admin-a@example.org",
                passwords.encode("supersecret-password-a"), "Anna Müller"));
        AppUser memberA = users.save(new AppUser("adm-member-a@example.org",
                passwords.encode("supersecret-password-m"), "Max Mustermann"));
        AppUser adminB = users.save(new AppUser("adm-admin-b@example.org",
                passwords.encode("supersecret-password-b"), "Bea Beispiel"));
        AppUser noNameA = users.save(new AppUser("adm-noname-a@example.org",
                passwords.encode("supersecret-password-n")));
        AppUser pendingA = users.save(new AppUser("adm-pending-a@example.org",
                passwords.encode("supersecret-password-p"), "Pending Paul"));
        AppUser disabledA = users.save(new AppUser("adm-disabled-a@example.org",
                passwords.encode("supersecret-password-d"), "Disabled Dora"));
        AppUser multi = users.save(new AppUser("adm-multi@example.org",
                passwords.encode("supersecret-password-x"), "Multi Mara"));

        memberships.save(new OrganizationMembership(orgA, adminA, MembershipRole.ADMIN, MembershipStatus.ACTIVE));
        OrganizationMembership mA = memberships.save(
                new OrganizationMembership(orgA, memberA, MembershipRole.MEMBER, MembershipStatus.ACTIVE));
        OrganizationMembership mB = memberships.save(
                new OrganizationMembership(orgB, adminB, MembershipRole.ADMIN, MembershipStatus.ACTIVE));
        memberships.save(new OrganizationMembership(orgA, noNameA, MembershipRole.MEMBER, MembershipStatus.ACTIVE));
        memberships.save(new OrganizationMembership(orgA, pendingA, MembershipRole.MEMBER, MembershipStatus.PENDING));
        OrganizationMembership dis = new OrganizationMembership(orgA, disabledA, MembershipRole.MEMBER, MembershipStatus.ACTIVE);
        memberships.save(dis);
        dis.disable();
        memberships.save(dis);
        memberships.save(new OrganizationMembership(orgA, multi, MembershipRole.ADMIN, MembershipStatus.ACTIVE));
        memberships.save(new OrganizationMembership(orgB, multi, MembershipRole.ADMIN, MembershipStatus.ACTIVE));
        return new Fixture(orgA, orgB, adminA, memberA, adminB, noNameA, pendingA, disabledA, multi, mA, mB);
    }

    @Test
    void activeAdminSeesMembersWithAllFieldsAndFallback() throws Exception {
        seed();
        assertLoginSuccess(formLogin("adm-admin-a@example.org", "supersecret-password-a"));
        var res = get("/admin/members");
        assertEquals(200, res.statusCode(), res.body());
        String html = res.body();
        assertTrue(html.contains("Anna Müller"), html);
        assertTrue(html.contains("Max Mustermann"), html);
        assertTrue(html.contains("adm-admin-a@example.org"), html);
        assertTrue(html.contains("adm-member-a@example.org"), html);
        assertTrue(html.contains("ADMIN"), html);
        assertTrue(html.contains("MEMBER"), html);
        assertTrue(html.contains("ACTIVE"), html);
        assertTrue(html.contains("adm-noname-a@example.org"), html);
        assertNull(users.findByEmail("adm-noname-a@example.org").orElseThrow().getDisplayName());
        assertTrue(html.contains("Org A"), html);
        assertTrue(html.contains("/admin/members"), html);
        assertTrue(html.contains("/admin/logout"), html);
        assertNotNull(extractCsrf(html), "members page must expose CSRF token");
    }

    @Test
    void adminASeesOnlyOwnOrgAndCannotTouchForeignMembership() throws Exception {
        Fixture f = seed();
        assertLoginSuccess(formLogin("adm-admin-a@example.org", "supersecret-password-a"));
        String html = get("/admin/members").body();
        assertTrue(html.contains("adm-member-a@example.org"), html);
        assertFalse(html.contains("adm-admin-b@example.org"), "must not list B members");

        UUID foreignId = f.memberBMembership().getId();
        String csrf = pageCsrf("/admin/members");
        var attempt = postMemberForm(foreignId, "role=MEMBER", csrf);
        assertEquals(404, attempt.statusCode(), attempt.body());
        assertFalse(attempt.body().contains("adm-admin-b@example.org"), "must not leak B details");
        assertEquals(MembershipRole.ADMIN, memberships.findById(foreignId).orElseThrow().getRole());

        String csrf2 = pageCsrf("/admin/members");
        var attempt2 = postMemberForm(foreignId,
                "displayName=" + URLEncoder.encode("Hacker", StandardCharsets.UTF_8), csrf2);
        assertEquals(404, attempt2.statusCode());
        assertEquals("Bea Beispiel",
                users.findByEmail("adm-admin-b@example.org").orElseThrow().getDisplayName());
    }

    @Test
    void multiOrgAdminSwitchesContextWithoutStaleData() throws Exception {
        Fixture f = seed();
        assertLoginSuccess(formLogin("adm-multi@example.org", "supersecret-password-x"));
        var initial = get("/admin/members");
        assertEquals(302, initial.statusCode(), initial.body());
        assertTrue(initial.headers().firstValue("location").orElse("").endsWith("/admin/org"));
        String csrfSel = pageCsrf("/admin/org");
        assertEquals(302, postOrgSwitch(f.orgA().getId(), csrfSel).statusCode());
        String htmlA = get("/admin/members").body();
        assertEquals(200, get("/admin/members").statusCode());
        assertTrue(htmlA.contains("adm-member-a@example.org"), htmlA);
        assertFalse(htmlA.contains("adm-admin-b@example.org"), htmlA);

        String csrfSel2 = pageCsrf("/admin/org");
        assertEquals(302, postOrgSwitch(f.orgB().getId(), csrfSel2).statusCode());
        String htmlB = get("/admin/members").body();
        assertTrue(htmlB.contains("adm-admin-b@example.org"), htmlB);
        assertTrue(htmlB.contains("Org B"), htmlB);
        assertFalse(htmlB.contains("adm-member-a@example.org"), "no stale A data after switch");
    }

    @Test
    void apiSessionCanOpenMembersWithoutVisitingDashboard() throws Exception {
        seed();
        apiLogin("adm-admin-a@example.org", "supersecret-password-a");
        var single = get("/admin/members");
        assertEquals(200, single.statusCode(), single.body());
        assertTrue(single.body().contains("adm-member-a@example.org"));
        assertFalse(single.body().contains("adm-admin-b@example.org"));

        http();
        apiLogin("adm-multi@example.org", "supersecret-password-x");
        var multi = get("/admin/members");
        assertEquals(302, multi.statusCode(), multi.body());
        assertTrue(multi.headers().firstValue("location").orElse("").endsWith("/admin/org"));
        assertFalse(multi.body().contains("adm-member-a@example.org"));
    }

    @Test
    void roleStatusFormPreservesTimestampsWhenStatusIsUnchanged() throws Exception {
        Fixture f = seed();
        assertLoginSuccess(formLogin("adm-admin-a@example.org", "supersecret-password-a"));
        UUID target = f.memberAMembership().getId();
        var before = memberships.findById(target).orElseThrow();
        var activatedAt = before.getActivatedAt();
        var active = postMemberForm(target, "role=ADMIN&status=ACTIVE", pageCsrf("/admin/members"));
        assertEquals(302, active.statusCode(), active.body());
        assertEquals(activatedAt, memberships.findById(target).orElseThrow().getActivatedAt());

        UUID disabledId = memberships.findByOrganizationIdAndUserId(
                f.orgA().getId(), f.disabledA().getId()).orElseThrow().getId();
        var disabledAt = memberships.findById(disabledId).orElseThrow().getDisabledAt();
        var disabled = postMemberForm(disabledId, "role=ADMIN&status=DISABLED", pageCsrf("/admin/members"));
        assertEquals(302, disabled.statusCode(), disabled.body());
        var after = memberships.findById(disabledId).orElseThrow();
        assertEquals(MembershipRole.ADMIN, after.getRole());
        assertEquals(MembershipStatus.DISABLED, after.getStatus());
        assertEquals(disabledAt, after.getDisabledAt());
    }

    @Test
    void unauthenticatedIsRedirectedToAdminLogin() throws Exception {
        seed();
        var res = get("/admin/members");
        assertEquals(302, res.statusCode(), res.body());
        assertTrue(res.headers().firstValue("location").orElse("").contains("/admin/login"));

        var post = client.send(HttpRequest.newBuilder(URI.create(base("/admin/members/" + UUID.randomUUID())))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("role=MEMBER")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(post.statusCode() == 302 || post.statusCode() == 403, "got " + post.statusCode());
    }

    @Test
    void memberOnlyPendingAndDisabledGetNoAdminAccess() throws Exception {
        seed();
        assertEquals(302, formLogin("adm-member-a@example.org", "supersecret-password-m").statusCode());
        assertEquals(403, get("/admin/members").statusCode());

        http();
        assertEquals(302, formLogin("adm-pending-a@example.org", "supersecret-password-p").statusCode());
        assertEquals(403, get("/admin/members").statusCode());

        http();
        assertEquals(302, formLogin("adm-disabled-a@example.org", "supersecret-password-d").statusCode());
        assertEquals(403, get("/admin/members").statusCode());
    }

    @Test
    void validRoleChangeSucceedsAndPersists() throws Exception {
        Fixture f = seed();
        assertLoginSuccess(formLogin("adm-admin-a@example.org", "supersecret-password-a"));
        assertEquals(200, get("/admin/members").statusCode());

        UUID target = f.memberAMembership().getId();
        String csrf = pageCsrf("/admin/members");
        var res = postMemberForm(target, "role=ADMIN", csrf);
        assertEquals(302, res.statusCode(), res.body());
        assertEquals(MembershipRole.ADMIN, memberships.findById(target).orElseThrow().getRole());

        String csrf2 = pageCsrf("/admin/members");
        var dis = postMemberForm(target, "status=DISABLED", csrf2);
        assertEquals(302, dis.statusCode());
        assertEquals(MembershipStatus.DISABLED, memberships.findById(target).orElseThrow().getStatus());

        String csrf3 = pageCsrf("/admin/members");
        var roleOnly = postMemberForm(target, "role=MEMBER", csrf3);
        assertEquals(302, roleOnly.statusCode());
        var after = memberships.findById(target).orElseThrow();
        assertEquals(MembershipRole.MEMBER, after.getRole());
        assertEquals(MembershipStatus.DISABLED, after.getStatus());
    }

    @Test
    void invalidRoleAndStatusAreRejectedWithoutMutation() throws Exception {
        Fixture f = seed();
        assertLoginSuccess(formLogin("adm-admin-a@example.org", "supersecret-password-a"));
        UUID target = f.memberAMembership().getId();

        String csrf = pageCsrf("/admin/members");
        var badRole = postMemberForm(target, "role=SUPERADMIN", csrf);
        assertEquals(302, badRole.statusCode(), badRole.body());
        assertTrue(badRole.headers().firstValue("location").orElse("").contains("error="));
        assertEquals(MembershipRole.MEMBER, memberships.findById(target).orElseThrow().getRole());

        String csrf2 = pageCsrf("/admin/members");
        var badStatus = postMemberForm(target, "status=GOLD", csrf2);
        assertEquals(302, badStatus.statusCode());
        assertEquals(MembershipStatus.ACTIVE, memberships.findById(target).orElseThrow().getStatus());
    }

    @Test
    void mutationsRequireCsrf() throws Exception {
        Fixture f = seed();
        assertLoginSuccess(formLogin("adm-admin-a@example.org", "supersecret-password-a"));
        UUID target = f.memberAMembership().getId();

        var noCsrf = client.send(HttpRequest.newBuilder(URI.create(base("/admin/members/" + target)))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("role=ADMIN")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(403, noCsrf.statusCode(), noCsrf.body());
        assertEquals(MembershipRole.MEMBER, memberships.findById(target).orElseThrow().getRole());

        var badToken = client.send(HttpRequest.newBuilder(URI.create(base("/admin/members/" + target)))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("role=ADMIN&_csrf=invalid-token-value")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(403, badToken.statusCode());
    }

    @Test
    void displayNameUnicodeTrimClearAndGlobalConsistency() throws Exception {
        Fixture f = seed();
        assertLoginSuccess(formLogin("adm-admin-a@example.org", "supersecret-password-a"));
        UUID target = f.memberAMembership().getId();

        String csrf = pageCsrf("/admin/members");
        String unicode = "Jörg Müller 🌟";
        var uni = postMemberForm(target,
                "displayName=" + URLEncoder.encode("  " + unicode + "  ", StandardCharsets.UTF_8), csrf);
        assertEquals(302, uni.statusCode(), uni.body());
        assertEquals(unicode, users.findByEmail("adm-member-a@example.org").orElseThrow().getDisplayName());
        assertTrue(get("/admin/members").body().contains(unicode));

        String csrf2 = pageCsrf("/admin/members");
        var tooLong = postMemberForm(target, "displayName=" + "a".repeat(256), csrf2);
        assertEquals(302, tooLong.statusCode());
        assertTrue(tooLong.headers().firstValue("location").orElse("").contains("error="));
        assertEquals(unicode, users.findByEmail("adm-member-a@example.org").orElseThrow().getDisplayName());

        String csrf3 = pageCsrf("/admin/members");
        var cleared = postMemberForm(target,
                "displayName=" + URLEncoder.encode("   ", StandardCharsets.UTF_8), csrf3);
        assertEquals(302, cleared.statusCode());
        assertNull(users.findByEmail("adm-member-a@example.org").orElseThrow().getDisplayName());

        UUID multiA = memberships
                .findByOrganizationIdAndUserId(f.orgA().getId(), f.multi().getId()).orElseThrow().getId();
        String csrf4 = pageCsrf("/admin/members");
        var renamed = postMemberForm(multiA,
                "displayName=" + URLEncoder.encode("Globale Gina Ünïcödé ✓", StandardCharsets.UTF_8), csrf4);
        assertEquals(302, renamed.statusCode());
        assertEquals("Globale Gina Ünïcödé ✓",
                users.findByEmail("adm-multi@example.org").orElseThrow().getDisplayName());
    }

    @Test
    void pendingIsStatusNotRoleAndActivationWorks() throws Exception {
        Fixture f = seed();
        assertLoginSuccess(formLogin("adm-admin-a@example.org", "supersecret-password-a"));
        UUID pendingId = memberships
                .findByOrganizationIdAndUserId(f.orgA().getId(), f.pendingA().getId()).orElseThrow().getId();
        String csrf = pageCsrf("/admin/members");
        var act = postMemberForm(pendingId, "status=ACTIVE", csrf);
        assertEquals(302, act.statusCode());
        var activated = memberships.findById(pendingId).orElseThrow();
        assertEquals(MembershipStatus.ACTIVE, activated.getStatus());
        assertEquals(MembershipRole.MEMBER, activated.getRole());
    }
}
