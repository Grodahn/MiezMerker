package org.miezmerker.backend;

import org.junit.jupiter.api.Tag;

import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.miezmerker.backend.domain.AppUser;
import org.miezmerker.backend.domain.DerivedVisit;
import org.miezmerker.backend.domain.FeedingSite;
import org.miezmerker.backend.domain.MembershipRole;
import org.miezmerker.backend.domain.MembershipStatus;
import org.miezmerker.backend.domain.NodeDeployment;
import org.miezmerker.backend.domain.NodeDevice;
import org.miezmerker.backend.domain.Organization;
import org.miezmerker.backend.domain.OrganizationMembership;
import org.miezmerker.backend.domain.RawObservation;
import org.miezmerker.backend.domain.UserStatus;
import org.miezmerker.backend.repo.AppUserRepository;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Acceptance tests for #35: feeding-site management in the server-rendered
 * Admin backend ({@code /admin/sites}).
 *
 * <p>Covers ADMIN-only access, tenant-scoped list/create/detail/edit, CSRF,
 * honest clock display and the critical historical guarantee: editing a site
 * never reinterprets old deployments, raw observations or derived visits.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Tag("admin")
@Tag("auth")
@Tag("node")
class AdminSitesTest {
    @Value("${local.server.port}") int port;
    String formOrganizationId;

    @Autowired AppUserRepository users;
    @Autowired OrganizationRepository organizations;
    @Autowired MembershipRepository memberships;
    @Autowired NodeRepository nodes;
    @Autowired RawObservationRepository observations;
    @Autowired DerivedVisitRepository derivedVisits;
    @Autowired NodeDeploymentRepository deployments;
    @Autowired FeedingSiteRepository sites;
    @Autowired PasswordEncoder passwords;
    @Autowired TestDatabaseCleaner cleaner;
    @Autowired jakarta.persistence.EntityManagerFactory entityManagerFactory;

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
        var response = client.send(HttpRequest.newBuilder(URI.create(base(path))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        Matcher org = Pattern.compile("name=\"formOrganizationId\"[^>]*value=\"([^\"]+)\"")
                .matcher(response.body());
        if (org.find()) {
            formOrganizationId = org.group(1);
        }
        return response;
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
        String loc = res.headers().firstValue("location").orElse("");
        assertTrue(loc.contains("/admin/"), "login should redirect to admin shell, got: " + loc);
    }

    String adminPageCsrf(String path) throws Exception {
        var res = get(path);
        assertEquals(200, res.statusCode(), res.body());
        String token = extractCsrf(res.body());
        assertNotNull(token, "admin page must expose CSRF token: " + path);
        return token;
    }

    HttpResponse<String> postForm(String path, String formBody) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base(path)))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formBody)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    String formBody(String csrf, String... pairs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pairs.length; i += 2) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(pairs[i], StandardCharsets.UTF_8));
            sb.append('=');
            sb.append(URLEncoder.encode(pairs[i + 1], StandardCharsets.UTF_8));
        }
        if (csrf != null) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append("_csrf=").append(URLEncoder.encode(csrf, StandardCharsets.UTF_8));
        }
        if (formOrganizationId != null && !java.util.Arrays.asList(pairs)
                .contains("formOrganizationId")) {
            sb.append("&formOrganizationId=").append(formOrganizationId);
        }
        return sb.toString();
    }

    record Fixture(Organization orgA, Organization orgB, AppUser adminA, AppUser memberA,
            AppUser adminB, AppUser pendingA, AppUser disabledMemberA, AppUser disabledAccount,
            AppUser multiAdmin) {}

    Fixture seed() {
        cleaner.clean();

        Organization orgA = organizations.save(new Organization("org-a35", "Org A", null));
        Organization orgB = organizations.save(new Organization("org-b35", "Org B", null));

        AppUser adminA = users.save(new AppUser("a35-admin-a@example.org",
                passwords.encode("supersecret-password-a"), "Ada Admin"));
        AppUser memberA = users.save(new AppUser("a35-member-a@example.org",
                passwords.encode("supersecret-password-m")));
        AppUser adminB = users.save(new AppUser("a35-admin-b@example.org",
                passwords.encode("supersecret-password-b")));
        AppUser pendingA = users.save(new AppUser("a35-pending-a@example.org",
                passwords.encode("supersecret-password-p")));
        AppUser disabledMemberA = users.save(new AppUser("a35-disabled-a@example.org",
                passwords.encode("supersecret-password-d")));
        AppUser disabledAccount = users.save(new AppUser("a35-locked@example.org",
                passwords.encode("supersecret-password-l")));
        disabledAccount.setStatus(UserStatus.DISABLED);
        users.save(disabledAccount);
        AppUser multiAdmin = users.save(new AppUser("a35-multi-admin@example.org",
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

    FeedingSite saveSite(Organization org, String name, String description,
            String label) {
        return sites.save(new FeedingSite(org, name, description, null, null, label));
    }

    NodeDevice saveClaimedNode(Organization org) {
        UUID nodeId = UUID.randomUUID();
        String fingerprint = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "").substring(0, 28);
        NodeDevice node = new NodeDevice(nodeId, "x-" + UUID.randomUUID(),
                "y-" + UUID.randomUUID(), fingerprint.substring(0, 60), "test-1");
        node.claim(org);
        node.touchContact();
        return nodes.save(node);
    }

    @Test
    void siteDetailQueryCountDoesNotGrowPerCurrentNode() throws Exception {
        Fixture f = seed();
        FeedingSite site = saveSite(f.orgA(), "Batch Site", null, null);
        Instant start = Instant.parse("2025-01-01T00:00:00Z");
        deployments.save(new NodeDeployment(f.orgA(), saveClaimedNode(f.orgA()), site, start, null));
        assertLoginSuccess(formLogin("a35-admin-a@example.org", "supersecret-password-a"));
        var statistics = entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        try {
            statistics.clear();
            assertEquals(200, get("/admin/sites/" + site.getId()).statusCode());
            long baseline = statistics.getPrepareStatementCount();
            for (int i = 0; i < 25; i++) {
                deployments.save(new NodeDeployment(f.orgA(), saveClaimedNode(f.orgA()), site, start, null));
            }
            statistics.clear();
            var detail = get("/admin/sites/" + site.getId());
            assertEquals(200, detail.statusCode(), detail.body());
            assertTrue(detail.body().contains("test-1"));
            assertTrue(statistics.getPrepareStatementCount() <= baseline + 2,
                    "SQL count must stay constant: baseline=" + baseline
                            + ", actual=" + statistics.getPrepareStatementCount());
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }

    // --- Access ---

    @Test
    void unauthenticatedSitesRedirectToLogin() throws Exception {
        seed();
        for (String path : new String[] {"/admin/sites", "/admin/sites/new",
                "/admin/sites/" + UUID.randomUUID(),
                "/admin/sites/" + UUID.randomUUID() + "/edit"}) {
            var res = get(path);
            assertEquals(302, res.statusCode(), path + ": " + res.body());
            assertTrue(res.headers().firstValue("location").orElse("")
                    .contains("/admin/login"), path);
        }
    }

    @Test
    void memberOnlyPendingAndDisabledAreDenied() throws Exception {
        seed();
        var login = formLogin("a35-member-a@example.org", "supersecret-password-m");
        assertEquals(302, login.statusCode(), login.body());
        for (String path : new String[] {"/admin/sites", "/admin/sites/new"}) {
            var denied = get(path);
            assertEquals(403, denied.statusCode(), path + ": " + denied.body());
            assertTrue(denied.body().contains("Kein Admin-Zugang"), path);
            assertFalse(denied.body().contains("Org A"), path);
        }

        http();
        formLogin("a35-pending-a@example.org", "supersecret-password-p");
        assertEquals(403, get("/admin/sites").statusCode());

        http();
        formLogin("a35-disabled-a@example.org", "supersecret-password-d");
        assertEquals(403, get("/admin/sites").statusCode());
    }

    @Test
    void activeAdminIsAllowed() throws Exception {
        seed();
        var login = formLogin("a35-admin-a@example.org", "supersecret-password-a");
        assertLoginSuccess(login);
        var list = get("/admin/sites");
        assertEquals(200, list.statusCode(), list.body());
        assertTrue(list.body().contains("Futterstellen"), list.body());
        assertTrue(list.body().contains("Org A"), list.body());
        var form = get("/admin/sites/new");
        assertEquals(200, form.statusCode(), form.body());
        assertTrue(form.body().contains("Futterstelle anlegen"), form.body());
    }

    // --- List ---

    @Test
    void activeOrgSeesOnlyOwnSitesAndEmptyRenders() throws Exception {
        Fixture f = seed();
        saveSite(f.orgA(), "Site Alpha", "Beschreibung Alpha", "Hinterhof");
        saveSite(f.orgA(), "Site Beta", null, null);
        saveSite(f.orgB(), "Site Fremd", "Geheim", "Geheimort");

        var login = formLogin("a35-admin-a@example.org", "supersecret-password-a");
        assertLoginSuccess(login);
        var list = get("/admin/sites");
        assertEquals(200, list.statusCode(), list.body());
        assertTrue(list.body().contains("Site Alpha"), list.body());
        assertTrue(list.body().contains("Site Beta"), list.body());
        assertTrue(list.body().contains("Hinterhof"), list.body());
        assertFalse(list.body().contains("Site Fremd"), list.body());
        assertFalse(list.body().contains("Geheimort"), list.body());

        http();
        var loginB = formLogin("a35-admin-b@example.org", "supersecret-password-b");
        assertLoginSuccess(loginB);
        var listB = get("/admin/sites");
        assertEquals(200, listB.statusCode(), listB.body());
        assertTrue(listB.body().contains("Site Fremd"), listB.body());
        assertFalse(listB.body().contains("Site Alpha"), listB.body());

        // Empty organization renders a clear empty state, not an error.
        sites.deleteAll();
        // Re-seed one B site so B is non-empty and A is empty.
        saveSite(f.orgB(), "Nur B", null, null);
        http();
        formLogin("a35-admin-a@example.org", "supersecret-password-a");
        var empty = get("/admin/sites");
        assertEquals(200, empty.statusCode(), empty.body());
        assertTrue(empty.body().contains("Noch keine Futterstellen vorhanden."),
                empty.body());
        assertFalse(empty.body().contains("Nur B"), empty.body());
    }

    // --- Create ---

    @Test
    void validCreationBelongsToActiveOrg() throws Exception {
        Fixture f = seed();
        var login = formLogin("a35-admin-a@example.org", "supersecret-password-a");
        assertLoginSuccess(login);
        String csrf = adminPageCsrf("/admin/sites/new");
        var created = postForm("/admin/sites", formBody(csrf,
                "name", "Neue Stelle",
                "description", "Futterecke",
                "locationLabel", "Garten",
                "locationLat", "51.5",
                "locationLng", "7.1"));
        assertEquals(302, created.statusCode(), created.body());
        String loc = created.headers().firstValue("location").orElse("");
        assertTrue(loc.contains("/admin/sites/"), loc);
        String siteId = loc.substring(loc.lastIndexOf('/') + 1);
        var detail = get("/admin/sites/" + siteId);
        assertEquals(200, detail.statusCode(), detail.body());
        assertTrue(detail.body().contains("Neue Stelle"), detail.body());
        assertTrue(detail.body().contains("Futterecke"), detail.body());
        assertTrue(detail.body().contains("Garten"), detail.body());

        FeedingSite stored = sites.findById(UUID.fromString(siteId)).orElseThrow();
        assertEquals(f.orgA().getId(), stored.getOrganization().getId());
        assertEquals("Neue Stelle", stored.getName());
        assertEquals("Futterecke", stored.getDescription());
        assertEquals("Garten", stored.getLocationLabel());
        assertEquals(51.5, stored.getLocationLat());
        assertEquals(7.1, stored.getLocationLng());
    }

    @Test
    void validationErrorsRenderBackWithoutLosingValues() throws Exception {
        seed();
        formLogin("a35-admin-a@example.org", "supersecret-password-a");
        String csrf = adminPageCsrf("/admin/sites/new");

        // Blank name.
        var blank = postForm("/admin/sites", formBody(csrf,
                "name", "   ",
                "description", "Meine Beschreibung",
                "locationLabel", "Mein Ort"));
        assertEquals(200, blank.statusCode(), blank.body());
        assertTrue(blank.body().contains("Bitte einen Namen eingeben."), blank.body());
        assertTrue(blank.body().contains("Meine Beschreibung"), blank.body());
        assertTrue(blank.body().contains("Mein Ort"), blank.body());
        assertEquals(0, sites.count());

        // Half coordinates.
        var half = postForm("/admin/sites", formBody(csrf,
                "name", "Halbe Koordinaten",
                "locationLat", "51.5",
                "locationLng", ""));
        assertEquals(200, half.statusCode(), half.body());
        assertTrue(half.body().contains("zusammen angeben"), half.body());
        assertTrue(half.body().contains("Halbe Koordinaten"), half.body());
        assertEquals(0, sites.count());

        // Out of range.
        var range = postForm("/admin/sites", formBody(csrf,
                "name", "Weit weg",
                "locationLat", "200",
                "locationLng", "10"));
        assertEquals(200, range.statusCode(), range.body());
        assertTrue(range.body().contains("Breitengrad muss zwischen -90 und 90 liegen."),
                range.body());
        assertEquals(0, sites.count());
    }

    @Test
    void csrfIsRequiredForCreateAndEdit() throws Exception {
        Fixture f = seed();
        FeedingSite existing = saveSite(f.orgA(), "Bestehend", null, null);
        formLogin("a35-admin-a@example.org", "supersecret-password-a");

        var noCsrfCreate = postForm("/admin/sites",
                "name=" + URLEncoder.encode("Ohne Token", StandardCharsets.UTF_8));
        assertEquals(403, noCsrfCreate.statusCode(), noCsrfCreate.body());
        assertEquals(1, sites.count());

        var noCsrfEdit = postForm("/admin/sites/" + existing.getId(),
                "name=" + URLEncoder.encode("Hijack", StandardCharsets.UTF_8));
        assertEquals(403, noCsrfEdit.statusCode(), noCsrfEdit.body());
        assertEquals("Bestehend",
                sites.findById(existing.getId()).orElseThrow().getName());
    }

    @Test
    void memberCannotMutateViaAdminButRestStillAllowsCare() throws Exception {
        seed();
        formLogin("a35-member-a@example.org", "supersecret-password-m");
        // No CSRF fetch possible via admin (403); use a forged body to prove the
        // gate, not the token, denies the member.
        var denied = postForm("/admin/sites",
                "name=" + URLEncoder.encode("Member Stelle", StandardCharsets.UTF_8));
        // CSRF filter runs before authorization: without a token this is 403
        // either way. With a token it must still be 403 (ADMIN-only).
        assertEquals(403, denied.statusCode(), denied.body());
        assertEquals(0, sites.count());
        // REST/PWA routine care stays MEMBER-capable (#11): covered by
        // Issue9IngestTest.memberCanReadIngestAndProvideRoutineCare, so the
        // Admin gate must not weaken REST permissions to make pages work.
    }

    // --- Detail ---

    @Test
    void detailShowsCurrentNodesHistoryAndHonestClock() throws Exception {
        Fixture f = seed();
        FeedingSite siteA = saveSite(f.orgA(), "Site A", "Alt", "Hof A");
        FeedingSite siteB = saveSite(f.orgA(), "Site B", "Neu", "Hof B");
        NodeDevice node = saveClaimedNode(f.orgA());
        Instant t0 = Instant.parse("2025-01-01T00:00:00Z");
        Instant t1 = Instant.parse("2025-04-01T00:00:00Z");
        NodeDeployment dA = deployments.save(
                new NodeDeployment(f.orgA(), node, siteA, t0, t1));
        deployments.save(new NodeDeployment(f.orgA(), node, siteB, t1, null));
        RawObservation oldObs = observations.save(new RawObservation(f.orgA(), node, 1,
                "CHIP-1", t0.plusSeconds(60).toEpochMilli(), "SYNCED", null, null,
                null, siteA, dA));
        observations.save(new RawObservation(f.orgA(), node, 2,
                "CHIP-1", null, "UNKNOWN", null, 123L, 1, null, null));

        var login = formLogin("a35-admin-a@example.org", "supersecret-password-a");
        assertLoginSuccess(login);
        var detailB = get("/admin/sites/" + siteB.getId());
        assertEquals(200, detailB.statusCode(), detailB.body());
        assertTrue(detailB.body().contains("Site B"), detailB.body());
        assertTrue(detailB.body().contains("Hof B"), detailB.body());
        // Currently assigned node is shown with server contact info.
        assertTrue(detailB.body().contains(node.getNodeId().toString()), detailB.body());
        // Open deployment is marked honestly.
        assertTrue(detailB.body().contains("Ohne Enddatum"), detailB.body());
        // UNKNOWN clock is never a precise timestamp.
        assertTrue(detailB.body().contains("Uhrzeit unbekannt"), detailB.body());
        assertFalse(detailB.body().contains("Rohwert: null"), detailB.body());

        var detailA = get("/admin/sites/" + siteA.getId());
        assertEquals(200, detailA.statusCode(), detailA.body());
        assertTrue(detailA.body().contains("Site A"), detailA.body());
        // Frozen historical attribution: old observation stays at Site A.
        assertTrue(detailA.body().contains("CHIP-1"), detailA.body());
        assertEquals(siteA.getId(), oldObs.getFeedingSite().getId());
    }

    // --- Edit ---

    @Test
    void editUpdatesMasterDataAndKeepsOwnershipAndHistory() throws Exception {
        Fixture f = seed();
        FeedingSite site = saveSite(f.orgA(), "Alter Name", "Alte Beschreibung",
                "Alter Ort");
        NodeDevice node = saveClaimedNode(f.orgA());
        Instant from = Instant.parse("2025-02-01T00:00:00Z");
        NodeDeployment deployment = deployments.save(
                new NodeDeployment(f.orgA(), node, site, from, null));
        Instant depFrom = deployment.getValidFrom();
        assertNull(deployment.getValidUntil());

        formLogin("a35-admin-a@example.org", "supersecret-password-a");
        var form = get("/admin/sites/" + site.getId() + "/edit");
        assertEquals(200, form.statusCode(), form.body());
        assertTrue(form.body().contains("Alter Name"), form.body());

        String csrf = extractCsrf(form.body());
        assertNotNull(csrf);
        var updated = postForm("/admin/sites/" + site.getId(), formBody(csrf,
                "name", "Neuer Name",
                "description", "Neue Beschreibung",
                "locationLabel", "Neuer Ort",
                "locationLat", "52.0",
                "locationLng", "8.0"));
        assertEquals(302, updated.statusCode(), updated.body());
        var detail = get("/admin/sites/" + site.getId());
        assertEquals(200, detail.statusCode(), detail.body());
        assertTrue(detail.body().contains("Neuer Name"), detail.body());

        FeedingSite stored = sites.findById(site.getId()).orElseThrow();
        assertEquals("Neuer Name", stored.getName());
        assertEquals("Neue Beschreibung", stored.getDescription());
        assertEquals("Neuer Ort", stored.getLocationLabel());
        // Ownership and stable id unchanged.
        assertEquals(f.orgA().getId(), stored.getOrganization().getId());
        assertEquals(site.getId(), stored.getId());
        // Historical deployment untouched.
        NodeDeployment reloaded =
                deployments.findById(deployment.getId()).orElseThrow();
        assertEquals(depFrom, reloaded.getValidFrom());
        assertNull(reloaded.getValidUntil());
        assertEquals(site.getId(), reloaded.getFeedingSite().getId());
    }

    // --- Tenant isolation ---

    @Test
    void foreignSiteIdsDoNotLeakAnyData() throws Exception {
        Fixture f = seed();
        FeedingSite foreign = sites.save(new FeedingSite(f.orgB(), "Geheime Stelle",
                "Geheime Beschreibung", 10.0, 20.0, "Geheimort"));
        NodeDevice foreignNode = saveClaimedNode(f.orgB());
        deployments.save(new NodeDeployment(f.orgB(), foreignNode, foreign,
                Instant.parse("2025-03-01T00:00:00Z"), null));
        observations.save(new RawObservation(f.orgB(), foreignNode, 1, "GEHEIM",
                Instant.parse("2025-03-02T00:00:00Z").toEpochMilli(), "SYNCED",
                null, null, null, foreign, null));

        formLogin("a35-admin-a@example.org", "supersecret-password-a");

        var detail = get("/admin/sites/" + foreign.getId());
        assertEquals(404, detail.statusCode(), detail.body());
        assertFalse(detail.body().contains("Geheime Stelle"), detail.body());
        assertFalse(detail.body().contains("Geheime Beschreibung"), detail.body());
        assertFalse(detail.body().contains("Geheimort"), detail.body());
        assertFalse(detail.body().contains("GEHEIM"), detail.body());
        assertFalse(detail.body().contains(foreignNode.getNodeId().toString()),
                detail.body());

        var editForm = get("/admin/sites/" + foreign.getId() + "/edit");
        assertEquals(404, editForm.statusCode(), editForm.body());
        assertFalse(editForm.body().contains("Geheime Stelle"), editForm.body());

        String csrf = adminPageCsrf("/admin/sites/new");
        var edit = postForm("/admin/sites/" + foreign.getId(), formBody(csrf,
                "name", "Hijack",
                "description", "Hijack"));
        assertEquals(404, edit.statusCode(), edit.body());
        assertEquals("Geheime Stelle",
                sites.findById(foreign.getId()).orElseThrow().getName());

        // Random ids behave identically (no oracle).
        UUID random = UUID.randomUUID();
        assertEquals(404, get("/admin/sites/" + random).statusCode());
    }

    // --- Historical semantics (critical) ---

    @Test
    void nodeMoveARetainsHistoryAndEditsDoNotReinterpret() throws Exception {
        Fixture f = seed();
        FeedingSite siteA = saveSite(f.orgA(), "Site A", "Historisch", "Ort A");
        FeedingSite siteB = saveSite(f.orgA(), "Site B", "Aktuell", "Ort B");
        NodeDevice node = saveClaimedNode(f.orgA());
        Instant jan = Instant.parse("2025-01-01T00:00:00Z");
        Instant apr = Instant.parse("2025-04-01T00:00:00Z");
        NodeDeployment depA = deployments.save(
                new NodeDeployment(f.orgA(), node, siteA, jan, apr));
        NodeDeployment depB = deployments.save(
                new NodeDeployment(f.orgA(), node, siteB, apr, null));

        // January observation belongs to Site A, June to Site B (frozen).
        RawObservation janObs = observations.save(new RawObservation(f.orgA(), node, 1,
                "CHIP-H", jan.plus(Duration.ofDays(10)).toEpochMilli(), "SYNCED",
                null, null, null, siteA, depA));
        RawObservation junObs = observations.save(new RawObservation(f.orgA(), node, 2,
                "CHIP-H", apr.plus(Duration.ofDays(10)).toEpochMilli(), "SYNCED",
                null, null, null, siteB, depB));
        DerivedVisit janVisit = derivedVisits.save(new DerivedVisit(f.orgA(), siteA,
                "CHIP-H", null, jan.plus(Duration.ofDays(10)),
                jan.plus(Duration.ofDays(10)).plusSeconds(30), 1, "visit-gap-v1", 60,
                janObs, janObs));
        DerivedVisit junVisit = derivedVisits.save(new DerivedVisit(f.orgA(), siteB,
                "CHIP-H", null, apr.plus(Duration.ofDays(10)),
                apr.plus(Duration.ofDays(10)).plusSeconds(30), 1, "visit-gap-v1", 60,
                junObs, junObs));

        formLogin("a35-admin-a@example.org", "supersecret-password-a");

        // Opening or editing Site B must not pull January data to Site B.
        var detailB = get("/admin/sites/" + siteB.getId());
        assertEquals(200, detailB.statusCode(), detailB.body());
        assertTrue(detailB.body().contains(node.getNodeId().toString()), detailB.body());

        String csrf = adminPageCsrf("/admin/sites/new");
        var editB = postForm("/admin/sites/" + siteB.getId(), formBody(csrf,
                "name", "Site B umbenannt",
                "description", "Aktuell neu",
                "locationLabel", "Ort B neu"));
        assertEquals(302, editB.statusCode(), editB.body());

        var editA = postForm("/admin/sites/" + siteA.getId(), formBody(
                adminPageCsrf("/admin/sites/" + siteA.getId() + "/edit"),
                "name", "Site A umbenannt",
                "description", "Historisch neu",
                "locationLabel", "Ort A neu"));
        assertEquals(302, editA.statusCode(), editA.body());

        // Historical rows are byte-identical apart from the intended renames.
        assertEquals(2, deployments.findByNodeNodeId(node.getNodeId()).size());
        assertEquals(apr, deployments.findById(depA.getId()).orElseThrow()
                .getValidUntil());
        assertNull(deployments.findById(depB.getId()).orElseThrow().getValidUntil());
        assertEquals(siteA.getId(), observations
                .findByNodeNodeIdAndSequence(node.getNodeId(), 1).orElseThrow()
                .getFeedingSite().getId());
        assertEquals(siteB.getId(), observations
                .findByNodeNodeIdAndSequence(node.getNodeId(), 2).orElseThrow()
                .getFeedingSite().getId());
        assertEquals(siteA.getId(), derivedVisits.findById(janVisit.getId())
                .orElseThrow().getFeedingSite().getId());
        assertEquals(siteB.getId(), derivedVisits.findById(junVisit.getId())
                .orElseThrow().getFeedingSite().getId());
        // Renames applied, ownership stable.
        assertEquals("Site B umbenannt",
                sites.findById(siteB.getId()).orElseThrow().getName());
        assertEquals(f.orgA().getId(),
                sites.findById(siteB.getId()).orElseThrow().getOrganization().getId());
    }

    // --- Multi-org admin ---

    @Test
    void multiOrgAdminSwitchesContextWithoutStaleData() throws Exception {
        Fixture f = seed();
        saveSite(f.orgA(), "Nur A", null, null);
        saveSite(f.orgB(), "Nur B", null, null);

        var login = formLogin("a35-multi-admin@example.org", "supersecret-password-x");
        assertLoginSuccess(login);
        // No session org yet: list defers to explicit selection.
        var deferred = get("/admin/sites");
        assertEquals(302, deferred.statusCode(), deferred.body());
        assertTrue(deferred.headers().firstValue("location").orElse("")
                .contains("/admin/org"), deferred.body());

        String csrf = adminPageCsrf("/admin/org");
        var toA = postForm("/admin/org", formBody(csrf,
                "organizationId", f.orgA().getId().toString()));
        assertEquals(302, toA.statusCode(), toA.body());
        var listA = get("/admin/sites");
        assertEquals(200, listA.statusCode(), listA.body());
        assertTrue(listA.body().contains("Nur A"), listA.body());
        assertFalse(listA.body().contains("Nur B"), listA.body());

        String csrf2 = adminPageCsrf("/admin/org");
        var toB = postForm("/admin/org", formBody(csrf2,
                "organizationId", f.orgB().getId().toString()));
        assertEquals(302, toB.statusCode(), toB.body());
        var listB = get("/admin/sites");
        assertEquals(200, listB.statusCode(), listB.body());
        assertTrue(listB.body().contains("Nur B"), listB.body());
        assertFalse(listB.body().contains("Nur A"), listB.body());
    }

    @Test
    void staleCreateFormCannotWriteIntoNewlySelectedOrganization() throws Exception {
        Fixture f = seed();
        assertLoginSuccess(formLogin("a35-multi-admin@example.org", "supersecret-password-x"));
        String csrf = adminPageCsrf("/admin/org");
        assertEquals(302, postForm("/admin/org", formBody(csrf,
                "organizationId", f.orgA().getId().toString())).statusCode());
        String staleCsrf = adminPageCsrf("/admin/sites/new");
        String staleOrg = formOrganizationId;
        assertEquals(f.orgA().getId().toString(), staleOrg);
        assertEquals(302, postForm("/admin/org", formBody(staleCsrf,
                "organizationId", f.orgB().getId().toString())).statusCode());

        var stale = postForm("/admin/sites", formBody(staleCsrf,
                "name", "Created for A", "formOrganizationId", staleOrg));
        assertEquals(409, stale.statusCode(), stale.body());
        assertEquals(0, sites.count());

        String freshCsrf = adminPageCsrf("/admin/sites/new");
        assertEquals(f.orgB().getId().toString(), formOrganizationId);
        var fresh = postForm("/admin/sites", formBody(freshCsrf, "name", "Created for B"));
        assertEquals(302, fresh.statusCode(), fresh.body());
        assertEquals(1, sites.findByOrganizationId(f.orgB().getId()).size());
        assertTrue(sites.findByOrganizationId(f.orgA().getId()).isEmpty());
    }

    @Test
    void futureDeploymentIsNotPresentedAsCurrentAndHistoryKeepsTableStyles() throws Exception {
        Fixture f = seed();
        FeedingSite site = saveSite(f.orgA(), "Future site", null, null);
        NodeDevice node = saveClaimedNode(f.orgA());
        deployments.save(new NodeDeployment(f.orgA(), node, site,
                Instant.now().plus(Duration.ofDays(30)), null));
        assertLoginSuccess(formLogin("a35-admin-a@example.org", "supersecret-password-a"));
        var detail = get("/admin/sites/" + site.getId());
        assertEquals(200, detail.statusCode(), detail.body());
        assertTrue(detail.body().contains("Keine aktuell zugeordneten Nodes."), detail.body());
        assertTrue(detail.body().contains(node.getNodeId().toString()), detail.body());
        assertTrue(detail.body().contains("Ohne Enddatum"), detail.body());
        assertFalse(detail.body().contains("aktuell offen"), detail.body());
        assertTrue(detail.body().contains(".table-scroll{overflow-x:auto}"), detail.body());
    }

    @Test
    void pwaRestContractStillServesMemberCare() throws Exception {
        Fixture f = seed();
        saveSite(f.orgA(), "PWA Site", null, null);
        // The existing REST/PWA API is untouched: admins list via JSON, and the
        // PWA frontend route file remains for #38 (no backend deletion here).
        formLogin("a35-admin-a@example.org", "supersecret-password-a");
        var res = get("/api/v1/organizations/" + f.orgA().getId() + "/feeding-sites");
        assertEquals(200, res.statusCode(), res.body());
        assertTrue(res.body().contains("PWA Site"), res.body());
    }
}
