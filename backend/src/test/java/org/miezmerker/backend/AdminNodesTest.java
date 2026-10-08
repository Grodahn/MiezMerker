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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Tag("admin")
@Tag("auth")
@Tag("node")
class AdminNodesTest {
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


    String detailPath(NodeDevice node) { return "/admin/nodes/" + node.getNodeId(); }

    String snapshot(String html) {
        var m = Pattern.compile("name=\"expectedDeploymentId\"[^>]*value=\"([^\"]*)\"").matcher(html);
        assertTrue(m.find(), html);
        return m.group(1);
    }

    void loginAdmin() throws Exception {
        assertLoginSuccess(formLogin("a35-admin-a@example.org", "supersecret-password-a"));
    }

    HttpResponse<String> assign(NodeDevice node, FeedingSite site, String expected) throws Exception {
        String csrf = adminPageCsrf(detailPath(node));
        return postForm(detailPath(node) + "/assignment", formBody(csrf,
                "feedingSiteId", site.getId().toString(), "expectedDeploymentId", expected));
    }

    @Test
    void listDetailAndNavigationShowOnlyOwnNamedAndUnnamedNodes() throws Exception {
        var f = seed();
        var named = saveClaimedNode(f.orgA());
        named.setDisplayName("Der Grüne"); named.setProtocolVersion("v1");
        named.setStatusNote("Batterie prüfen"); nodes.save(named);
        var unnamed = saveClaimedNode(f.orgA());
        var foreign = saveClaimedNode(f.orgB());
        foreign.setDisplayName("Geheimnapf"); nodes.save(foreign);
        var site = saveSite(f.orgA(), "Tierheim", null, null);
        deployments.save(new NodeDeployment(f.orgA(), named, site,
                Instant.parse("2025-01-01T00:00:00Z"), null));
        loginAdmin();
        var list = get("/admin/nodes");
        assertEquals(200, list.statusCode(), list.body());
        for (String value : new String[]{"Der Grüne", "Unbenannter Napf", "Nicht zugeordnet",
                "Tierheim", named.getNodeId().toString(), unnamed.getNodeId().toString()})
            assertTrue(list.body().contains(value), list.body());
        assertFalse(list.body().contains("Geheimnapf"));
        assertFalse(list.body().contains(foreign.getNodeId().toString()));
        var detail = get(detailPath(named));
        assertEquals(200, detail.statusCode(), detail.body());
        for (String value : new String[]{"test-1", "v1", "Batterie prüfen", "Tierheim"})
            assertTrue(detail.body().contains(value), detail.body());
        assertTrue(get("/admin/").body().contains("/admin/nodes"));
        var sitePage = get("/admin/sites/" + site.getId());
        assertEquals(200, sitePage.statusCode(), sitePage.body());
        assertTrue(sitePage.body().contains(detailPath(named)), sitePage.body());
        assertTrue(sitePage.body().contains("Der Grüne"), sitePage.body());
    }

    @Test
    void namesNormalizeAllowDuplicatesAndPreserveIdentity() throws Exception {
        var f = seed(); var first = saveClaimedNode(f.orgA()); var second = saveClaimedNode(f.orgA());
        loginAdmin();
        for (var node : new NodeDevice[]{first, second}) {
            String csrf = adminPageCsrf(detailPath(node));
            assertEquals(302, postForm(detailPath(node) + "/name", formBody(csrf,
                    "displayName", "\u00a0 Der Grüne \u2003")).statusCode());
            var stored = nodes.findById(node.getNodeId()).orElseThrow();
            assertEquals("Der Grüne", stored.getDisplayName());
            assertEquals(node.getPublicKeyX(), stored.getPublicKeyX());
            assertEquals(node.getPublicKeyY(), stored.getPublicKeyY());
            assertEquals(node.getFingerprint(), stored.getFingerprint());
            assertEquals(node.getState(), stored.getState());
            assertEquals(node.getClaimedAt().toEpochMilli(), stored.getClaimedAt().toEpochMilli());
        }
        String csrf = adminPageCsrf(detailPath(first));
        var invalid = postForm(detailPath(first) + "/name", formBody(csrf, "displayName", "X".repeat(101)));
        assertEquals(200, invalid.statusCode(), invalid.body());
        assertTrue(invalid.body().contains("100 Zeichen"));
        assertEquals("Der Grüne", nodes.findById(first.getNodeId()).orElseThrow().getDisplayName());
        assertEquals(302, postForm(detailPath(first) + "/name", formBody(csrf,
                "displayName", "\u00a0 \u2003")).statusCode());
        assertNull(nodes.findById(first.getNodeId()).orElseThrow().getDisplayName());
        assertTrue(get(detailPath(first)).body().contains("Unbenannter Napf"));
    }

    @Test
    void initialAssignmentAndMovePreserveHistoryAndFrozenAttribution() throws Exception {
        var f = seed(); var node = saveClaimedNode(f.orgA());
        var a = saveSite(f.orgA(), "Alter Ort", null, null);
        var b = saveSite(f.orgA(), "Neuer Ort", null, null);
        loginAdmin();
        assertEquals(302, assign(node, a, "").statusCode());
        var first = deployments.findByNodeNodeId(node.getNodeId()).get(0);
        var observation = observations.save(new RawObservation(f.orgA(), node, 1, "CHIP-52",
                first.getValidFrom().toEpochMilli(), "SYNCED", null, null, null, a, first));
        var visit = derivedVisits.save(new DerivedVisit(f.orgA(), a, "CHIP-52", null,
                first.getValidFrom(), first.getValidFrom().plusSeconds(30), 1,
                "visit-gap-v1", 60, observation, observation));
        assertEquals(302, assign(node, b, first.getId().toString()).statusCode());
        var history = deployments.findByNodeNodeId(node.getNodeId());
        assertEquals(2, history.size());
        var old = deployments.findById(first.getId()).orElseThrow();
        var active = history.stream().filter(d -> d.getValidUntil() == null).findFirst().orElseThrow();
        assertEquals(first.getValidFrom(), old.getValidFrom());
        assertEquals(old.getValidUntil(), active.getValidFrom());
        assertEquals(b.getId(), active.getFeedingSite().getId());
        var page = get(detailPath(node));
        assertEquals(200, page.statusCode(), page.body());
        for (String value : new String[]{"Alter Ort", "Neuer Ort", old.getValidFrom().toString(),
                old.getValidUntil().toString(), "Offen (ohne Enddatum)"})
            assertTrue(page.body().contains(value), page.body());
        var frozen = observations.findById(observation.getId()).orElseThrow();
        assertEquals(a.getId(), frozen.getFeedingSite().getId());
        assertEquals(first.getId(), frozen.getDeployment().getId());
        assertEquals(a.getId(), derivedVisits.findById(visit.getId()).orElseThrow().getFeedingSite().getId());
    }

    @Test
    void staleInitialAndMoveFormsAndSameSiteFailWithoutChangingHistory() throws Exception {
        var f = seed(); var node = saveClaimedNode(f.orgA());
        var a = saveSite(f.orgA(), "Ort A", null, null);
        var b = saveSite(f.orgA(), "Ort B", null, null);
        var c = saveSite(f.orgA(), "Ort C", null, null);
        loginAdmin();
        assertEquals("", snapshot(get(detailPath(node)).body()));
        assertEquals(302, assign(node, a, "").statusCode());
        assertEquals(302, assign(node, b, "").statusCode());
        assertTrue(get(detailPath(node)).body().contains("Zuordnung wurde geändert"));
        assertEquals(1, deployments.findByNodeNodeId(node.getNodeId()).size());
        String first = snapshot(get(detailPath(node)).body());
        assertEquals(302, assign(node, a, first).statusCode());
        assertTrue(get(detailPath(node)).body().contains("bereits zugeordnet"));
        assertEquals(1, deployments.findByNodeNodeId(node.getNodeId()).size());
        assertEquals(302, assign(node, b, first).statusCode());
        assertEquals(302, assign(node, c, first).statusCode());
        assertTrue(get(detailPath(node)).body().contains("Zuordnung wurde geändert"));
        var history = deployments.findByNodeNodeId(node.getNodeId());
        assertEquals(2, history.size());
        assertEquals(b.getId(), history.stream().filter(d -> d.getValidUntil() == null)
                .findFirst().orElseThrow().getFeedingSite().getId());
    }

    @Test
    void futureDeploymentConflictRollsBackAndDoesNotAppearCurrent() throws Exception {
        var f = seed(); var node = saveClaimedNode(f.orgA());
        var a = saveSite(f.orgA(), "Ort A", null, null); var b = saveSite(f.orgA(), "Ort B", null, null);
        var future = deployments.save(new NodeDeployment(f.orgA(), node, a, Instant.now().plusSeconds(3600), null));
        loginAdmin();
        var detail = get(detailPath(node));
        assertEquals(200, detail.statusCode(), detail.body());
        assertTrue(detail.body().contains("Nicht zugeordnet"));
        assertEquals(302, assign(node, b, future.getId().toString()).statusCode());
        assertTrue(get(detailPath(node)).body().contains("Konflikt"));
        assertEquals(1, deployments.findByNodeNodeId(node.getNodeId()).size());
        assertNull(deployments.findById(future.getId()).orElseThrow().getValidUntil());
    }

    @Test
    void foreignNodeAndSiteIdsAndMissingSnapshotAreRejected() throws Exception {
        var f = seed(); var own = saveClaimedNode(f.orgA()); var foreign = saveClaimedNode(f.orgB());
        var ownSite = saveSite(f.orgA(), "Own", null, null); var foreignSite = saveSite(f.orgB(), "Secret Site", null, null);
        loginAdmin();
        var detail = get(detailPath(own));
        assertFalse(detail.body().contains("Secret Site"));
        String csrf = extractCsrf(detail.body());
        for (UUID id : new UUID[]{foreign.getNodeId(), UUID.randomUUID()}) {
            assertEquals(404, get("/admin/nodes/" + id).statusCode());
            assertEquals(404, postForm("/admin/nodes/" + id + "/name", formBody(csrf, "displayName", "Hijack")).statusCode());
            assertEquals(404, postForm("/admin/nodes/" + id + "/assignment", formBody(csrf,
                    "feedingSiteId", ownSite.getId().toString(), "expectedDeploymentId", "")).statusCode());
        }
        assertEquals(404, assign(own, foreignSite, "").statusCode());
        assertEquals(400, postForm(detailPath(own) + "/assignment", formBody(csrf,
                "feedingSiteId", ownSite.getId().toString())).statusCode());
        assertEquals(0, deployments.count());
        assertNull(nodes.findById(foreign.getNodeId()).orElseThrow().getDisplayName());
    }

    @Test
    void csrfIsRequiredAndInvalidTokensRejectedForBothMutations() throws Exception {
        var f = seed(); var node = saveClaimedNode(f.orgA()); var site = saveSite(f.orgA(), "Site", null, null);
        loginAdmin(); get(detailPath(node));
        for (String token : new String[]{null, "invalid"}) {
            assertEquals(403, postForm(detailPath(node) + "/name", formBody(token, "displayName", "Hijack")).statusCode());
            assertEquals(403, postForm(detailPath(node) + "/assignment", formBody(token,
                    "feedingSiteId", site.getId().toString(), "expectedDeploymentId", "")).statusCode());
        }
        assertNull(nodes.findById(node.getNodeId()).orElseThrow().getDisplayName());
        assertEquals(0, deployments.count());
    }

    @Test
    void memberWithValidCsrfCannotReadOrMutate() throws Exception {
        var f = seed(); var node = saveClaimedNode(f.orgA()); var site = saveSite(f.orgA(), "Site", null, null);
        formLogin("a35-member-a@example.org", "supersecret-password-m");
        // The public API exposes the authenticated session's valid CSRF token.
        var tokenResponse = get("/api/v1/auth/csrf");
        assertEquals(200, tokenResponse.statusCode(), tokenResponse.body());
        String csrf = new tools.jackson.databind.ObjectMapper()
                .readTree(tokenResponse.body()).get("token").asText();
        assertEquals(403, get("/admin/nodes").statusCode());
        assertEquals(403, get(detailPath(node)).statusCode());
        assertEquals(403, postForm(detailPath(node) + "/name", formBody(csrf,
                "formOrganizationId", f.orgA().getId().toString(), "displayName", "Hijack")).statusCode());
        assertEquals(403, postForm(detailPath(node) + "/assignment", formBody(csrf,
                "formOrganizationId", f.orgA().getId().toString(), "feedingSiteId", site.getId().toString(),
                "expectedDeploymentId", "")).statusCode());
        assertNull(nodes.findById(node.getNodeId()).orElseThrow().getDisplayName());
        assertEquals(0, deployments.count());
    }

    @Test
    void organizationSwitchInvalidatesBothForms() throws Exception {
        var f = seed(); var node = saveClaimedNode(f.orgA()); var site = saveSite(f.orgA(), "Site", null, null);
        assertLoginSuccess(formLogin("a35-multi-admin@example.org", "supersecret-password-x"));
        assertEquals(302, get("/admin/nodes").statusCode());
        String csrf = adminPageCsrf("/admin/org");
        assertEquals(302, postForm("/admin/org", formBody(csrf, "organizationId", f.orgA().getId().toString())).statusCode());
        csrf = adminPageCsrf(detailPath(node));
        String staleOrg = formOrganizationId;
        assertEquals(302, postForm("/admin/org", formBody(csrf, "organizationId", f.orgB().getId().toString())).statusCode());
        assertEquals(409, postForm(detailPath(node) + "/name", formBody(csrf,
                "formOrganizationId", staleOrg, "displayName", "Hijack")).statusCode());
        assertEquals(409, postForm(detailPath(node) + "/assignment", formBody(csrf,
                "formOrganizationId", staleOrg, "feedingSiteId", site.getId().toString(), "expectedDeploymentId", "")).statusCode());
        assertNull(nodes.findById(node.getNodeId()).orElseThrow().getDisplayName());
        assertEquals(0, deployments.count());
    }

    @Test
    void concurrentMovesFromSameSnapshotOnlyApplyOnce() throws Exception {
        var f = seed(); var node = saveClaimedNode(f.orgA());
        var a = saveSite(f.orgA(), "A", null, null);
        var b = saveSite(f.orgA(), "B", null, null);
        var c = saveSite(f.orgA(), "C", null, null);
        var old = deployments.save(new NodeDeployment(f.orgA(), node, a,
                Instant.parse("2025-01-01T00:00:00Z"), null));
        loginAdmin();
        String csrf = adminPageCsrf(detailPath(node));
        var requests = new java.util.ArrayList<java.util.concurrent.CompletableFuture<HttpResponse<String>>>();
        for (var target : new FeedingSite[]{b, c}) {
            var request = HttpRequest.newBuilder(URI.create(base(detailPath(node) + "/assignment")))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(formBody(csrf,
                            "feedingSiteId", target.getId().toString(),
                            "expectedDeploymentId", old.getId().toString()))).build();
            requests.add(client.sendAsync(request, HttpResponse.BodyHandlers.ofString()));
        }
        for (var response : requests) assertEquals(302, response.get().statusCode());
        var history = deployments.findByNodeNodeId(node.getNodeId());
        assertEquals(2, history.size(), "Only one concurrent move may be committed");
        var active = history.stream().filter(d -> d.getValidUntil() == null).findFirst().orElseThrow();
        assertTrue(java.util.Set.of(b.getId(), c.getId()).contains(active.getFeedingSite().getId()));
        assertEquals(active.getValidFrom(), deployments.findById(old.getId()).orElseThrow().getValidUntil());
        // Both redirects target the same page; consume both queued flash maps.
        String feedback = get(detailPath(node)).body() + get(detailPath(node)).body();
        assertTrue(feedback.contains("Zuordnung wurde geändert"));
    }

    @Test
    void revokedAdminMembershipAndDisabledAccountCannotMutate() throws Exception {
        var f = seed(); var node = saveClaimedNode(f.orgA());
        var site = saveSite(f.orgA(), "Site", null, null);
        loginAdmin();
        String csrf = adminPageCsrf(detailPath(node));
        var membership = memberships.findByOrganizationIdAndUserId(f.orgA().getId(), f.adminA().getId()).orElseThrow();
        membership.setStatusDirect(MembershipStatus.PENDING); memberships.save(membership);
        assertEquals(403, get(detailPath(node)).statusCode());
        assertEquals(403, postForm(detailPath(node) + "/name", formBody(csrf, "displayName", "Hijack")).statusCode());
        assertEquals(403, postForm(detailPath(node) + "/assignment", formBody(csrf,
                "feedingSiteId", site.getId().toString(), "expectedDeploymentId", "")).statusCode());
        membership.setStatusDirect(MembershipStatus.ACTIVE); memberships.save(membership);
        var user = users.findById(f.adminA().getId()).orElseThrow();
        user.setStatus(UserStatus.DISABLED); users.save(user);
        assertEquals(403, get(detailPath(node)).statusCode());
        assertEquals(403, postForm(detailPath(node) + "/name", formBody(csrf, "displayName", "Hijack")).statusCode());
        assertEquals(403, postForm(detailPath(node) + "/assignment", formBody(csrf,
                "feedingSiteId", site.getId().toString(), "expectedDeploymentId", "")).statusCode());
        assertNull(nodes.findById(node.getNodeId()).orElseThrow().getDisplayName());
        assertEquals(0, deployments.count());
    }
}
