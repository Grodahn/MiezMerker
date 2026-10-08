package org.miezmerker.backend;

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
import org.miezmerker.backend.domain.Cat;
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

/** Real HTTP acceptance coverage for issue #36. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AdminObservationsVisitsTest {
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

    @Autowired org.miezmerker.backend.service.VisitAggregationService aggregation;
    @Autowired org.miezmerker.backend.service.ObservationIngestService ingest;
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


    record Data(Fixture f, FeedingSite oldSite, FeedingSite newSite, FeedingSite foreignSite,
            NodeDevice node, NodeDevice foreignNode, RawObservation old, RawObservation recent,
            RawObservation unknown, RawObservation foreign, DerivedVisit foreignVisit) {}

    Data data() {
        Fixture f = seed();
        var oldSite = saveSite(f.orgA(), "Historical Alpha", null, null);
        var newSite = saveSite(f.orgA(), "Current Beta", null, null);
        var foreignSite = saveSite(f.orgB(), "Secret Foreign Site", null, null);
        var n = saveClaimedNode(f.orgA());
        var foreignNode = saveClaimedNode(f.orgB());
        var t = Instant.parse("2026-04-01T00:00:00Z");
        var oldDeployment = deployments.save(new NodeDeployment(f.orgA(), n, oldSite,
                Instant.parse("2026-01-01T00:00:00Z"), t));
        deployments.save(new NodeDeployment(f.orgA(), n, newSite, t, null));
        var ingested = ingest.ingestBatch(f.adminA().getId(), f.orgA().getId(), java.util.List.of(
                new org.miezmerker.backend.service.ObservationIngestService.IngestItem(
                        n.getNodeId(), 9007199254740993L, "OLDCHIP", t.minusSeconds(60).toEpochMilli(),
                        "SYNCED", "00000000-0000-0000-0000-000000000001", 12345L, 7),
                new org.miezmerker.backend.service.ObservationIngestService.IngestItem(
                        n.getNodeId(), 9007199254740994L, "NEWCHIP", t.plusSeconds(60).toEpochMilli(),
                        "RTC_ONLY", "00000000-0000-0000-0000-000000000002", 23456L, 8),
                new org.miezmerker.backend.service.ObservationIngestService.IngestItem(
                        n.getNodeId(), 9007199254740995L, "UNKNOWNCHIP", null,
                        "UNKNOWN", "00000000-0000-0000-0000-000000000003", 34567L, 9)));
        assertEquals(3, ingested.inserted(), ingested.results().toString());
        observations.save(new RawObservation(f.orgA(), n, 9007199254740996L, "OLDCHIP",
                t.minusSeconds(30).toEpochMilli(), "SYNCED", "00000000-0000-0000-0000-000000000001", 12375L, 7,
                oldSite, oldDeployment));
        var own = observations.findAll();
        RawObservation old = own.stream().filter(o -> o.getSequence() == 9007199254740993L).findFirst().orElseThrow();
        RawObservation recent = own.stream().filter(o -> o.getChipId().equals("NEWCHIP")).findFirst().orElseThrow();
        RawObservation unknown = own.stream().filter(o -> o.getChipId().equals("UNKNOWNCHIP")).findFirst().orElseThrow();
        var foreign = observations.save(new RawObservation(f.orgB(), foreignNode, 1,
                "SECRETCHIP", t.toEpochMilli(), "SYNCED", "secret-boot", 1L, 1, foreignSite, null));
        cats.save(new Cat(f.orgA(), "OLDCHIP", "Old Cat", "ACTIVE", null));
        aggregation.recompute(f.adminA().getId(), f.orgA().getId(), null, null);
        var foreignVisit = derivedVisits.save(new DerivedVisit(f.orgB(), foreignSite, "SECRETCHIP",
                null, t, t.plusSeconds(30), 2, "visit-gap-v1", 120, foreign, foreign));
        return new Data(f, oldSite, newSite, foreignSite, n, foreignNode, old, recent, unknown,
                foreign, foreignVisit);
    }

    void loginA() throws Exception {
        assertLoginSuccess(formLogin("a35-admin-a@example.org", "supersecret-password-a"));
    }
    String page(String path) throws Exception {
        var r = get(path);
        assertEquals(200, r.statusCode(), r.body());
        assertFalse(r.body().contains("SECRETCHIP"));
        assertFalse(r.body().contains("Secret Foreign Site"));
        return r.body();
    }
    String table(String html) {
        int start = html.indexOf("<tbody>");
        return start < 0 ? "" : html.substring(start, html.indexOf("</tbody>", start));
    }

    @Test
    void accessRequiresActiveAdmin() throws Exception {
        Fixture f = seed();
        for (String route : new String[]{"/admin/observations", "/admin/visits"}) {
            var r = get(route);
            assertEquals(302, r.statusCode());
            assertTrue(r.headers().firstValue("location").orElse("").contains("/admin/login"));
        }
        for (String[] account : new String[][]{{"member", "m"}, {"pending", "p"}, {"disabled", "d"}}) {
            http();
            formLogin("a35-" + account[0] + "-a@example.org", "supersecret-password-" + account[1]);
            assertEquals(403, get("/admin/observations").statusCode());
            assertEquals(403, get("/admin/visits").statusCode());
            var token = new tools.jackson.databind.ObjectMapper().readTree(get("/api/v1/auth/csrf").body()).get("token").asText();
            assertEquals(403, postForm("/admin/visits/recompute", formBody(token,
                    "formOrganizationId", f.orgA().getId().toString())).statusCode());
        }
        http(); loginA(); page("/admin/observations"); page("/admin/visits");
    }

    @Test
    void rawFidelityUnknownAndHistoricalAssociation() throws Exception {
        var d = data(); loginA();
        String html = page("/admin/observations?organizationId=" + d.f().orgB().getId());
        assertTrue(html.contains("9007199254740993"));
        assertTrue(html.contains(d.old().getObservedAtMs().toString()));
        assertTrue(html.contains(d.old().getReceivedAt().toString()));
        assertTrue(html.contains("SYNCED"));
        assertTrue(html.contains("00000000-0000-0000-0000-000000000001"));
        assertTrue(html.contains("12345"));
        assertTrue(html.contains("Boot counter: 7"));
        String unknown = table(page("/admin/observations?chipId=UNKNOWNCHIP"));
        assertTrue(unknown.contains("Uhrzeit unbekannt"));
        assertTrue(unknown.contains("RTC-Rohwert (ms): nicht vorhanden"));
        assertTrue(unknown.contains(d.unknown().getReceivedAt().toString()));
        assertFalse(unknown.contains("1970-01-01"));
        assertTrue(unknown.contains("UNKNOWN"));
        String old = table(page("/admin/observations?chipId=OLDCHIP"));
        assertTrue(old.contains("Historical Alpha"));
        assertFalse(old.contains("Current Beta"));
        String recent = table(page("/admin/observations?chipId=NEWCHIP"));
        assertTrue(recent.contains("Current Beta"));
        assertFalse(recent.contains("Historical Alpha"));
    }

    @Test
    void persistedInvalidRawClockValueIsNeverRewritten() throws Exception {
        var d = data();
        // Zero is schema-valid legacy data, but is not a reliable observation time.
        observations.save(new RawObservation(d.f().orgA(), d.node(), 888, "INVALIDCLOCK",
                0L, "SYNCED", "legacy", 456L, 3, null, null));
        loginA();
        String invalid = table(page("/admin/observations?chipId=INVALIDCLOCK"));
        assertTrue(invalid.contains("Ungültige Zeit (Rohwert: 0)"));
        assertTrue(invalid.contains("RTC-Rohwert (ms): 0"));
        assertFalse(invalid.contains("1970-01-01"));
    }

    @Test
    void rawFiltersPaginationAndNeutralForeignIds() throws Exception {
        var d = data(); loginA();
        String first = table(page("/admin/observations?limit=1"));
        String second = table(page("/admin/observations?limit=1&offset=1"));
        assertFalse(first.equals(second));
        assertEquals(first, table(page("/admin/observations?limit=1")));
        assertTrue(page("/admin/observations?limit=1").contains("Nächste Seite"));
        assertTrue(page("/admin/observations?limit=1&offset=1").contains("Vorherige Seite"));
        String site = table(page("/admin/observations?feedingSiteId=" + d.oldSite().getId()));
        assertTrue(site.contains("OLDCHIP")); assertFalse(site.contains("NEWCHIP"));
        assertTrue(table(page("/admin/observations?nodeId=" + d.node().getNodeId())).contains("OLDCHIP"));
        assertTrue(table(page("/admin/observations?chipId=%20oldchip%20")).contains("OLDCHIP"));
        assertFalse(table(page("/admin/observations?chipId=OLDCHIP")).contains("NEWCHIP"));
        assertTrue(table(page("/admin/observations?sequence=9007199254740994")).contains("NEWCHIP"));
        String time = table(page("/admin/observations?from=2026-04-01T00:00&to=2026-04-02T00:00"));
        assertTrue(time.contains("NEWCHIP")); assertFalse(time.contains("OLDCHIP"));
        for (String query : new String[]{"feedingSiteId=" + d.foreignSite().getId(),
                "nodeId=" + d.foreignNode().getNodeId(), "nodeId=" + UUID.randomUUID(),
                "chipId=missing", "offset=500"}) {
            assertTrue(page("/admin/observations?" + query).contains("Keine Ergebnisse"));
        }
        for (String query : new String[]{"feedingSiteId=invalid", "sequence=-1", "limit=101",
                "offset=-1", "from=invalid", "from=2026-04-02T00:00&to=2026-04-01T00:00"}) {
            assertTrue(page("/admin/observations?" + query).contains("Filter ungültig"));
        }
    }

    @Test
    void visitsDisplayPersistedValuesAndFilters() throws Exception {
        var d = data(); loginA();
        String old = table(page("/admin/visits?chipId=OLDCHIP"));
        assertTrue(old.contains(d.old().getObservedAtMs() == null ? "bad" :
                Instant.ofEpochMilli(d.old().getObservedAtMs()).toString()));
        assertTrue(old.contains("PT30S"));
        assertTrue(old.contains("2026-03-31T23:59:30Z"));
        assertTrue(old.contains("Old Cat"));
        assertTrue(old.contains("Historical Alpha"));
        assertFalse(old.contains("Current Beta"));
        assertTrue(old.contains("visit-gap-v1"));
        assertTrue(old.contains("Gap: 60 s"));
        assertTrue(old.contains(d.old().getId().toString()));
        assertTrue(old.contains("<td>2</td>"));
        assertTrue(table(page("/admin/visits?chipId=NEWCHIP")).contains("Noch keine Katze zugeordnet"));
        assertTrue(page("/admin/visits?limit=1").contains("Nächste Seite"));
        assertFalse(table(page("/admin/visits?limit=1")).equals(table(page("/admin/visits?limit=1&offset=1"))));
        assertFalse(table(page("/admin/visits?feedingSiteId=" + d.oldSite().getId())).contains("NEWCHIP"));
        assertTrue(page("/admin/visits?feedingSiteId=" + d.foreignSite().getId()).contains("Keine Ergebnisse"));
        assertFalse(table(page("/admin/visits?from=2026-04-01T00:00&to=2026-04-02T00:00")).contains("OLDCHIP"));
    }

    @Test
    void recomputeRequiresCsrfUsesActiveTenantAndPreservesRawAndForeignVisits() throws Exception {
        var d = data(); loginA();
        assertEquals(403, postForm("/admin/visits/recompute", "formOrganizationId=" + d.f().orgA().getId()).statusCode());
        String csrf = adminPageCsrf("/admin/visits");
        assertEquals(302, postForm("/admin/visits/recompute", formBody(csrf,
                "formOrganizationId", d.f().orgA().getId().toString())).statusCode());
        assertTrue(page("/admin/visits").contains("Besuche neu berechnet: 2"));
        assertTrue(derivedVisits.findById(d.foreignVisit().getId()).isPresent());
        assertEquals(5, observations.count());
        assertNull(observations.findById(d.unknown().getId()).orElseThrow().getObservedAtMs());
        csrf = adminPageCsrf("/admin/visits");
        postForm("/admin/visits/recompute", formBody(csrf, "formOrganizationId", d.f().orgB().getId().toString()));
        assertTrue(page("/admin/visits").contains("Organisation gewechselt"));
        assertTrue(derivedVisits.findById(d.foreignVisit().getId()).isPresent());
    }

    @Test
    void switchingOrgImmediatelyChangesReadsAndRejectsStaleRecompute() throws Exception {
        var d = data();
        assertLoginSuccess(formLogin("a35-multi-admin@example.org", "supersecret-password-x"));
        String csrf = adminPageCsrf("/admin/org");
        postForm("/admin/org", formBody(csrf, "organizationId", d.f().orgA().getId().toString()));
        assertTrue(page("/admin/observations").contains("OLDCHIP"));
        assertTrue(page("/admin/visits").contains("Old Cat"));
        csrf = adminPageCsrf("/admin/visits");
        postForm("/admin/org", formBody(csrf, "organizationId", d.f().orgB().getId().toString()));
        for (String route : new String[]{"/admin/observations", "/admin/visits"}) {
            String html = get(route).body();
            assertTrue(html.contains("SECRETCHIP")); assertFalse(html.contains("OLDCHIP"));
            assertTrue(get(route + "?feedingSiteId=" + d.oldSite().getId()).body().contains("Keine Ergebnisse"));
        }
        postForm("/admin/visits/recompute", formBody(csrf, "formOrganizationId", d.f().orgA().getId().toString()));
        assertTrue(get("/admin/visits").body().contains("Organisation gewechselt"));
        assertTrue(derivedVisits.findById(d.foreignVisit().getId()).isPresent());
    }
}
