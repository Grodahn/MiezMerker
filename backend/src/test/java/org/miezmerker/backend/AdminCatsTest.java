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
import org.miezmerker.backend.domain.Cat;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP acceptance tests for #37, with session login and CSRF. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Tag("admin")
@Tag("auth")
@Tag("visits")
class AdminCatsTest {
    @Value("${local.server.port}") int port;
    String formOrganizationId;

    @Autowired AppUserRepository users;
    @Autowired OrganizationRepository organizations;
    @Autowired MembershipRepository memberships;
    @Autowired NodeRepository nodes;
    @Autowired RawObservationRepository observations;
    @Autowired DerivedVisitRepository derivedVisits;
    @Autowired NodeDeploymentRepository deployments;
    @Autowired CatRepository cats;
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
        memberships.save(new OrganizationMembership(orgA, pendingA, MembershipRole.ADMIN,
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

    Cat cat(Organization org, String chip, String name) {
        return cats.save(new Cat(org, chip, name, "aktiv", "Notiz " + name));
    }
    void loginA() throws Exception {
        assertLoginSuccess(formLogin("a35-admin-a@example.org", "supersecret-password-a"));
    }
    @Test
    void accessAndEmptyState() throws Exception {
        seed();
        for (String path : new String[]{"/admin/cats", "/admin/cats/from-chip?chipId=ABC",
                "/admin/cats/" + UUID.randomUUID(), "/admin/cats/" + UUID.randomUUID() + "/edit"}) {
            var r = get(path); assertEquals(302, r.statusCode());
            assertTrue(r.headers().firstValue("location").orElse("").contains("/admin/login"));
        }
        for (String[] account : new String[][]{{"member", "m"}, {"pending", "p"}, {"disabled", "d"}}) {
            http(); formLogin("a35-" + account[0] + "-a@example.org", "supersecret-password-" + account[1]);
            assertEquals(403, get("/admin/cats").statusCode());
            assertEquals(403, get("/admin/cats/from-chip?chipId=ABC").statusCode());
        }
        http(); formLogin("a35-locked@example.org", "supersecret-password-l");
        assertEquals(302, get("/admin/cats").statusCode());
        http(); loginA();
        var r = get("/admin/cats"); assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.body().contains("Noch keine Katzen")); assertTrue(r.body().contains("Keine unbekannten Chips"));
    }
    @Test
    void listFieldsAndForeignIdsAreScoped() throws Exception {
        var f = seed(); var own = cat(f.orgA(), "A-CHIP", "Minka"); var foreign = cat(f.orgB(), "SECRET-B", "Secret Cat");
        var node = saveClaimedNode(f.orgB());
        observations.save(new RawObservation(f.orgB(), node, 1, "FOREIGN-UNKNOWN", null, "UNKNOWN", null, null, null, null, null));
        loginA(); var r = get("/admin/cats"); assertEquals(200, r.statusCode(), r.body());
        for (String text : new String[]{"Minka", "A-CHIP", "aktiv", "Notiz Minka"}) assertTrue(r.body().contains(text), r.body());
        for (String text : new String[]{"SECRET-B", "Secret Cat", "FOREIGN-UNKNOWN"}) assertFalse(r.body().contains(text));
        assertEquals(200, get("/admin/cats/" + own.getId()).statusCode());
        for (String suffix : new String[]{"", "/edit"}) assertEquals(404, get("/admin/cats/" + foreign.getId() + suffix).statusCode());
        String token = adminPageCsrf("/admin/cats");
        assertEquals(404, postForm("/admin/cats/" + foreign.getId(), formBody(token, "name", "Hack", "formOrganizationId", f.orgA().getId().toString())).statusCode());
        assertEquals("Secret Cat", cats.findById(foreign.getId()).orElseThrow().getName());
    }
    @Test
    void editingValidationCsrfAndImmutableIdentity() throws Exception {
        var f = seed(); var c = cat(f.orgA(), "A-CHIP", "Minka"); loginA();
        String path = "/admin/cats/" + c.getId(); String token = adminPageCsrf(path + "/edit");
        assertEquals(403, postForm(path, formBody(null, "name", "Changed")).statusCode());
        for (String[] invalid : new String[][]{{"name", "x".repeat(256)}, {"status", "x".repeat(65)}, {"notes", "x".repeat(2001)}}) {
            var r = postForm(path, formBody(token, invalid)); assertEquals(200, r.statusCode(), r.body());
            assertTrue(r.body().contains("Bitte Eingaben prüfen"));
            assertEquals("Minka", cats.findById(c.getId()).orElseThrow().getName());
        }
        var r = postForm(path, formBody(token, "name", "  Neue Minka  ", "status", "vermisst", "notes", "Neue Notiz", "chipId", "HACK"));
        assertEquals(302, r.statusCode(), r.body()); var saved = cats.findById(c.getId()).orElseThrow();
        assertEquals("Neue Minka", saved.getName()); assertEquals("vermisst", saved.getStatus()); assertEquals("Neue Notiz", saved.getNotes());
        assertEquals("A-CHIP", saved.getChipId());
        assertTrue(cats.findByIdAndOrganizationId(c.getId(), f.orgA().getId()).isPresent());
        assertFalse(cats.findByIdAndOrganizationId(c.getId(), f.orgB().getId()).isPresent());
        var detail = get(path); assertTrue(detail.body().contains("Neue Notiz"));
        assertEquals(302, postForm(path, formBody(token, "name", "", "status", "", "notes", "")).statusCode());
        assertNull(cats.findById(c.getId()).orElseThrow().getNotes());
    }
    @Test
    void unknownChipCreationHonestClockAndForeignClaim() throws Exception {
        var f = seed(); var n = saveClaimedNode(f.orgA()); var foreign = saveClaimedNode(f.orgB());
        observations.save(new RawObservation(f.orgA(), n, 1, "unknown-a", null, "UNKNOWN", null, null, null, null, null));
        observations.save(new RawObservation(f.orgB(), foreign, 1, "UNKNOWN-B", null, "UNKNOWN", null, null, null, null, null));
        loginA(); var list = get("/admin/cats"); assertEquals(200, list.statusCode(), list.body());
        assertTrue(list.body().contains("UNKNOWN-A")); assertTrue(list.body().contains("unbekannt – keine verlässliche Uhrzeit"));
        assertTrue(list.body().contains("Letzter Server-Empfang")); assertFalse(list.body().contains("2026-05-28T"));
        String token = adminPageCsrf("/admin/cats/from-chip?chipId=UNKNOWN-A");
        assertEquals(403, postForm("/admin/cats/from-chip", formBody(null, "chipId", "UNKNOWN-A")).statusCode());
        assertEquals(404, get("/admin/cats/from-chip?chipId=UNKNOWN-B").statusCode());
        assertEquals(404, postForm("/admin/cats/from-chip", formBody(token, "chipId", "UNKNOWN-B", "name", "Foreign")).statusCode());
        var invalid = postForm("/admin/cats/from-chip", formBody(token, "chipId", "UNKNOWN-A", "name", "x".repeat(256)));
        assertEquals(200, invalid.statusCode()); assertTrue(invalid.body().contains("Bitte Eingaben prüfen"));
        var created = postForm("/admin/cats/from-chip", formBody(token, "chipId", " unknown-a ", "name", "New Cat"));
        assertEquals(302, created.statusCode(), created.body());
        var c = cats.findByOrganizationIdAndChipId(f.orgA().getId(), "UNKNOWN-A").orElseThrow();
        assertEquals("New Cat", c.getName()); assertTrue(cats.findByOrganizationId(f.orgB().getId()).isEmpty());
        var detail = get("/admin/cats/" + c.getId()); assertEquals(200, detail.statusCode(), detail.body());
        assertTrue(detail.body().contains("UNKNOWN wird nicht als präzise Sichtung"));
        assertTrue(detail.body().contains("unbekannt – keine verlässliche Uhrzeit"));
        assertFalse(get("/admin/cats").body().contains("Katze anlegen</a>"));
        assertEquals(404, postForm("/admin/cats/from-chip", formBody(token, "chipId", "UNKNOWN-A")).statusCode());
    }
    @Test
    void reliableSightingFrozenSiteAndPersistedVisitsSurviveMove() throws Exception {
        var f = seed(); var c = cat(f.orgA(), "HISTORY", "History Cat"); var n = saveClaimedNode(f.orgA());
        var oldSite = saveSite(f.orgA(), "Original Site", null, null); var newSite = saveSite(f.orgA(), "New Deployment Site", null, null);
        Instant time = Instant.parse("2026-01-01T12:00:00Z");
        var old = deployments.save(new NodeDeployment(f.orgA(), n, oldSite, time.minusSeconds(60), time.plusSeconds(60)));
        deployments.save(new NodeDeployment(f.orgA(), n, newSite, time.plusSeconds(60), null));
        var o = observations.save(new RawObservation(f.orgA(), n, 1, "HISTORY", time.toEpochMilli(), "SYNCED", null, null, null, oldSite, old));
        // Latest receipt has UNKNOWN time and no frozen site; never substitute current deployment.
        observations.save(new RawObservation(f.orgA(), n, 2, "HISTORY", null, "UNKNOWN", null, null, null, null, null));
        derivedVisits.save(new DerivedVisit(f.orgA(), oldSite, "HISTORY", c, time, time.plusSeconds(10), 1, "visit-gap-v1", 300, o, o));
        loginA(); var list = get("/admin/cats"); assertEquals(200, list.statusCode(), list.body());
        assertTrue(list.body().contains(time.toString())); assertTrue(list.body().contains("Original Site"));
        assertFalse(list.body().contains("New Deployment Site"));
        var detail = get("/admin/cats/" + c.getId()); assertEquals(200, detail.statusCode(), detail.body());
        assertTrue(detail.body().contains("Original Site")); assertTrue(detail.body().contains(time.plusSeconds(10).toString()));
        assertFalse(detail.body().contains("New Deployment Site")); assertEquals(1, derivedVisits.count());
        assertEquals(2, observations.count());
    }
    @Test
    void organizationSwitchHasNoStaleDataAndRejectsOldForms() throws Exception {
        var f = seed(); var a = cat(f.orgA(), "A-CHIP", "Only A"); cat(f.orgB(), "B-CHIP", "Only B");
        // Same chip can be observed independently in both tenants. Old A form must not create in B.
        for (Organization org : new Organization[]{f.orgA(), f.orgB()}) {
            var n = saveClaimedNode(org);
            observations.save(new RawObservation(org, n, 1, "SHARED-UNKNOWN", null, "UNKNOWN", null, null, null, null, null));
        }
        assertLoginSuccess(formLogin("a35-multi-admin@example.org", "supersecret-password-x"));
        assertEquals(302, get("/admin/cats").statusCode());
        String token = adminPageCsrf("/admin/org");
        assertEquals(302, postForm("/admin/org", formBody(token, "organizationId", f.orgA().getId().toString())).statusCode());
        var list = get("/admin/cats"); assertTrue(list.body().contains("Only A")); assertFalse(list.body().contains("Only B"));
        token = adminPageCsrf("/admin/cats/from-chip?chipId=SHARED-UNKNOWN");
        assertEquals(302, postForm("/admin/org", formBody(token, "organizationId", f.orgB().getId().toString())).statusCode());
        list = get("/admin/cats"); assertTrue(list.body().contains("Only B")); assertFalse(list.body().contains("Only A"));
        assertEquals(404, get("/admin/cats/" + a.getId()).statusCode());
        assertEquals(409, postForm("/admin/cats/from-chip", formBody(token, "chipId", "SHARED-UNKNOWN", "formOrganizationId", f.orgA().getId().toString())).statusCode());
        assertTrue(cats.findByOrganizationIdAndChipId(f.orgB().getId(), "SHARED-UNKNOWN").isEmpty());
    }
    @Test
    void listQueryCountDoesNotGrowPerCat() throws Exception {
        var f = seed(); var site = saveSite(f.orgA(), "Batch Site", null, null);
        var node = saveClaimedNode(f.orgA()); cat(f.orgA(), "BATCH-0", "Batch 0");
        observations.save(new RawObservation(f.orgA(), node, 1, "BATCH-0", 1000L, "KNOWN", null, null, null, site, null));
        loginA();
        var statistics = entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        try {
            statistics.clear(); assertEquals(200, get("/admin/cats").statusCode());
            long baseline = statistics.getPrepareStatementCount();
            for (int i = 1; i <= 25; i++) {
                cat(f.orgA(), "BATCH-" + i, "Batch " + i);
                observations.save(new RawObservation(f.orgA(), node, i + 1, "BATCH-" + i, 1000L,
                        "KNOWN", null, null, null, site, null));
            }
            statistics.clear(); var list = get("/admin/cats"); assertEquals(200, list.statusCode(), list.body());
            assertTrue(list.body().contains("Batch 25"));
            assertTrue(statistics.getPrepareStatementCount() <= baseline + 2,
                    "SQL count must stay constant: baseline=" + baseline + ", actual=" + statistics.getPrepareStatementCount());
        } finally { statistics.setStatisticsEnabled(false); }
    }
    @Test
    void invalidClockIsUncertainAndLatestReliableSiteIsNeverGuessed() throws Exception {
        var f = seed(); var c = cat(f.orgA(), "INVALID", "Invalid Clock Cat"); var node = saveClaimedNode(f.orgA());
        var site = saveSite(f.orgA(), "Old Site", null, null);
        observations.save(new RawObservation(f.orgA(), node, 1, "INVALID", 0L, "KNOWN", null, null, null, null, null));
        observations.save(new RawObservation(f.orgA(), node, 2, "INVALID", Long.MAX_VALUE, "SYNCED", null, null, null, null, null));
        loginA(); var detail = get("/admin/cats/" + c.getId()); assertEquals(200, detail.statusCode(), detail.body());
        assertTrue(detail.body().contains("unbekannt – keine verlässliche Uhrzeit"));
        assertTrue(detail.body().contains("Uhrzeit unbekannt/ungültig"));
        observations.save(new RawObservation(f.orgA(), node, 3, "INVALID", 1000L, "RTC_ONLY", null, null, null, site, null));
        observations.save(new RawObservation(f.orgA(), node, 4, "INVALID", 2000L, "KNOWN", null, null, null, null, null));
        var list = get("/admin/cats"); assertEquals(200, list.statusCode(), list.body());
        assertTrue(list.body().contains("1970-01-01T00:00:02Z"));
        assertFalse(list.body().contains("Old Site"), "Earlier site must not be shown as the latest reliable site");
        assertTrue(get("/admin/cats/" + c.getId()).body().contains("Old Site"), "Earlier site remains in known site history");
    }

    @Test
    void claimingChipReusesPersistedHistoryWithoutRecomputingOrLeakingOtherTenant() throws Exception {
        var f = seed(); Instant time = Instant.parse("2026-02-01T12:00:00Z");
        for (Organization org : new Organization[]{f.orgA(), f.orgB()}) {
            var n = saveClaimedNode(org); var site = saveSite(org,
                    org.getId().equals(f.orgA().getId()) ? "Own History Site" : "Foreign History Site", null, null);
            var o = observations.save(new RawObservation(org, n, 1, "SAME-CHIP", time.toEpochMilli(),
                    "SYNCED", null, null, null, site, null));
            derivedVisits.save(new DerivedVisit(org, site, "SAME-CHIP", null, time, time.plusSeconds(30),
                    1, "visit-gap-v1", 300, o, o));
        }
        cat(f.orgB(), "SAME-CHIP", "Foreign Same Chip Cat");
        loginA(); String token = adminPageCsrf("/admin/cats/from-chip?chipId=SAME-CHIP");
        assertEquals(302, postForm("/admin/cats/from-chip", formBody(token, "chipId", "SAME-CHIP", "name", "Own Same Chip Cat")).statusCode());
        var c = cats.findByOrganizationIdAndChipId(f.orgA().getId(), "SAME-CHIP").orElseThrow();
        var detail = get("/admin/cats/" + c.getId()); assertEquals(200, detail.statusCode(), detail.body());
        assertTrue(detail.body().contains("Own History Site")); assertTrue(detail.body().contains(time.plusSeconds(30).toString()));
        assertFalse(detail.body().contains("Foreign History Site")); assertFalse(detail.body().contains("Foreign Same Chip Cat"));
        assertEquals(2, derivedVisits.count());
        assertEquals("Foreign Same Chip Cat", cats.findByOrganizationIdAndChipId(f.orgB().getId(), "SAME-CHIP").orElseThrow().getName());
    }

    @Test
    void catDetailSummarizesVisitedSitesWithLatestReliableVisit() throws Exception {
        var f = seed(); var c = cat(f.orgA(), "SITES", "Sites Cat"); var n = saveClaimedNode(f.orgA());
        var oldSite = saveSite(f.orgA(), "Old Frozen Site", null, null);
        var newSite = saveSite(f.orgA(), "New Frozen Site", null, null);
        Instant time = Instant.parse("2026-03-01T12:00:00Z");
        var firstDeployment = new NodeDeployment(f.orgA(), n, oldSite, time.minusSeconds(60), time.plusSeconds(60));
        deployments.save(firstDeployment);
        deployments.save(new NodeDeployment(f.orgA(), n, newSite, time.plusSeconds(60), null));
        var first = observations.save(new RawObservation(f.orgA(), n, 1, "SITES", time.toEpochMilli(),
                "SYNCED", null, null, null, oldSite, firstDeployment));
        var later = observations.save(new RawObservation(f.orgA(), n, 2, "SITES",
                time.plusSeconds(300).toEpochMilli(), "SYNCED", null, null, null, newSite, null));
        derivedVisits.save(new DerivedVisit(f.orgA(), oldSite, "SITES", c, time, time.plusSeconds(10),
                1, "visit-gap-v1", 300, first, first));
        derivedVisits.save(new DerivedVisit(f.orgA(), newSite, "SITES", c, time.plusSeconds(300),
                time.plusSeconds(310), 1, "visit-gap-v1", 300, later, later));
        loginA();
        var detail = get("/admin/cats/" + c.getId()); assertEquals(200, detail.statusCode(), detail.body());
        // Feeding-site summary: each site once with its latest reliable visit,
        // frozen historical attribution intact despite the later node move.
        assertTrue(detail.body().contains("Besuchte Futterstellen"));
        assertTrue(detail.body().contains("Old Frozen Site"));
        assertTrue(detail.body().contains(time.toString()));
        assertTrue(detail.body().contains("New Frozen Site"));
        assertTrue(detail.body().contains(time.plusSeconds(300).toString()));
        // Pageable visit list with deterministic newest-first order.
        assertTrue(detail.body().contains("Besuche dieses Chips"));
        assertTrue(detail.body().contains("Ab Eintrag 1"));
        assertTrue(detail.body().contains(time.plusSeconds(310).toString()));
    }

    @Test
    void catDetailVisitPagingIsBoundedAndRejectsInvalidPages() throws Exception {
        var f = seed(); var c = cat(f.orgA(), "PAGED", "Paged Cat"); var n = saveClaimedNode(f.orgA());
        var site = saveSite(f.orgA(), "Paged Site", null, null);
        Instant time = Instant.parse("2026-04-01T12:00:00Z");
        var o = observations.save(new RawObservation(f.orgA(), n, 1, "PAGED", time.toEpochMilli(),
                "SYNCED", null, null, null, site, null));
        for (int i = 0; i < 25; i++) {
            derivedVisits.save(new DerivedVisit(f.orgA(), site, "PAGED", c, time.plusSeconds(i * 600L),
                    time.plusSeconds(i * 600L + 10), 1, "visit-gap-v1", 300, o, o));
        }
        loginA();
        var first = get("/admin/cats/" + c.getId()); assertEquals(200, first.statusCode(), first.body());
        assertTrue(first.body().contains("Ab Eintrag 1"));
        assertTrue(first.body().contains("Nächste Seite"));
        assertTrue(first.body().contains(time.plusSeconds(24 * 600L).toString()));
        // The oldest visit ends at a time shown nowhere else on the first page.
        assertFalse(first.body().contains(time.plusSeconds(10).toString()),
                "Oldest visit must not be on the first page");

        var second = get("/admin/cats/" + c.getId() + "?offset=20&limit=20");
        assertEquals(200, second.statusCode(), second.body());
        assertTrue(second.body().contains("Ab Eintrag 21"));
        assertTrue(second.body().contains("Vorherige Seite"));
        assertTrue(second.body().contains(time.plusSeconds(10).toString()));
        assertFalse(second.body().contains("Nächste Seite"));

        assertEquals(400, get("/admin/cats/" + c.getId() + "?limit=0").statusCode());
        assertEquals(400, get("/admin/cats/" + c.getId() + "?offset=-1").statusCode());
    }

}
