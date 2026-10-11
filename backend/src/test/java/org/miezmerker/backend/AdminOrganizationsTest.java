package org.miezmerker.backend;

import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.miezmerker.backend.bootstrap.SystemRoleMaintenance;
import org.miezmerker.backend.domain.AppUser;
import org.miezmerker.backend.domain.Cat;
import org.miezmerker.backend.domain.MembershipRole;
import org.miezmerker.backend.domain.MembershipStatus;
import org.miezmerker.backend.domain.NodeDevice;
import org.miezmerker.backend.domain.Organization;
import org.miezmerker.backend.domain.OrganizationMembership;
import org.miezmerker.backend.domain.OrganizationStatus;
import org.miezmerker.backend.domain.ShareAudience;
import org.miezmerker.backend.domain.ShareScope;
import org.miezmerker.backend.domain.SysadminOrgAction;
import org.miezmerker.backend.domain.UserStatus;
import org.miezmerker.backend.repo.AppUserRepository;
import org.miezmerker.backend.repo.CatRepository;
import org.miezmerker.backend.repo.MembershipRepository;
import org.miezmerker.backend.repo.NodeRepository;
import org.miezmerker.backend.repo.OrganizationRepository;
import org.miezmerker.backend.repo.SharePolicyRepository;
import org.miezmerker.backend.repo.SysadminOrganizationAuditRepository;
import org.miezmerker.backend.service.SharePolicyService;
import org.miezmerker.backend.support.TestDatabaseCleaner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Acceptance tests for #92: SYSADMIN-only organization management at
 * {@code /admin/organizations}. Covers creation/safe defaults, rename
 * stability, deactivate/reactivate without grant restoration, safe deletion
 * retention, ADMIN assignment/revocation, negative security (non-SYSADMIN,
 * ID guessing, CSRF, tenant-data isolation) and audit correctness.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Tag("admin")
@Tag("auth")
class AdminOrganizationsTest {
    @Value("${local.server.port}") int port;

    @Autowired AppUserRepository users;
    @Autowired OrganizationRepository organizations;
    @Autowired MembershipRepository memberships;
    @Autowired CatRepository cats;
    @Autowired NodeRepository nodes;
    @Autowired SharePolicyRepository sharePolicies;
    @Autowired SharePolicyService sharePolicyService;
    @Autowired SysadminOrganizationAuditRepository sysadminAudit;
    @Autowired PasswordEncoder passwords;
    @Autowired TestDatabaseCleaner cleaner;
    @Autowired DataSource dataSource;

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
        String url = path.startsWith("http://") || path.startsWith("https://")
                ? path : base(path);
        return client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    String extractCsrf(String html) {
        Matcher m = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(html);
        if (m.find()) {
            return m.group(1);
        }
        Matcher m2 = Pattern.compile("name=\"([^\"]*csrf[^\"]*)\"[^>]*value=\"([^\"]+)\"",
                Pattern.CASE_INSENSITIVE).matcher(html);
        return m2.find() ? m2.group(2) : null;
    }

    String loginPageCsrf() throws Exception {
        var res = get("/admin/login");
        assertEquals(200, res.statusCode(), res.body());
        String token = extractCsrf(res.body());
        assertNotNull(token, "login page must expose CSRF token");
        return token;
    }

    void formLogin(String email, String password) throws Exception {
        String body = "email=" + enc(email) + "&password=" + enc(password)
                + "&_csrf=" + enc(loginPageCsrf());
        var res = client.send(HttpRequest.newBuilder(URI.create(base("/admin/login")))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(302, res.statusCode(), res.body());
    }

    String pageCsrf(String path) throws Exception {
        var res = get(path);
        assertEquals(200, res.statusCode(), res.body());
        String token = extractCsrf(res.body());
        assertNotNull(token, path + " must expose CSRF token");
        return token;
    }

    HttpResponse<String> postForm(String path, String formBodyWithoutCsrf, String csrf)
            throws Exception {
        String body = formBodyWithoutCsrf.isEmpty() ? "_csrf=" + enc(csrf)
                : formBodyWithoutCsrf + "&_csrf=" + enc(csrf);
        return client.send(HttpRequest.newBuilder(URI.create(base(path)))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    void grantSysadmin(AppUser user) throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertTrue(SystemRoleMaintenance.apply(connection, UUID.randomUUID(), user.getId(),
                    "GRANT", "test-operator", "AdminOrganizationsTest fixture"));
        }
    }

    record Fixture(Organization orgA, Organization orgB, AppUser sysadmin, AppUser adminA,
            AppUser memberA, AppUser outsider, AppUser multi, AppUser disabled) {}

    Fixture seed() {
        cleaner.clean();
        Organization orgA = organizations.save(new Organization("org-a", "Org A", null));
        Organization orgB = organizations.save(new Organization("org-b", "Org B", null));
        AppUser sysadmin = users.save(new AppUser("sysop@example.org",
                passwords.encode("supersecret-sysop")));
        AppUser adminA = users.save(new AppUser("orga-a@example.org",
                passwords.encode("supersecret-a")));
        AppUser memberA = users.save(new AppUser("member-a@example.org",
                passwords.encode("supersecret-m")));
        AppUser outsider = users.save(new AppUser("outsider@example.org",
                passwords.encode("supersecret-o")));
        AppUser multi = users.save(new AppUser("multi@example.org",
                passwords.encode("supersecret-x")));
        AppUser disabled = users.save(new AppUser("locked92@example.org",
                passwords.encode("supersecret-l")));
        disabled.setStatus(UserStatus.DISABLED);
        users.save(disabled);
        memberships.save(new OrganizationMembership(orgA, adminA, MembershipRole.ADMIN,
                MembershipStatus.ACTIVE));
        memberships.save(new OrganizationMembership(orgA, memberA, MembershipRole.MEMBER,
                MembershipStatus.ACTIVE));
        memberships.save(new OrganizationMembership(orgA, multi, MembershipRole.MEMBER,
                MembershipStatus.ACTIVE));
        memberships.save(new OrganizationMembership(orgB, multi, MembershipRole.ADMIN,
                MembershipStatus.ACTIVE));
        return new Fixture(orgA, orgB, sysadmin, adminA, memberA, outsider, multi, disabled);
    }

    UUID createOrg(String slug, String name) throws Exception {
        String csrf = pageCsrf("/admin/organizations");
        var res = postForm("/admin/organizations",
                "slug=" + enc(slug) + "&displayName=" + enc(name), csrf);
        assertEquals(302, res.statusCode(), res.body());
        String location = res.headers().firstValue("location").orElse("");
        String marker = "/admin/organizations/";
        assertTrue(location.contains(marker), location);
        return UUID.fromString(location.substring(location.indexOf(marker) + marker.length()));
    }

    String followRedirect(HttpResponse<String> redirect) throws Exception {
        String location = redirect.headers().firstValue("location").orElse("");
        assertFalse(location.isBlank(), "expected redirect location");
        var page = get(location);
        assertEquals(200, page.statusCode(), page.body());
        return page.body();
    }

    @Test
    void createOrganizationUsesSafeDefaults() throws Exception {
        Fixture f = seed();
        grantSysadmin(f.sysadmin());
        formLogin("sysop@example.org", "supersecret-sysop");

        var list = get("/admin/organizations");
        assertEquals(200, list.statusCode(), list.body());
        assertTrue(list.body().contains("Systemverwaltung"), list.body());
        assertTrue(list.body().contains("Org A"), list.body());

        UUID id = createOrg("neu-org", "Neue Org");
        Organization created = organizations.findById(id).orElseThrow();
        assertEquals("neu-org", created.getSlug());
        assertEquals("Neue Org", created.getDisplayName());
        assertEquals(OrganizationStatus.ACTIVE, created.getStatus());
        assertFalse(created.isDiscoverable(), "new organizations are hidden by default");
        assertTrue(sharePolicies.findByOrganizationId(id).isEmpty(), "no sharing grants");

        var audits = sysadminAudit.findByOrganizationIdOrderByCreatedAtDesc(id);
        assertEquals(1, audits.size());
        assertEquals(SysadminOrgAction.CREATE, audits.get(0).getAction());
        assertEquals(f.sysadmin().getId(), audits.get(0).getActor().getId());
        assertNotNull(audits.get(0).getCreatedAt());

        var detail = get("/admin/organizations/" + id);
        assertEquals(200, detail.statusCode(), detail.body());
        assertTrue(detail.body().contains("Keine Freigaben"), detail.body());
        assertTrue(detail.body().contains("Versteckt"), detail.body());
    }

    @Test
    void renameKeepsStableTechnicalIdentityAndNodeOwnership() throws Exception {
        Fixture f = seed();
        grantSysadmin(f.sysadmin());
        NodeDevice node = new NodeDevice(UUID.randomUUID(), "x-" + UUID.randomUUID(),
                "y-" + UUID.randomUUID(), "fp-" + UUID.randomUUID(), "1.0");
        node.claim(f.orgA());
        nodes.save(node);
        Cat cat = cats.save(new Cat(f.orgA(), " chip-001 ", "Miez", null, null));
        formLogin("sysop@example.org", "supersecret-sysop");

        String csrf = pageCsrf("/admin/organizations/" + f.orgA().getId());
        var res = postForm("/admin/organizations/" + f.orgA().getId() + "/rename",
                "displayName=" + enc("Org A Neu") + "&publicContact=" + enc("kontakt@beispiel.org"),
                csrf);
        assertEquals(302, res.statusCode(), res.body());

        Organization renamed = organizations.findById(f.orgA().getId()).orElseThrow();
        assertEquals(f.orgA().getId(), renamed.getId(), "technical id is stable");
        assertEquals("org-a", renamed.getSlug(), "slug is immutable");
        assertEquals("Org A Neu", renamed.getDisplayName());
        assertEquals("kontakt@beispiel.org", renamed.getPublicContact());
        assertEquals(f.orgA().getId(),
                nodes.findById(node.getNodeId()).orElseThrow().getOrganization().getId());
        assertEquals(f.orgA().getId(),
                cats.findById(cat.getId()).orElseThrow().getOrganization().getId());

        var audits = sysadminAudit.findByOrganizationIdOrderByCreatedAtDesc(f.orgA().getId());
        assertTrue(audits.stream().anyMatch(a -> a.getAction() == SysadminOrgAction.RENAME
                && a.getBeforeState().contains("Org A")
                && a.getAfterState().contains("Org A Neu")));
    }

    @Test
    void deactivateAndReactivateRequireConfirmation() throws Exception {
        Fixture f = seed();
        grantSysadmin(f.sysadmin());
        formLogin("sysop@example.org", "supersecret-sysop");
        String detailPath = "/admin/organizations/" + f.orgA().getId();

        var unconfirmed = postForm(detailPath + "/deactivate", "", pageCsrf(detailPath));
        assertEquals(302, unconfirmed.statusCode());
        assertEquals(OrganizationStatus.ACTIVE,
                organizations.findById(f.orgA().getId()).orElseThrow().getStatus());

        var deactivated = postForm(detailPath + "/deactivate", "confirmed=true",
                pageCsrf(detailPath));
        assertEquals(302, deactivated.statusCode());
        assertEquals(OrganizationStatus.DISABLED,
                organizations.findById(f.orgA().getId()).orElseThrow().getStatus());
        assertTrue(followRedirect(deactivated).contains("Deaktiviert")
                || get(detailPath).body().contains("Deaktiviert"));

        var unconfirmedRe = postForm(detailPath + "/reactivate", "", pageCsrf(detailPath));
        assertEquals(302, unconfirmedRe.statusCode());
        assertEquals(OrganizationStatus.DISABLED,
                organizations.findById(f.orgA().getId()).orElseThrow().getStatus());

        var reactivated = postForm(detailPath + "/reactivate", "confirmed=true",
                pageCsrf(detailPath));
        assertEquals(302, reactivated.statusCode());
        assertEquals(OrganizationStatus.ACTIVE,
                organizations.findById(f.orgA().getId()).orElseThrow().getStatus());
    }

    @Test
    void reactivationDoesNotRestoreSharingGrants() throws Exception {
        Fixture f = seed();
        grantSysadmin(f.sysadmin());
        // Owner ADMIN (tenant authority) sets up explicit grants first.
        sharePolicyService.setDiscoverable(f.adminA().getId(), f.orgA().getId(), true);
        memberships.save(new OrganizationMembership(f.orgB(), f.adminA(), MembershipRole.ADMIN,
                MembershipStatus.ACTIVE));
        sharePolicyService.setDiscoverable(f.adminA().getId(), f.orgB().getId(), true);
        sharePolicyService.upsert(f.adminA().getId(), f.orgA().getId(), ShareScope.CARE,
                ShareAudience.ALLOWLIST, List.of(f.orgB().getId()));
        sharePolicyService.upsert(f.adminA().getId(), f.orgA().getId(), ShareScope.VISITS,
                ShareAudience.ALLOWLIST, List.of(f.orgB().getId()));
        assertTrue(sharePolicyService
                .hasEffectiveScope(f.orgB().getId(), f.orgA().getId(), ShareScope.CARE));

        formLogin("sysop@example.org", "supersecret-sysop");
        String detailPath = "/admin/organizations/" + f.orgA().getId();
        assertEquals(302, postForm(detailPath + "/deactivate", "confirmed=true",
                pageCsrf(detailPath)).statusCode());
        assertTrue(sharePolicies.findByOrganizationId(f.orgA().getId()).isEmpty(),
                "deactivation deletes outgoing grants");
        assertTrue(sharePolicyService.resolveEffectiveScopes(f.orgB().getId(), f.orgA().getId())
                .isEmpty());

        assertEquals(302, postForm(detailPath + "/reactivate", "confirmed=true",
                pageCsrf(detailPath)).statusCode());
        assertTrue(sharePolicies.findByOrganizationId(f.orgA().getId()).isEmpty(),
                "reactivation restores no grants");
        assertTrue(sharePolicyService.resolveEffectiveScopes(f.orgB().getId(), f.orgA().getId())
                .isEmpty());
    }

    @Test
    void deletionRequestRetainsAllData() throws Exception {
        Fixture f = seed();
        grantSysadmin(f.sysadmin());
        NodeDevice node = new NodeDevice(UUID.randomUUID(), "x-" + UUID.randomUUID(),
                "y-" + UUID.randomUUID(), "fp-" + UUID.randomUUID(), "1.0");
        node.claim(f.orgA());
        nodes.save(node);
        Cat cat = cats.save(new Cat(f.orgA(), "chip-del", "Del", null, null));
        UUID memberId = memberships
                .findByOrganizationIdAndUserId(f.orgA().getId(), f.memberA().getId()).orElseThrow()
                .getId();
        formLogin("sysop@example.org", "supersecret-sysop");
        String detailPath = "/admin/organizations/" + f.orgA().getId();

        var wrongSlug = postForm(detailPath + "/delete",
                "slugConfirmation=" + enc("falsch") + "&confirmed=true", pageCsrf(detailPath));
        assertEquals(302, wrongSlug.statusCode());
        assertEquals(OrganizationStatus.ACTIVE,
                organizations.findById(f.orgA().getId()).orElseThrow().getStatus());

        var deleted = postForm(detailPath + "/delete",
                "slugConfirmation=" + enc("org-a") + "&confirmed=true", pageCsrf(detailPath));
        assertEquals(302, deleted.statusCode());
        assertEquals(OrganizationStatus.DISABLED,
                organizations.findById(f.orgA().getId()).orElseThrow().getStatus());
        // Safe retention: nothing is cascade-deleted.
        assertTrue(organizations.findById(f.orgA().getId()).isPresent());
        assertTrue(cats.findById(cat.getId()).isPresent());
        assertTrue(nodes.findById(node.getNodeId()).isPresent());
        assertTrue(memberships.findById(memberId).isPresent());
        assertTrue(sysadminAudit.findByOrganizationIdOrderByCreatedAtDesc(f.orgA().getId()).stream()
                .anyMatch(a -> a.getAction() == SysadminOrgAction.DELETE_REQUESTED));
        assertTrue(followRedirect(deleted).contains("aufbewahrt")
                || get(detailPath).body().contains("Deaktiviert"));
    }

    @Test
    void assignAndRevokeAdmin() throws Exception {
        Fixture f = seed();
        grantSysadmin(f.sysadmin());
        formLogin("sysop@example.org", "supersecret-sysop");
        String detailPath = "/admin/organizations/" + f.orgA().getId();

        var assigned = postForm(detailPath + "/admins",
                "email=" + enc("outsider@example.org"), pageCsrf(detailPath));
        assertEquals(302, assigned.statusCode());
        var membership = memberships.findByOrganizationIdAndUserId(f.orgA().getId(),
                f.outsider().getId()).orElseThrow();
        assertEquals(MembershipRole.ADMIN, membership.getRole());
        assertEquals(MembershipStatus.ACTIVE, membership.getStatus());

        // Duplicate assignment is a conflict, not a second membership.
        var duplicate = postForm(detailPath + "/admins",
                "email=" + enc("outsider@example.org"), pageCsrf(detailPath));
        assertEquals(302, duplicate.statusCode());
        assertTrue(followRedirect(duplicate).contains("bereits Orgaadmin"));
        assertEquals(1, memberships.findByOrganizationId(f.orgA().getId()).stream()
                .filter(m -> m.getUser().getId().equals(f.outsider().getId())).count());

        var revoked = postForm(
                detailPath + "/admins/" + f.outsider().getId() + "/revoke", "",
                pageCsrf(detailPath));
        assertEquals(302, revoked.statusCode());
        var demoted = memberships.findByOrganizationIdAndUserId(f.orgA().getId(),
                f.outsider().getId()).orElseThrow();
        assertEquals(MembershipRole.MEMBER, demoted.getRole());
        assertEquals(MembershipStatus.ACTIVE, demoted.getStatus());

        var audits = sysadminAudit.findByOrganizationIdOrderByCreatedAtDesc(f.orgA().getId());
        assertTrue(audits.stream().anyMatch(a -> a.getAction() == SysadminOrgAction.ADMIN_GRANTED
                && f.outsider().getEmail().equals(
                        a.getTarget() == null ? null : a.getTarget().getEmail())));
        assertTrue(audits.stream().anyMatch(a -> a.getAction() == SysadminOrgAction.ADMIN_REVOKED));

        var unknown = postForm(detailPath + "/admins",
                "email=" + enc("unbekannt@example.org"), pageCsrf(detailPath));
        assertEquals(302, unknown.statusCode());
        assertTrue(followRedirect(unknown).contains("Kein Konto"));
    }

    @Test
    void multipleMembershipsStayIndependent() throws Exception {
        Fixture f = seed();
        grantSysadmin(f.sysadmin());
        formLogin("sysop@example.org", "supersecret-sysop");
        String pathA = "/admin/organizations/" + f.orgA().getId();

        assertEquals(302, postForm(pathA + "/admins", "email=" + enc("multi@example.org"),
                pageCsrf(pathA)).statusCode());
        assertEquals(MembershipRole.ADMIN, memberships.findByOrganizationIdAndUserId(
                f.orgA().getId(), f.multi().getId()).orElseThrow().getRole());

        assertEquals(302, postForm(pathA + "/admins/" + f.multi().getId() + "/revoke", "",
                pageCsrf(pathA)).statusCode());
        // Only organization A changed; the B membership is untouched.
        assertEquals(MembershipRole.MEMBER, memberships.findByOrganizationIdAndUserId(
                f.orgA().getId(), f.multi().getId()).orElseThrow().getRole());
        var inB = memberships.findByOrganizationIdAndUserId(f.orgB().getId(), f.multi().getId())
                .orElseThrow();
        assertEquals(MembershipRole.ADMIN, inB.getRole());
        assertEquals(MembershipStatus.ACTIVE, inB.getStatus());
    }

    @Test
    void disabledAccountsRejectedAndInactiveOrganizationsHandledSafely() throws Exception {
        Fixture f = seed();
        grantSysadmin(f.sysadmin());
        formLogin("sysop@example.org", "supersecret-sysop");
        String pathA = "/admin/organizations/" + f.orgA().getId();

        var disabledAccount = postForm(pathA + "/admins",
                "email=" + enc("locked92@example.org"), pageCsrf(pathA));
        assertEquals(302, disabledAccount.statusCode());
        assertTrue(followRedirect(disabledAccount).contains("deaktiviert"));
        assertTrue(memberships
                .findByOrganizationIdAndUserId(f.orgA().getId(), f.disabled().getId()).isEmpty());

        // Provisioning on an inactive organization is allowed (needed for
        // recovery), stays audited, but grants no effective tenant access.
        assertEquals(302, postForm(pathA + "/deactivate", "confirmed=true",
                pageCsrf(pathA)).statusCode());
        var onInactive = postForm(pathA + "/admins", "email=" + enc("outsider@example.org"),
                pageCsrf(pathA));
        assertEquals(302, onInactive.statusCode());
        assertEquals(MembershipRole.ADMIN, memberships.findByOrganizationIdAndUserId(
                f.orgA().getId(), f.outsider().getId()).orElseThrow().getRole());
        assertEquals(OrganizationStatus.DISABLED,
                organizations.findById(f.orgA().getId()).orElseThrow().getStatus());
    }

    @Test
    void nonSysadminAccessIsRejected() throws Exception {
        Fixture f = seed();
        grantSysadmin(f.sysadmin());

        // Organization ADMIN without the global role: filter gate denies.
        formLogin("orga-a@example.org", "supersecret-a");
        assertEquals(403, get("/admin/organizations").statusCode());
        var post = client.send(HttpRequest.newBuilder(URI.create(base("/admin/organizations")))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("slug=x&displayName=y"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(post.statusCode() == 302 || post.statusCode() == 403,
                "got " + post.statusCode());
        if (post.statusCode() == 403) {
            assertTrue(organizations.findBySlug("x").isEmpty());
        }

        http();
        formLogin("member-a@example.org", "supersecret-m");
        assertEquals(403, get("/admin/organizations").statusCode());
        assertEquals(403,
                get("/admin/organizations/" + f.orgA().getId()).statusCode());

        // Unauthenticated: redirect to the admin login, never a data page.
        http();
        assertEquals(302, get("/admin/organizations").statusCode());
        assertTrue(get("/admin/organizations").headers().firstValue("location").orElse("")
                .contains("/admin/login"));
    }

    @Test
    void sysadminWithoutMembershipCannotReadTenantData() throws Exception {
        Fixture f = seed();
        grantSysadmin(f.sysadmin());
        formLogin("sysop@example.org", "supersecret-sysop");

        // Metadata administration works …
        assertEquals(200, get("/admin/organizations").statusCode());
        assertEquals(200, get("/admin/organizations/" + f.orgA().getId()).statusCode());
        // … but tenant data stays closed without a membership.
        assertEquals(403, get("/admin/cats").statusCode());
        assertEquals(403, get("/admin/members").statusCode());
        assertEquals(403,
                get("/api/v1/organizations/" + f.orgA().getId() + "/cats").statusCode());
    }

    @Test
    void forgedAndGuessedIdsDoNotLeak() throws Exception {
        Fixture f = seed();
        grantSysadmin(f.sysadmin());
        formLogin("sysop@example.org", "supersecret-sysop");

        UUID guessed = UUID.randomUUID();
        assertEquals(404, get("/admin/organizations/" + guessed).statusCode());
        var forged = postForm("/admin/organizations/" + guessed + "/rename",
                "displayName=" + enc("Angriff"), pageCsrf("/admin/organizations"));
        assertEquals(302, forged.statusCode());
        assertEquals(404, get(forged.headers().firstValue("location").orElse("/missing"))
                .statusCode());

        // Organization ADMINs must not reach the SYSADMIN surface at all,
        // including for their own organization id.
        http();
        formLogin("orga-a@example.org", "supersecret-a");
        var own = get("/admin/organizations/" + f.orgA().getId());
        assertEquals(403, own.statusCode(), own.body());
        assertFalse(own.body().contains("outsider@example.org"));
    }

    @Test
    void mutationsRequireCsrf() throws Exception {
        Fixture f = seed();
        grantSysadmin(f.sysadmin());
        formLogin("sysop@example.org", "supersecret-sysop");

        var noToken = client.send(HttpRequest.newBuilder(URI.create(base("/admin/organizations")))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("slug=csrf-org&displayName=CSRF"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, noToken.statusCode(), noToken.body());
        assertTrue(organizations.findBySlug("csrf-org").isEmpty());

        var badToken = client.send(
                HttpRequest.newBuilder(URI.create(base("/admin/organizations/" + f.orgA().getId()
                        + "/rename")))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "displayName=" + enc("Angriff") + "&_csrf=invalid-token-value"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(403, badToken.statusCode());
        assertEquals("Org A",
                organizations.findById(f.orgA().getId()).orElseThrow().getDisplayName());
    }

    @Test
    void auditCorrectnessAndFailedOperationsLeaveNoTrace() throws Exception {
        Fixture f = seed();
        grantSysadmin(f.sysadmin());
        formLogin("sysop@example.org", "supersecret-sysop");

        int auditsBefore = sysadminAudit.findByOrganizationIdOrderByCreatedAtDesc(f.orgA().getId())
                .size();
        // Duplicate slug: conflict, no organization, no audit row.
        var duplicate = postForm("/admin/organizations",
                "slug=" + enc("org-a") + "&displayName=" + enc("Doppelgänger"),
                pageCsrf("/admin/organizations"));
        assertEquals(302, duplicate.statusCode());
        assertTrue(followRedirect(duplicate).contains("vergeben"));
        assertEquals(1, organizations.findAll().stream()
                .filter(o -> o.getSlug().equals("org-a")).count());
        assertEquals(auditsBefore, sysadminAudit
                .findByOrganizationIdOrderByCreatedAtDesc(f.orgA().getId()).size());

        // Blank rename: rejected, name and audit unchanged (atomic no-op).
        String detailPath = "/admin/organizations/" + f.orgA().getId();
        var blank = postForm(detailPath + "/rename",
                "displayName=" + enc("   "), pageCsrf(detailPath));
        assertEquals(302, blank.statusCode());
        assertEquals("Org A",
                organizations.findById(f.orgA().getId()).orElseThrow().getDisplayName());
        assertEquals(auditsBefore, sysadminAudit
                .findByOrganizationIdOrderByCreatedAtDesc(f.orgA().getId()).size());

        // Successful rename records actor, operation, timestamp and states.
        var renamed = postForm(detailPath + "/rename",
                "displayName=" + enc("Org A Audit"), pageCsrf(detailPath));
        assertEquals(302, renamed.statusCode());
        var audits = sysadminAudit.findByOrganizationIdOrderByCreatedAtDesc(f.orgA().getId());
        assertEquals(auditsBefore + 1, audits.size());
        var entry = audits.get(0);
        assertEquals("sysop@example.org", entry.getActor().getEmail());
        assertEquals(f.orgA().getId(), entry.getOrganization().getId());
        assertEquals(SysadminOrgAction.RENAME, entry.getAction());
        assertTrue(entry.getBeforeState().contains("Org A"));
        assertTrue(entry.getAfterState().contains("Org A Audit"));
        assertNotNull(entry.getCreatedAt());
    }

    @Test
    void existingOrgAdminFunctionalityRemainsUnchanged() throws Exception {
        seed();
        // No SYSADMIN involved: the regular tenant admin surface keeps working.
        formLogin("orga-a@example.org", "supersecret-a");
        var members = get("/admin/members");
        assertEquals(200, members.statusCode(), members.body());
        assertTrue(members.body().contains("member-a@example.org"), members.body());
        assertTrue(members.body().contains("Orgaadmin"), members.body());
    }
}
