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
 * Acceptance tests for #52: administrative Napf (Node) management in the
 * server-rendered Admin backend ({@code /admin/nodes}).
 *
 * <p>User-facing term is Napf; internally the existing {@code Node} entity is
 * kept. Covers tenant-scoped list/detail, display-name editing with #50
 * semantics, initial assignment and atomic move via
 * {@code DeploymentService.move}, history accuracy, frozen observation/visit
 * attribution, ADMIN-only gates, CSRF, and foreign-id rejection.
 */
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
            AppUser adminB, AppUser multiAdmin) {}

    Fixture seed() {
        cleaner.clean();

        Organization orgA = organizations.save(new Organization("org-a52", "Org A", null));
        Organization orgB = organizations.save(new Organization("org-b52", "Org B", null));

        AppUser adminA = users.save(new AppUser("a52-admin-a@example.org",
                passwords.encode("supersecret-password-a"), "Ada Admin"));
        AppUser memberA = users.save(new AppUser("a52-member-a@example.org",
                passwords.encode("supersecret-password-m")));
        AppUser adminB = users.save(new AppUser("a52-admin-b@example.org",
                passwords.encode("supersecret-password-b")));
        AppUser multiAdmin = users.save(new AppUser("a52-multi-admin@example.org",
                passwords.encode("supersecret-password-x"), "Multi Admin"));

        memberships.save(new OrganizationMembership(orgA, adminA, MembershipRole.ADMIN,
                MembershipStatus.ACTIVE));
        memberships.save(new OrganizationMembership(orgA, memberA, MembershipRole.MEMBER,
                MembershipStatus.ACTIVE));
        memberships.save(new OrganizationMembership(orgB, adminB, MembershipRole.ADMIN,
                MembershipStatus.ACTIVE));
        memberships.save(new OrganizationMembership(orgA, multiAdmin, MembershipRole.ADMIN,
                MembershipStatus.ACTIVE));
        memberships.save(new OrganizationMembership(orgB, multiAdmin, MembershipRole.ADMIN,
                MembershipStatus.ACTIVE));
        return new Fixture(orgA, orgB, adminA, memberA, adminB, multiAdmin);
    }

    FeedingSite saveSite(Organization org, String name) {
        return sites.save(new FeedingSite(org, name, null, null, null, null));
    }

    NodeDevice saveClaimedNode(Organization org) {
        UUID nodeId = UUID.randomUUID();
        String fingerprint = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "").substring(0, 28);
        NodeDevice node = new NodeDevice(nodeId, "x-" + UUID.randomUUID(),
                "y-" + UUID.randomUUID(), fingerprint.substring(0, 60), "test-1");
        node.claim(org);
        return nodes.save(node);
    }

    NodeDevice saveNamedNode(Organization org, String displayName) {
        NodeDevice node = saveClaimedNode(org);
        node.setDisplayName(displayName);
        return nodes.save(node);
    }

    // --- 1. Tenant-scoped list ---

    @Test
    void adminListsOnlyOwnNodes() throws Exception {
        Fixture f = seed();
        NodeDevice ownNamed = saveNamedNode(f.orgA(), "Der Grüne");
        NodeDevice ownUnnamed = saveClaimedNode(f.orgA());
        NodeDevice foreign = saveNamedNode(f.orgB(), "Fremder Napf");

        assertLoginSuccess(formLogin("a52-admin-a@example.org", "supersecret-password-a"));
        var list = get("/admin/nodes");
        assertEquals(200, list.statusCode(), list.body());
        assertTrue(list.body().contains("Der Grüne"), list.body());
        assertTrue(list.body().contains(ownNamed.getNodeId().toString()), list.body());
        assertTrue(list.body().contains(ownUnnamed.getNodeId().toString()), list.body());
        assertFalse(list.body().contains("Fremder Napf"), list.body());
        assertFalse(list.body().contains(foreign.getNodeId().toString()), list.body());

        http();
        assertLoginSuccess(formLogin("a52-admin-b@example.org", "supersecret-password-b"));
        var listB = get("/admin/nodes");
        assertEquals(200, listB.statusCode(), listB.body());
        assertTrue(listB.body().contains("Fremder Napf"), listB.body());
        assertFalse(listB.body().contains("Der Grüne"), listB.body());
    }

    // --- 2. Named / unnamed rendering ---

    @Test
    void namedAndUnnamedNodesRenderCorrectly() throws Exception {
        Fixture f = seed();
        FeedingSite site = saveSite(f.orgA(), "Am Friedhof");
        NodeDevice named = saveNamedNode(f.orgA(), "Der Grüne");
        deployments.save(new NodeDeployment(f.orgA(), named, site,
                Instant.parse("2025-01-01T00:00:00Z"), null));
        NodeDevice unnamed = saveClaimedNode(f.orgA());

        assertLoginSuccess(formLogin("a52-admin-a@example.org", "supersecret-password-a"));
        var list = get("/admin/nodes");
        assertEquals(200, list.statusCode(), list.body());
        assertTrue(list.body().contains("Der Grüne"), list.body());
        assertTrue(list.body().contains("Unbenannter Napf"), list.body());
        assertTrue(list.body().contains("Am Friedhof"), list.body());
        assertTrue(list.body().contains("Nicht zugeordnet"), list.body());

        var detailNamed = get("/admin/nodes/" + named.getNodeId());
        assertEquals(200, detailNamed.statusCode(), detailNamed.body());
        assertTrue(detailNamed.body().contains("Der Grüne"), detailNamed.body());
        assertTrue(detailNamed.body().contains(named.getNodeId().toString()),
                detailNamed.body());
        assertTrue(detailNamed.body().contains("Am Friedhof"), detailNamed.body());

        var detailUnnamed = get("/admin/nodes/" + unnamed.getNodeId());
        assertEquals(200, detailUnnamed.statusCode(), detailUnnamed.body());
        assertTrue(detailUnnamed.body().contains("Unbenannter Napf"), detailUnnamed.body());
        assertTrue(detailUnnamed.body().contains("Nicht zugeordnet"), detailUnnamed.body());
        assertTrue(detailUnnamed.body().contains(unnamed.getNodeId().toString()),
                detailUnnamed.body());
    }

    // --- 3. Rename ---

    @Test
    void adminCanUpdateDisplayName() throws Exception {
        Fixture f = seed();
        NodeDevice node = saveNamedNode(f.orgA(), "Der Grüne");
        String fingerprintBefore = node.getFingerprint();
        String xBefore = node.getPublicKeyX();
        String yBefore = node.getPublicKeyY();

        assertLoginSuccess(formLogin("a52-admin-a@example.org", "supersecret-password-a"));
        String csrf = adminPageCsrf("/admin/nodes/" + node.getNodeId());
        var renamed = postForm("/admin/nodes/" + node.getNodeId() + "/name",
                formBody(csrf, "displayName", "  Silberner Napf  "));
        assertEquals(302, renamed.statusCode(), renamed.body());
        var detail = get("/admin/nodes/" + node.getNodeId());
        assertEquals(200, detail.statusCode(), detail.body());
        assertTrue(detail.body().contains("Silberner Napf"), detail.body());

        NodeDevice reloaded = nodes.findById(node.getNodeId()).orElseThrow();
        assertEquals("Silberner Napf", reloaded.getDisplayName());
        // Technical identity untouched.
        assertEquals(node.getNodeId(), reloaded.getNodeId());
        assertEquals("CLAIMED", reloaded.getState().name());
        assertEquals(f.orgA().getId(), reloaded.getOrganization().getId());
        assertEquals(fingerprintBefore, reloaded.getFingerprint());
        assertEquals(xBefore, reloaded.getPublicKeyX());
        assertEquals(yBefore, reloaded.getPublicKeyY());

        // Blank clears to null.
        String csrf2 = adminPageCsrf("/admin/nodes/" + node.getNodeId());
        var cleared = postForm("/admin/nodes/" + node.getNodeId() + "/name",
                formBody(csrf2, "displayName", "   "));
        assertEquals(302, cleared.statusCode(), cleared.body());
        assertNull(nodes.findById(node.getNodeId()).orElseThrow().getDisplayName());
        var clearedDetail = get("/admin/nodes/" + node.getNodeId());
        assertTrue(clearedDetail.body().contains("Unbenannter Napf"), clearedDetail.body());

        // Overlong names are rejected without touching the stored value.
        String csrf3 = adminPageCsrf("/admin/nodes/" + node.getNodeId());
        var tooLong = postForm("/admin/nodes/" + node.getNodeId() + "/name",
                formBody(csrf3, "displayName", "x".repeat(101)));
        assertEquals(200, tooLong.statusCode(), tooLong.body());
        assertTrue(tooLong.body().contains("höchstens"), tooLong.body());
        assertNull(nodes.findById(node.getNodeId()).orElseThrow().getDisplayName());
    }

    // --- 4. Duplicates allowed ---

    @Test
    void duplicateDisplayNamesRemainValid() throws Exception {
        Fixture f = seed();
        NodeDevice first = saveClaimedNode(f.orgA());
        NodeDevice second = saveClaimedNode(f.orgA());

        assertLoginSuccess(formLogin("a52-admin-a@example.org", "supersecret-password-a"));
        String csrf = adminPageCsrf("/admin/nodes/" + first.getNodeId());
        assertEquals(302, postForm("/admin/nodes/" + first.getNodeId() + "/name",
                formBody(csrf, "displayName", "Silberner Napf")).statusCode());
        String csrf2 = adminPageCsrf("/admin/nodes/" + second.getNodeId());
        var dup = postForm("/admin/nodes/" + second.getNodeId() + "/name",
                formBody(csrf2, "displayName", "Silberner Napf"));
        assertEquals(302, dup.statusCode(), dup.body());
        assertEquals("Silberner Napf",
                nodes.findById(first.getNodeId()).orElseThrow().getDisplayName());
        assertEquals("Silberner Napf",
                nodes.findById(second.getNodeId()).orElseThrow().getDisplayName());
    }

    // --- 5. Initial assignment ---

    @Test
    void adminCanPerformInitialAssignment() throws Exception {
        Fixture f = seed();
        FeedingSite site = saveSite(f.orgA(), "Am Friedhof");
        NodeDevice node = saveNamedNode(f.orgA(), "Der Grüne");

        assertLoginSuccess(formLogin("a52-admin-a@example.org", "supersecret-password-a"));
        String csrf = adminPageCsrf("/admin/nodes/" + node.getNodeId());
        var assigned = postForm("/admin/nodes/" + node.getNodeId() + "/assignment",
                formBody(csrf, "feedingSiteId", site.getId().toString()));
        assertEquals(302, assigned.statusCode(), assigned.body());

        var detail = get("/admin/nodes/" + node.getNodeId());
        assertEquals(200, detail.statusCode(), detail.body());
        assertTrue(detail.body().contains("Am Friedhof"), detail.body());
        assertTrue(detail.body().contains("heute (ohne Enddatum)"), detail.body());

        var history = deployments.findByOrganizationIdAndNodeNodeId(
                f.orgA().getId(), node.getNodeId());
        assertEquals(1, history.size());
        assertEquals(site.getId(), history.get(0).getFeedingSite().getId());
        assertNull(history.get(0).getValidUntil());
    }

    // --- 6+7. Move + history ---

    @Test
    void adminCanMoveNodeAndHistoryRemainsCorrect() throws Exception {
        Fixture f = seed();
        FeedingSite siteA = saveSite(f.orgA(), "Tierheim");
        FeedingSite siteB = saveSite(f.orgA(), "Am Friedhof");
        NodeDevice node = saveNamedNode(f.orgA(), "Der Grüne");
        Instant start = Instant.parse("2025-01-01T00:00:00Z");
        NodeDeployment depA = deployments.save(
                new NodeDeployment(f.orgA(), node, siteA, start, null));
        Instant depAFrom = depA.getValidFrom();

        assertLoginSuccess(formLogin("a52-admin-a@example.org", "supersecret-password-a"));
        String csrf = adminPageCsrf("/admin/nodes/" + node.getNodeId());
        var moved = postForm("/admin/nodes/" + node.getNodeId() + "/assignment",
                formBody(csrf, "feedingSiteId", siteB.getId().toString()));
        assertEquals(302, moved.statusCode(), moved.body());

        var detail = get("/admin/nodes/" + node.getNodeId());
        assertEquals(200, detail.statusCode(), detail.body());
        assertTrue(detail.body().contains("Am Friedhof"), detail.body());
        assertTrue(detail.body().contains("Tierheim"), detail.body());
        assertTrue(detail.body().contains("heute (ohne Enddatum)"), detail.body());

        var history = deployments.findByOrganizationIdAndNodeNodeId(
                f.orgA().getId(), node.getNodeId());
        assertEquals(2, history.size());
        NodeDeployment closed =
                deployments.findById(depA.getId()).orElseThrow();
        assertEquals(depAFrom, closed.getValidFrom());
        assertNotNull(closed.getValidUntil());
        NodeDeployment open = history.stream()
                .filter(d -> d.getValidUntil() == null).findFirst().orElseThrow();
        assertEquals(siteB.getId(), open.getFeedingSite().getId());
        // Non-overlapping: closed ends exactly where open begins.
        assertEquals(closed.getValidUntil(), open.getValidFrom());
        assertEquals(siteA.getId(), closed.getFeedingSite().getId());
    }

    // --- 8+9. Frozen attribution ---

    @Test
    void historicalObservationsAndVisitsKeepOriginalSite() throws Exception {
        Fixture f = seed();
        FeedingSite siteA = saveSite(f.orgA(), "Tierheim");
        FeedingSite siteB = saveSite(f.orgA(), "Am Friedhof");
        NodeDevice node = saveNamedNode(f.orgA(), "Der Grüne");
        Instant jan = Instant.parse("2025-01-01T00:00:00Z");
        NodeDeployment depA = deployments.save(
                new NodeDeployment(f.orgA(), node, siteA, jan, null));
        RawObservation janObs = observations.save(new RawObservation(f.orgA(), node, 1,
                "CHIP-H", jan.plus(Duration.ofDays(10)).toEpochMilli(), "SYNCED",
                null, null, null, siteA, depA));
        DerivedVisit janVisit = derivedVisits.save(new DerivedVisit(f.orgA(), siteA,
                "CHIP-H", null, jan.plus(Duration.ofDays(10)),
                jan.plus(Duration.ofDays(10)).plusSeconds(30), 1, "visit-gap-v1", 60,
                janObs, janObs));

        assertLoginSuccess(formLogin("a52-admin-a@example.org", "supersecret-password-a"));
        String csrf = adminPageCsrf("/admin/nodes/" + node.getNodeId());
        var moved = postForm("/admin/nodes/" + node.getNodeId() + "/assignment",
                formBody(csrf, "feedingSiteId", siteB.getId().toString()));
        assertEquals(302, moved.statusCode(), moved.body());

        assertEquals(siteA.getId(), observations
                .findByNodeNodeIdAndSequence(node.getNodeId(), 1).orElseThrow()
                .getFeedingSite().getId());
        assertEquals(siteA.getId(), derivedVisits.findById(janVisit.getId())
                .orElseThrow().getFeedingSite().getId());
        assertEquals(2, deployments.findByOrganizationIdAndNodeNodeId(
                f.orgA().getId(), node.getNodeId()).size());
    }

    // --- 10. MEMBER rejected ---

    @Test
    void memberCannotAccessAdminMutations() throws Exception {
        Fixture f = seed();
        NodeDevice node = saveNamedNode(f.orgA(), "Der Grüne");
        FeedingSite site = saveSite(f.orgA(), "Am Friedhof");

        var login = formLogin("a52-member-a@example.org", "supersecret-password-m");
        assertEquals(302, login.statusCode(), login.body());
        for (String path : new String[] {"/admin/nodes",
                "/admin/nodes/" + node.getNodeId()}) {
            var denied = get(path);
            assertEquals(403, denied.statusCode(), path + ": " + denied.body());
            assertTrue(denied.body().contains("Kein Admin-Zugang"), path);
            assertFalse(denied.body().contains("Der Grüne"), path);
        }
        var deniedRename = postForm("/admin/nodes/" + node.getNodeId() + "/name",
                "displayName=" + URLEncoder.encode("Hijack", StandardCharsets.UTF_8));
        assertEquals(403, deniedRename.statusCode(), deniedRename.body());
        assertEquals("Der Grüne",
                nodes.findById(node.getNodeId()).orElseThrow().getDisplayName());
        var deniedAssign = postForm("/admin/nodes/" + node.getNodeId() + "/assignment",
                "feedingSiteId=" + site.getId());
        assertEquals(403, deniedAssign.statusCode(), deniedAssign.body());
        assertTrue(deployments.findByOrganizationIdAndNodeNodeId(
                f.orgA().getId(), node.getNodeId()).isEmpty());
    }

    // --- 11. Foreign ids rejected ---

    @Test
    void foreignNodeAndSiteIdsAreRejected() throws Exception {
        Fixture f = seed();
        NodeDevice foreignNode = saveNamedNode(f.orgB(), "Geheimer Napf");
        FeedingSite foreignSite = saveSite(f.orgB(), "Geheimort");
        NodeDevice ownNode = saveNamedNode(f.orgA(), "Der Grüne");
        FeedingSite ownSite = saveSite(f.orgA(), "Am Friedhof");

        formLogin("a52-admin-a@example.org", "supersecret-password-a");

        var foreignDetail = get("/admin/nodes/" + foreignNode.getNodeId());
        assertEquals(404, foreignDetail.statusCode(), foreignDetail.body());
        assertFalse(foreignDetail.body().contains("Geheimer Napf"), foreignDetail.body());

        String csrf = adminPageCsrf("/admin/nodes/" + ownNode.getNodeId());
        var foreignRename = postForm("/admin/nodes/" + foreignNode.getNodeId() + "/name",
                formBody(csrf, "displayName", "Hijack"));
        assertEquals(404, foreignRename.statusCode(), foreignRename.body());
        assertEquals("Geheimer Napf",
                nodes.findById(foreignNode.getNodeId()).orElseThrow().getDisplayName());

        var foreignAssign = postForm("/admin/nodes/" + ownNode.getNodeId() + "/assignment",
                formBody(adminPageCsrf("/admin/nodes/" + ownNode.getNodeId()),
                        "feedingSiteId", foreignSite.getId().toString()));
        assertEquals(404, foreignAssign.statusCode(), foreignAssign.body());
        assertTrue(deployments.findByOrganizationIdAndNodeNodeId(
                f.orgA().getId(), ownNode.getNodeId()).isEmpty());

        var foreignNodeAssign = postForm(
                "/admin/nodes/" + foreignNode.getNodeId() + "/assignment",
                formBody(csrf, "feedingSiteId", ownSite.getId().toString()));
        assertEquals(404, foreignNodeAssign.statusCode(), foreignNodeAssign.body());

        UUID random = UUID.randomUUID();
        assertEquals(404, get("/admin/nodes/" + random).statusCode());
    }

    // --- 12. CSRF ---

    @Test
    void csrfIsRequiredForNodeMutations() throws Exception {
        Fixture f = seed();
        NodeDevice node = saveNamedNode(f.orgA(), "Der Grüne");
        FeedingSite site = saveSite(f.orgA(), "Am Friedhof");
        formLogin("a52-admin-a@example.org", "supersecret-password-a");

        var noCsrfRename = postForm("/admin/nodes/" + node.getNodeId() + "/name",
                "displayName=" + URLEncoder.encode("Hijack", StandardCharsets.UTF_8)
                        + "&formOrganizationId=" + f.orgA().getId());
        assertEquals(403, noCsrfRename.statusCode(), noCsrfRename.body());
        assertEquals("Der Grüne",
                nodes.findById(node.getNodeId()).orElseThrow().getDisplayName());

        var noCsrfAssign = postForm("/admin/nodes/" + node.getNodeId() + "/assignment",
                "feedingSiteId=" + site.getId()
                        + "&formOrganizationId=" + f.orgA().getId());
        assertEquals(403, noCsrfAssign.statusCode(), noCsrfAssign.body());
        assertTrue(deployments.findByOrganizationIdAndNodeNodeId(
                f.orgA().getId(), node.getNodeId()).isEmpty());
    }

    // --- 13. Stale / conflicting moves fail safely ---

    @Test
    void staleAndConflictingMovesFailSafely() throws Exception {
        Fixture f = seed();
        FeedingSite siteA = saveSite(f.orgA(), "Tierheim");
        FeedingSite siteB = saveSite(f.orgA(), "Am Friedhof");
        NodeDevice node = saveNamedNode(f.orgA(), "Der Grüne");
        deployments.save(new NodeDeployment(f.orgA(), node, siteA,
                Instant.parse("2025-01-01T00:00:00Z"), null));

        assertLoginSuccess(formLogin("a52-admin-a@example.org", "supersecret-password-a"));

        // Same-site move is a conflict: friendly error, history untouched.
        String csrf = adminPageCsrf("/admin/nodes/" + node.getNodeId());
        var same = postForm("/admin/nodes/" + node.getNodeId() + "/assignment",
                formBody(csrf, "feedingSiteId", siteA.getId().toString()));
        assertEquals(302, same.statusCode(), same.body());
        var sameDetail = get("/admin/nodes/" + node.getNodeId());
        assertEquals(200, sameDetail.statusCode(), sameDetail.body());
        assertTrue(sameDetail.body().contains("bereits zugeordnet"), sameDetail.body());
        assertEquals(1, deployments.findByOrganizationIdAndNodeNodeId(
                f.orgA().getId(), node.getNodeId()).size());
        assertNull(deployments.findByOrganizationIdAndNodeNodeId(
                f.orgA().getId(), node.getNodeId()).get(0).getValidUntil());

        // Stale organization guard: 409, nothing written.
        String staleCsrf = adminPageCsrf("/admin/nodes/" + node.getNodeId());
        String staleOrg = f.orgB().getId().toString();
        var stale = postForm("/admin/nodes/" + node.getNodeId() + "/assignment",
                formBody(staleCsrf, "feedingSiteId", siteB.getId().toString(),
                        "formOrganizationId", staleOrg));
        assertEquals(409, stale.statusCode(), stale.body());
        assertEquals(1, deployments.findByOrganizationIdAndNodeNodeId(
                f.orgA().getId(), node.getNodeId()).size());

        // Valid move still works afterwards.
        String freshCsrf = adminPageCsrf("/admin/nodes/" + node.getNodeId());
        var moved = postForm("/admin/nodes/" + node.getNodeId() + "/assignment",
                formBody(freshCsrf, "feedingSiteId", siteB.getId().toString()));
        assertEquals(302, moved.statusCode(), moved.body());
        assertEquals(2, deployments.findByOrganizationIdAndNodeNodeId(
                f.orgA().getId(), node.getNodeId()).size());
    }

    // --- 14. Existing admin navigation keeps working ---

    @Test
    void existingAdminNavigationAndSitesKeepWorking() throws Exception {
        Fixture f = seed();
        saveSite(f.orgA(), "PWA Site");
        saveNamedNode(f.orgA(), "Der Grüne");

        assertLoginSuccess(formLogin("a52-admin-a@example.org", "supersecret-password-a"));
        var dash = get("/admin/");
        assertEquals(200, dash.statusCode(), dash.body());
        assertTrue(dash.body().contains("Näpfe"), dash.body());
        assertTrue(dash.body().contains("/admin/nodes"), dash.body());
        assertTrue(dash.body().contains("Futterstellen"), dash.body());

        var sites = get("/admin/sites");
        assertEquals(200, sites.statusCode(), sites.body());
        assertTrue(sites.body().contains("PWA Site"), sites.body());
        assertTrue(sites.body().contains("/admin/nodes"), sites.body());

        var nodes = get("/admin/nodes");
        assertEquals(200, nodes.statusCode(), nodes.body());
        assertTrue(nodes.body().contains("/admin/sites"), nodes.body());
    }

    @Test
    void unauthenticatedNodesRedirectToLogin() throws Exception {
        seed();
        for (String path : new String[] {"/admin/nodes",
                "/admin/nodes/" + UUID.randomUUID()}) {
            var res = get(path);
            assertEquals(302, res.statusCode(), path + ": " + res.body());
            assertTrue(res.headers().firstValue("location").orElse("")
                    .contains("/admin/login"), path);
        }
    }
}
