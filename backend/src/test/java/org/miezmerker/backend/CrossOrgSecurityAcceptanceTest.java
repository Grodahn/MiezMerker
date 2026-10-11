package org.miezmerker.backend;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.miezmerker.backend.bootstrap.SystemRoleMaintenance;
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
import org.miezmerker.backend.domain.OrganizationStatus;
import org.miezmerker.backend.domain.RawObservation;
import org.miezmerker.backend.domain.ShareAudience;
import org.miezmerker.backend.domain.ShareScope;
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
import org.miezmerker.backend.security.AppUserDetails;
import org.miezmerker.backend.service.CatPhotoService;
import org.miezmerker.backend.service.SharePolicyService;
import org.miezmerker.backend.service.SharedCareService;
import org.miezmerker.backend.service.VisitAggregationService;
import org.miezmerker.backend.support.TestDatabaseCleaner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end security acceptance for issue #99 (epic #89, ADR 0016).
 *
 * <p>Deterministic three-organization fixture: A, B and C each own an ACTIVE
 * ADMIN plus an ACTIVE MEMBER, an organization-local {@code Cat} for the same
 * chip with different names/notes/photos, CLAIMED nodes, feeding sites with
 * deployment history, SYNCED plus UNKNOWN observations and recomputed visits.
 * The same chip number never implies shared ownership or authorization.
 *
 * <p>All assertions use the real backend authorization layer over HTTP or
 * through the authorized services; no mocks confer access.
 *
 * <p>Not parallel-safe with itself: the class shares one media directory and
 * the application's singleton in-memory shared-care rate limiter, so it must
 * run sequentially (per-test user/UUID fixtures keep sequential runs
 * deterministic and far below rate limits).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "miezmerker.media.storage-dir=target/cat-photos-acceptance99")
@ActiveProfiles("test")
@Tag("auth")
@Tag("visits")
class CrossOrgSecurityAcceptanceTest {
    static final String CHIP = "987654321098765";

    @Value("${local.server.port}") int port;
    @Value("${miezmerker.media.storage-dir}") String storageDir;

    @Autowired AppUserRepository users;
    @Autowired OrganizationRepository organizations;
    @Autowired MembershipRepository memberships;
    @Autowired CatRepository cats;
    @Autowired NodeRepository nodes;
    @Autowired FeedingSiteRepository sites;
    @Autowired NodeDeploymentRepository deployments;
    @Autowired RawObservationRepository observations;
    @Autowired DerivedVisitRepository visits;
    @Autowired SharePolicyService shares;
    @Autowired SharedCareService shared;
    @Autowired CatPhotoService photos;
    @Autowired VisitAggregationService aggregation;
    @Autowired org.miezmerker.backend.admin.SysadminOrganizationService sysadminOrgs;
    @Autowired PasswordEncoder passwords;
    @Autowired TestDatabaseCleaner cleaner;
    @Autowired DataSource dataSource;

    final ObjectMapper json = new ObjectMapper();
    final String password = "acceptance99-password";
    final Instant time = Instant.parse("2026-10-01T10:00:00Z");

    Organization orgA, orgB, orgC;
    AppUser adminA, memberA, adminB, memberB, adminC, memberC, sysadmin, stranger;
    Cat catA, catB, catC;
    NodeDevice nodeA, nodeB, nodeC;
    FeedingSite siteA, siteB, siteC;
    RawObservation proofA, proofB, proofC;
    HttpClient client;

    @BeforeEach
    void seed() throws Exception {
        cleaner.clean();
        Path dir = Path.of(storageDir).toAbsolutePath().normalize();
        if (Files.exists(dir)) {
            try (var files = Files.list(dir)) {
                for (Path file : files.toList()) Files.deleteIfExists(file);
            }
        }
        orgA = org("acc-a", "Acceptance Org A");
        orgB = org("acc-b", "Acceptance Org B");
        orgC = org("acc-c", "Acceptance Org C");
        adminA = user("acc-admin-a"); memberA = user("acc-member-a");
        adminB = user("acc-admin-b"); memberB = user("acc-member-b");
        adminC = user("acc-admin-c"); memberC = user("acc-member-c");
        sysadmin = user("acc-sysadmin"); stranger = user("acc-stranger");
        memberships.save(new OrganizationMembership(orgA, adminA, MembershipRole.ADMIN, MembershipStatus.ACTIVE));
        memberships.save(new OrganizationMembership(orgA, memberA, MembershipRole.MEMBER, MembershipStatus.ACTIVE));
        memberships.save(new OrganizationMembership(orgB, adminB, MembershipRole.ADMIN, MembershipStatus.ACTIVE));
        memberships.save(new OrganizationMembership(orgB, memberB, MembershipRole.MEMBER, MembershipStatus.ACTIVE));
        memberships.save(new OrganizationMembership(orgC, adminC, MembershipRole.ADMIN, MembershipStatus.ACTIVE));
        memberships.save(new OrganizationMembership(orgC, memberC, MembershipRole.MEMBER, MembershipStatus.ACTIVE));
        try (var connection = dataSource.getConnection()) {
            assertTrue(SystemRoleMaintenance.apply(connection, UUID.randomUUID(),
                    sysadmin.getId(), "GRANT", "operator-99", "acceptance fixture"));
        }
        catA = cats.save(new Cat(orgA, CHIP, "Minka-A", "healthy-A", "private note A"));
        catB = cats.save(new Cat(orgB, CHIP, "Schwarzi-B", "healthy-B", "private note B"));
        catC = cats.save(new Cat(orgC, CHIP, "Tiger-C", "healthy-C", "private note C"));
        nodeA = node(orgA); nodeB = node(orgB); nodeC = node(orgC);
        siteA = sites.save(new FeedingSite(orgA, "Bowl A", "desc A", 52.0, 13.0, "addr A"));
        siteB = sites.save(new FeedingSite(orgB, "Bowl B", "desc B", 52.1, 13.1, "addr B"));
        siteC = sites.save(new FeedingSite(orgC, "Bowl C", "desc C", 52.2, 13.2, "addr C"));
        var depA = deployments.save(new NodeDeployment(orgA, nodeA, siteA, time.minusSeconds(3600), null));
        var depB = deployments.save(new NodeDeployment(orgB, nodeB, siteB, time.minusSeconds(3600), null));
        var depC = deployments.save(new NodeDeployment(orgC, nodeC, siteC, time.minusSeconds(3600), null));
        proofA = observations.save(new RawObservation(orgA, nodeA, 1, CHIP,
                time.toEpochMilli(), "SYNCED", null, null, null, siteA, depA));
        observations.save(new RawObservation(orgA, nodeA, 2, CHIP,
                time.plusSeconds(3600).toEpochMilli(), "SYNCED", null, null, null, siteA, depA));
        observations.save(new RawObservation(orgA, nodeA, 3, CHIP, null, "UNKNOWN", null, null, null, null, null));
        proofB = observations.save(new RawObservation(orgB, nodeB, 1, CHIP,
                time.toEpochMilli(), "SYNCED", null, null, null, siteB, depB));
        observations.save(new RawObservation(orgB, nodeB, 2, CHIP,
                time.plusSeconds(3600).toEpochMilli(), "SYNCED", null, null, null, siteB, depB));
        proofC = observations.save(new RawObservation(orgC, nodeC, 1, CHIP,
                time.toEpochMilli(), "SYNCED", null, null, null, siteC, depC));
        aggregation.recompute(adminA.getId(), orgA.getId(), null, null);
        aggregation.recompute(adminB.getId(), orgB.getId(), null, null);
        aggregation.recompute(adminC.getId(), orgC.getId(), null, null);
        photos.store(adminA.getId(), orgA.getId(), catA.getId(), jpeg(48, 48, Color.RED));
        photos.store(adminB.getId(), orgB.getId(), catB.getId(), jpeg(48, 48, Color.BLUE));
        photos.store(adminC.getId(), orgC.getId(), catC.getId(), jpeg(48, 48, Color.GREEN));
        client = HttpClient.newBuilder().cookieHandler(new CookieManager())
                .connectTimeout(Duration.ofSeconds(10)).build();
    }

    Organization org(String slug, String name) {
        Organization org = new Organization(slug, name, null);
        org.setDiscoverable(true);
        return organizations.save(org);
    }

    AppUser user(String prefix) {
        return users.save(new AppUser(prefix + "@acceptance99.test", passwords.encode(password)));
    }

    NodeDevice node(Organization org) {
        NodeDevice node = new NodeDevice(UUID.randomUUID(), "0".repeat(64),
                "1".repeat(64), UUID.randomUUID().toString(), "test");
        node.claim(org);
        return nodes.save(node);
    }

    static byte[] jpeg(int width, int height, Color color) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "JPEG", output));
        return output.toByteArray();
    }

    String base(String path) {
        return "http://localhost:" + port + path;
    }

    String csrf() throws Exception {
        var res = client.send(HttpRequest.newBuilder(URI.create(base("/api/v1/auth/csrf")))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode(), res.body());
        return json.readTree(res.body()).get("token").asText();
    }

    void login(AppUser user) throws Exception {
        var res = client.send(HttpRequest.newBuilder(URI.create(base("/api/v1/auth/login")))
                .header("Content-Type", "application/json").header("X-XSRF-TOKEN", csrf())
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"" + user.getEmail()
                        + "\",\"password\":\"" + password + "\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode(), res.body());
    }

    void logout() throws Exception {
        var res = client.send(HttpRequest.newBuilder(URI.create(base("/api/v1/auth/logout")))
                .header("X-XSRF-TOKEN", csrf())
                .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode(), res.body());
    }

    HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base(path))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> postJson(String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base(path)))
                .header("Content-Type", "application/json").header("X-XSRF-TOKEN", csrf())
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> putJson(String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base(path)))
                .header("Content-Type", "application/json").header("X-XSRF-TOKEN", csrf())
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> deleteRaw(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base(path)))
                .header("X-XSRF-TOKEN", csrf()).DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<byte[]> getBytes(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base(path))).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    HttpResponse<byte[]> postBytes(String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base(path)))
                .header("Content-Type", "application/json").header("X-XSRF-TOKEN", csrf())
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    void grant(Organization owner, AppUser admin, ShareScope scope, Organization recipient) {
        shares.upsert(admin.getId(), owner.getId(), scope, ShareAudience.ALLOWLIST,
                List.of(recipient.getId()));
    }

    String catsPath(Organization org) {
        return "/api/v1/organizations/" + org.getId() + "/cats";
    }

    String photoPath(Organization org, Cat cat) {
        return "/api/v1/organizations/" + org.getId() + "/cats/" + cat.getId() + "/photo";
    }

    String resolveBody(RawObservation proof) {
        return "{\"ownObservationRefs\":[\"" + proof.getId() + "\"]}";
    }

    String visitsBody(RawObservation proof, Organization source) {
        return "{\"ownObservationRef\":\"" + proof.getId()
                + "\",\"sourceOrganizationId\":\"" + source.getId() + "\"}";
    }

    String visitsBody(RawObservation proof, Organization source, int limit, int offset) {
        return "{\"ownObservationRef\":\"" + proof.getId()
                + "\",\"sourceOrganizationId\":\"" + source.getId()
                + "\",\"limit\":" + limit + ",\"offset\":" + offset + "}";
    }

    void assertNoLeak(String body, String... secrets) {
        for (String secret : secrets) {
            assertFalse(body.contains(secret), "leaked " + secret + " in: " + body);
        }
    }

    @Test
    void defaultDenyAcrossAllApiSurfacesWithSameChip() throws Exception {
        login(memberA);
        // Cat lists contain only the local profile; counts do not leak.
        JsonNode listA = json.readTree(get(catsPath(orgA)).body());
        assertEquals(1, listA.size());
        assertEquals("Minka-A", listA.get(0).get("name").asText());
        assertEquals(CHIP, listA.get(0).get("chipId").asText());

        // Foreign cat ids are not addressable through the local tenant context.
        // The default Spring error echoes the request path; the asserted
        // property is that no foreign content (names, notes) is disclosed.
        assertEquals(404, get(catsPath(orgA) + "/" + catB.getId()).statusCode());
        String foreignDetail = get(catsPath(orgA) + "/" + catB.getId()).body();
        assertNoLeak(foreignDetail, "Schwarzi-B", "private note B");

        // Foreign organization paths reject readers without membership (403, never data).
        assertEquals(403, get(catsPath(orgB)).statusCode());
        assertEquals(403, get(catsPath(orgB) + "/" + catB.getId()).statusCode());
        assertEquals(403, get(photoPath(orgB, catB)).statusCode());

        // Observation and visit surfaces stay tenant-scoped; chip filters match only local rows.
        JsonNode obs = json.readTree(get("/api/v1/observations?organizationId=" + orgA.getId()
                + "&chipId=" + CHIP).body());
        assertTrue(obs.size() >= 3);
        for (JsonNode row : obs) {
            assertEquals(orgA.getId().toString(), row.get("organizationId").asText());
        }
        assertEquals(403, get("/api/v1/observations?organizationId=" + orgB.getId()).statusCode());
        JsonNode visitList = json.readTree(get("/api/v1/organizations/" + orgA.getId()
                + "/visits?chipId=" + CHIP).body());
        assertEquals(2, visitList.size());
        assertEquals(403, get("/api/v1/organizations/" + orgB.getId() + "/visits?chipId=" + CHIP).statusCode());
        JsonNode latest = json.readTree(get("/api/v1/organizations/" + orgA.getId()
                + "/visits/latest-per-site?chipId=" + CHIP).body());
        assertEquals(1, latest.size());
        assertEquals("Bowl A", latest.get(0).get("feedingSiteName").asText());
        assertEquals(403, get("/api/v1/organizations/" + orgB.getId()
                + "/visits/latest-per-site?chipId=" + CHIP).statusCode());
        JsonNode activity = json.readTree(get("/api/v1/organizations/" + orgA.getId()
                + "/chip-activity").body());
        assertEquals(1, activity.size());
        assertEquals(CHIP, activity.get(0).get("chipId").asText());

        // Shared-care stays empty without grants; photo delivery denies uniformly.
        var resolve = postJson("/api/v1/organizations/" + orgA.getId() + "/shared-care/resolve",
                resolveBody(proofA));
        assertEquals(200, resolve.statusCode());
        assertEquals(0, json.readTree(resolve.body()).get("items").size());
        assertEquals("no-store", resolve.headers().firstValue("Cache-Control").orElseThrow());
        var visitsCall = postJson("/api/v1/organizations/" + orgA.getId() + "/shared-care/visits",
                visitsBody(proofA, orgB));
        assertEquals(200, visitsCall.statusCode());
        assertEquals(0, json.readTree(visitsCall.body()).get("items").size());
        var photo = postBytes("/api/v1/organizations/" + orgA.getId() + "/shared-care/photo",
                visitsBody(proofA, orgB));
        assertEquals(404, photo.statusCode());
        assertNoLeak(new String(photo.body(), StandardCharsets.UTF_8),
                "Schwarzi-B", "private note B", "Bowl B");

        // Global SYSADMIN without membership reads neither cats nor media.
        login(sysadmin);
        assertEquals(403, get(catsPath(orgA)).statusCode());
        assertEquals(403, get(photoPath(orgA, catA)).statusCode());
        assertEquals(403, postJson("/api/v1/organizations/" + orgA.getId() + "/shared-care/resolve",
                resolveBody(proofA)).statusCode());

        // Strangers and hidden owners stay indistinguishable from missing data.
        login(stranger);
        assertEquals(403, get(catsPath(orgA)).statusCode());
    }

    @Test
    void sharingMatrixGrantsOnlyIntendedSubsets() throws Exception {
        // CARE only: profile visible, visits/photos/site labels withheld.
        grant(orgB, adminB, ShareScope.CARE, orgA);
        login(memberA);
        var resolve = postJson("/api/v1/organizations/" + orgA.getId() + "/shared-care/resolve",
                resolveBody(proofA));
        assertEquals(1, json.readTree(resolve.body()).get("items").size());
        assertEquals("Schwarzi-B",
                json.readTree(resolve.body()).get("items").get(0).get("catDisplayName").asText());
        assertNoLeak(resolve.body(), "private note B", "healthy-B", "Bowl B", "52.1");
        var visitsCall = postJson("/api/v1/organizations/" + orgA.getId() + "/shared-care/visits",
                visitsBody(proofA, orgB));
        assertEquals(0, json.readTree(visitsCall.body()).get("items").size());
        assertEquals(404, postBytes("/api/v1/organizations/" + orgA.getId() + "/shared-care/photo",
                visitsBody(proofA, orgB)).statusCode());

        // CARE + VISITS without SITE_LABEL: visits visible, site names withheld
        // across items, ordering and pagination.
        grant(orgB, adminB, ShareScope.VISITS, orgA);
        var paged = postJson("/api/v1/organizations/" + orgA.getId() + "/shared-care/visits",
                visitsBody(proofA, orgB, 1, 0));
        JsonNode page = json.readTree(paged.body());
        assertEquals(1, page.get("items").size());
        assertTrue(page.get("items").get(0).get("siteDisplayName").isNull());
        assertNoLeak(paged.body(), "Bowl B", "desc B", "addr B", "52.1", "13.1");
        var second = postJson("/api/v1/organizations/" + orgA.getId() + "/shared-care/visits",
                visitsBody(proofA, orgB, 1, 1));
        assertTrue(json.readTree(second.body()).get("items").get(0).get("siteDisplayName").isNull());

        // SITE_LABEL additionally reveals only the display name, never coordinates.
        grant(orgB, adminB, ShareScope.SITE_LABEL, orgA);
        var labeled = postJson("/api/v1/organizations/" + orgA.getId() + "/shared-care/visits",
                visitsBody(proofA, orgB));
        assertEquals(200, labeled.statusCode());
        assertEquals("no-store", labeled.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals("Bowl B", json.readTree(labeled.body()).get("items").get(0).get("siteDisplayName").asText());
        assertNoLeak(labeled.body(), "desc B", "addr B", "52.1", "13.1", "private");

        // SITE_LABEL without VISITS stays ineffective even with CARE present.
        shares.revoke(adminB.getId(), orgB.getId(), ShareScope.VISITS);
        assertEquals(0, json.readTree(postJson("/api/v1/organizations/" + orgA.getId()
                + "/shared-care/visits", visitsBody(proofA, orgB)).body()).get("items").size());
        assertEquals(1, json.readTree(postJson("/api/v1/organizations/" + orgA.getId()
                + "/shared-care/resolve", resolveBody(proofA)).body()).get("items").size());
        grant(orgB, adminB, ShareScope.VISITS, orgA);

        // Request bounds are enforced before any foreign discovery (400, never data).
        var refs = new StringBuilder("{\"ownObservationRefs\":[");
        for (int i = 0; i < 51; i++) {
            if (i > 0) refs.append(',');
            refs.append('"').append(UUID.randomUUID()).append('"');
        }
        refs.append("]}");
        assertEquals(400, postJson("/api/v1/organizations/" + orgA.getId() + "/shared-care/resolve",
                refs.toString()).statusCode());
        assertEquals(400, postJson("/api/v1/organizations/" + orgA.getId() + "/shared-care/resolve",
                "{\"ownObservationRefs\":[\"" + proofA.getId() + "\"],\"limit\":0}").statusCode());
        assertEquals(400, postJson("/api/v1/organizations/" + orgA.getId() + "/shared-care/visits",
                visitsBody(proofA, orgB, 50, 10001)).statusCode());

        // PHOTO requires CARE plus observation evidence; CARE + PHOTO authorizes delivery.
        grant(orgB, adminB, ShareScope.PHOTO, orgA);
        var image = postBytes("/api/v1/organizations/" + orgA.getId() + "/shared-care/photo",
                visitsBody(proofA, orgB));
        assertEquals(200, image.statusCode());
        assertEquals("no-store", image.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(image.body().length > 100);

        // VISITS without CARE and PHOTO without CARE never become effective.
        shares.revoke(adminB.getId(), orgB.getId(), ShareScope.CARE);
        assertEquals(0, json.readTree(postJson("/api/v1/organizations/" + orgA.getId()
                + "/shared-care/visits", visitsBody(proofA, orgB)).body()).get("items").size());
        assertEquals(404, postBytes("/api/v1/organizations/" + orgA.getId() + "/shared-care/photo",
                visitsBody(proofA, orgB)).statusCode());
    }

    @Test
    void nonTransitiveSharingAcrossThreeOrganizations() throws Exception {
        grant(orgA, adminA, ShareScope.CARE, orgB);
        grant(orgB, adminB, ShareScope.CARE, orgC);
        grant(orgB, adminB, ShareScope.VISITS, orgC);
        grant(orgB, adminB, ShareScope.PHOTO, orgC);
        photos.store(adminA.getId(), orgA.getId(), catA.getId(), jpeg(32, 32, Color.RED));

        // C reads B, B reads A, but C never inherits A through B.
        login(memberC);
        var fromB = postJson("/api/v1/organizations/" + orgC.getId() + "/shared-care/resolve",
                resolveBody(proofC));
        assertEquals(1, json.readTree(fromB.body()).get("items").size());
        assertEquals("Schwarzi-B",
                json.readTree(fromB.body()).get("items").get(0).get("catDisplayName").asText());
        var visitsFromB = postJson("/api/v1/organizations/" + orgC.getId() + "/shared-care/visits",
                visitsBody(proofC, orgB));
        assertEquals(2, json.readTree(visitsFromB.body()).get("items").size());
        var photoFromB = postBytes("/api/v1/organizations/" + orgC.getId() + "/shared-care/photo",
                visitsBody(proofC, orgB));
        assertEquals(200, photoFromB.statusCode());
        var fromA = postJson("/api/v1/organizations/" + orgC.getId() + "/shared-care/visits",
                visitsBody(proofC, orgA));
        assertEquals(0, json.readTree(fromA.body()).get("items").size());
        assertEquals(404, postBytes("/api/v1/organizations/" + orgC.getId() + "/shared-care/photo",
                visitsBody(proofC, orgA)).statusCode());
        assertNoLeak(fromA.body(), "Minka-A", "private note A", "Bowl A");

        // B still reads A directly via the A->B grant; switching context never merges the views.
        login(memberB);
        var bReadsA = postJson("/api/v1/organizations/" + orgB.getId() + "/shared-care/resolve",
                resolveBody(proofB));
        assertEquals(1, json.readTree(bReadsA.body()).get("items").size());
        assertEquals("Minka-A",
                json.readTree(bReadsA.body()).get("items").get(0).get("catDisplayName").asText());
        login(memberA);
        assertEquals(1, json.readTree(get(catsPath(orgA)).body()).size());
    }

    @Test
    void revocationAndLifecycleBlockSubsequentReads() throws Exception {
        grant(orgB, adminB, ShareScope.CARE, orgA);
        grant(orgB, adminB, ShareScope.VISITS, orgA);
        grant(orgB, adminB, ShareScope.PHOTO, orgA);
        login(memberA);
        assertEquals(1, json.readTree(postJson("/api/v1/organizations/" + orgA.getId()
                + "/shared-care/resolve", resolveBody(proofA)).body()).get("items").size());
        assertEquals(200, postBytes("/api/v1/organizations/" + orgA.getId() + "/shared-care/photo",
                visitsBody(proofA, orgB)).statusCode());

        // Scope revocation, recipient removal, hiding and deactivation each deny the next read.
        shares.revoke(adminB.getId(), orgB.getId(), ShareScope.PHOTO);
        assertEquals(404, postBytes("/api/v1/organizations/" + orgA.getId() + "/shared-care/photo",
                visitsBody(proofA, orgB)).statusCode());
        assertEquals(1, json.readTree(postJson("/api/v1/organizations/" + orgA.getId()
                + "/shared-care/resolve", resolveBody(proofA)).body()).get("items").size());
        shares.revoke(adminB.getId(), orgB.getId(), ShareScope.VISITS);
        assertEquals(0, json.readTree(postJson("/api/v1/organizations/" + orgA.getId()
                + "/shared-care/visits", visitsBody(proofA, orgB)).body()).get("items").size());
        shares.revoke(adminB.getId(), orgB.getId(), ShareScope.CARE);
        assertEquals(0, json.readTree(postJson("/api/v1/organizations/" + orgA.getId()
                + "/shared-care/resolve", resolveBody(proofA)).body()).get("items").size());

        // Re-grant then hide the owner: grants invalidate immediately and never auto-restore.
        grant(orgB, adminB, ShareScope.CARE, orgA);
        shares.setDiscoverable(adminB.getId(), orgB.getId(), false);
        login(memberA);
        assertEquals(0, json.readTree(postJson("/api/v1/organizations/" + orgA.getId()
                + "/shared-care/resolve", resolveBody(proofA)).body()).get("items").size());
        shares.setDiscoverable(adminB.getId(), orgB.getId(), true);
        assertEquals(0, json.readTree(postJson("/api/v1/organizations/" + orgA.getId()
                + "/shared-care/resolve", resolveBody(proofA)).body()).get("items").size());

        // Disabled membership, disabled user and logout all block existing sessions.
        grant(orgB, adminB, ShareScope.CARE, orgA);
        assertEquals(1, json.readTree(postJson("/api/v1/organizations/" + orgA.getId()
                + "/shared-care/resolve", resolveBody(proofA)).body()).get("items").size());
        OrganizationMembership membership = memberships
                .findByOrganizationIdAndUserId(orgA.getId(), memberA.getId()).orElseThrow();
        membership.setStatusDirect(MembershipStatus.DISABLED);
        memberships.save(membership);
        assertEquals(403, get(catsPath(orgA)).statusCode());
        membership.setStatusDirect(MembershipStatus.ACTIVE);
        memberships.save(membership);
        login(memberA);
        assertEquals(200, get(catsPath(orgA)).statusCode());

        // A disabled global account loses its live session: session reads 401,
        // tenant reads 403, and re-enabling plus re-login restores access.
        memberA.setStatus(UserStatus.DISABLED);
        users.save(memberA);
        assertEquals(401, get("/api/v1/auth/session").statusCode());
        assertEquals(403, get(catsPath(orgA)).statusCode());
        memberA.setStatus(UserStatus.ACTIVE);
        users.save(memberA);
        login(memberA);
        assertEquals(200, get(catsPath(orgA)).statusCode());

        logout();
        assertEquals(401, get("/api/v1/auth/session").statusCode());
        assertEquals(401, get(catsPath(orgA)).statusCode());

        // Organization switching uses the selected context; A->B->A never leaks B into A.
        // (Per-test isolation comes from cleaner.clean() in seed(); no ordering
        // assumption between tests is made here.)
        login(memberA);
        memberships.save(new OrganizationMembership(orgB, memberA, MembershipRole.MEMBER, MembershipStatus.ACTIVE));
        assertEquals(1, json.readTree(get(catsPath(orgA)).body()).size());
        assertEquals("Minka-A", json.readTree(get(catsPath(orgA)).body()).get(0).get("name").asText());
        assertEquals(1, json.readTree(get(catsPath(orgB)).body()).size());
        assertEquals("Schwarzi-B", json.readTree(get(catsPath(orgB)).body()).get(0).get("name").asText());
    }

    @Test
    void photoSecurityAcrossOrganizations() throws Exception {
        // Same chip keeps distinguishable bytes per organization.
        login(memberA);
        byte[] own = getBytes(photoPath(orgA, catA)).body();
        login(memberB);
        byte[] foreign = getBytes(photoPath(orgB, catB)).body();
        assertFalse(java.util.Arrays.equals(own, foreign));

        // Members manage only their own tenant; foreign ids stay hidden, never forbidden-oracle.
        login(memberA);
        assertEquals(403, getBytes(photoPath(orgB, catB)).statusCode());
        assertEquals(404, getBytes("/api/v1/organizations/" + orgA.getId()
                + "/cats/" + catB.getId() + "/photo").statusCode());
        assertEquals(404, getBytes("/api/v1/organizations/" + orgA.getId()
                + "/cats/" + UUID.randomUUID() + "/photo").statusCode());

        // Foreign delivery needs CARE + PHOTO + genuine observation; wrong contexts fail identically.
        grant(orgB, adminB, ShareScope.CARE, orgA);
        assertEquals(404, postBytes("/api/v1/organizations/" + orgA.getId() + "/shared-care/photo",
                visitsBody(proofA, orgB)).statusCode());
        grant(orgB, adminB, ShareScope.PHOTO, orgA);
        var authorized = postBytes("/api/v1/organizations/" + orgA.getId() + "/shared-care/photo",
                visitsBody(proofA, orgB));
        assertEquals(200, authorized.statusCode());
        assertEquals("no-store", authorized.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals("nosniff", authorized.headers().firstValue("X-Content-Type-Options").orElseThrow());
        // Forged observation proof fails uniformly (400, rate-limited) before any
        // foreign discovery; a valid proof in a context without membership
        // fails at the tenant gate (403). Neither discloses foreign existence.
        assertEquals(400, postBytes("/api/v1/organizations/" + orgA.getId() + "/shared-care/photo",
                "{\"ownObservationRef\":\"" + proofB.getId() + "\",\"sourceOrganizationId\":\""
                        + orgB.getId() + "\"}").statusCode());
        assertEquals(403, postBytes("/api/v1/organizations/" + orgC.getId() + "/shared-care/photo",
                visitsBody(proofA, orgB)).statusCode());

        // Revocation blocks reuse of the same request; bytes stay distinguishable per owner.
        shares.revoke(adminB.getId(), orgB.getId(), ShareScope.PHOTO);
        assertEquals(404, postBytes("/api/v1/organizations/" + orgA.getId() + "/shared-care/photo",
                visitsBody(proofA, orgB)).statusCode());
        login(memberB);
        assertArrayEquals(foreign, getBytes(photoPath(orgB, catB)).body());
    }

    @Test
    void roleBoundariesAndOrganizationLifecycle() throws Exception {
        login(sysadmin);
        // SYSADMIN administers organizations but reads no tenant animal data.
        assertEquals(200, get("/api/v1/organizations/directory").statusCode());
        assertEquals(403, get(catsPath(orgA)).statusCode());

        // MEMBER cannot perform ADMIN-only operations (policy surface is PUT/DELETE).
        login(memberA);
        var grantAttempt = putJson("/api/v1/organizations/" + orgA.getId() + "/share-policies/CARE",
                "{\"audience\":\"PRIVATE\"}");
        assertEquals(403, grantAttempt.statusCode());
        assertEquals(403, deleteRaw("/api/v1/organizations/" + orgA.getId()
                + "/share-policies/CARE").statusCode());
        var deleteAttempt = client.send(HttpRequest.newBuilder(
                        URI.create(base(catsPath(orgA) + "/" + catA.getId())))
                        .header("X-XSRF-TOKEN", csrf()).DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(403, deleteAttempt.statusCode());

        // ADMIN of A cannot administer B; disabled organizations reject stale authorization.
        login(adminA);
        assertEquals(403, putJson("/api/v1/organizations/" + orgB.getId() + "/share-policies/CARE",
                "{\"audience\":\"PRIVATE\"}").statusCode());
        orgB.setStatus(OrganizationStatus.DISABLED);
        organizations.save(orgB);
        login(memberB);
        assertEquals(403, get(catsPath(orgB)).statusCode());

        // Reactivation through the SYSADMIN surface never restores invalidated grants.
        grant(orgA, adminA, ShareScope.CARE, orgC);
        var sysadminPrincipal = new AppUserDetails(sysadmin.getId(), sysadmin.getEmail(),
                sysadmin.getPasswordHash(), UserStatus.ACTIVE);
        sysadminOrgs.deactivateOrganization(sysadminPrincipal, orgA.getId(), true);
        sysadminOrgs.reactivateOrganization(sysadminPrincipal, orgA.getId(), true);
        login(memberC);
        // Disabling invalidated the outgoing grant; reactivation keeps it revoked.
        assertEquals(0, json.readTree(postJson("/api/v1/organizations/" + orgC.getId()
                + "/shared-care/resolve", resolveBody(proofC)).body()).get("items").size());
    }

    @Test
    void sharingLeavesHistoricalDataUntouched() throws Exception {
        long observationsBefore = observations.count();
        long visitsBefore = visits.count();
        String nameBefore = cats.findByOrganizationIdAndChipId(orgA.getId(), CHIP).orElseThrow().getName();
        UUID nodeOwnerBefore = nodes.findById(nodeB.getNodeId()).orElseThrow().getOrganization().getId();

        grant(orgB, adminB, ShareScope.CARE, orgA);
        grant(orgB, adminB, ShareScope.VISITS, orgA);
        grant(orgB, adminB, ShareScope.SITE_LABEL, orgA);
        login(memberA);
        var resolve = postJson("/api/v1/organizations/" + orgA.getId() + "/shared-care/resolve",
                resolveBody(proofA));
        assertEquals(1, json.readTree(resolve.body()).get("items").size());
        var sharedVisits = postJson("/api/v1/organizations/" + orgA.getId() + "/shared-care/visits",
                visitsBody(proofA, orgB));
        assertEquals(2, json.readTree(sharedVisits.body()).get("items").size());

        // Reading shared projections changes no authoritative rows and merges no profiles.
        assertEquals(observationsBefore, observations.count());
        assertEquals(visitsBefore, visits.count());
        assertEquals(nameBefore,
                cats.findByOrganizationIdAndChipId(orgA.getId(), CHIP).orElseThrow().getName());
        assertEquals("Schwarzi-B",
                cats.findByOrganizationIdAndChipId(orgB.getId(), CHIP).orElseThrow().getName());
        assertEquals(nodeOwnerBefore, nodes.findById(nodeB.getNodeId()).orElseThrow().getOrganization().getId());

        // Revocation deletes no local data; local histories remain complete.
        shares.revoke(adminB.getId(), orgB.getId(), ShareScope.VISITS);
        assertEquals(observationsBefore, observations.count());
        assertEquals(visitsBefore, visits.count());
        assertEquals(2, visits.countByOrganizationId(orgA.getId()));
        assertEquals(2, visits.countByOrganizationId(orgB.getId()));
        List<DerivedVisit> localA = visits.findByOrganizationIdAndAlgorithmVersion(orgA.getId(),
                VisitAggregationService.ALGORITHM_VISIT_GAP_V1);
        assertEquals(2, localA.size());
        for (DerivedVisit visit : localA) {
            assertEquals(orgA.getId(), visit.getOrganization().getId());
            assertEquals(CHIP, visit.getChipId());
        }
    }

    // Reveals assertion mistakes in this acceptance test itself: member delete is
    // 403 via TenantService#requireAdmin, never 405; keep the contract explicit.
    @Test
    void memberCannotDeleteForeignOrLocalCats() throws Exception {
        login(memberA);
        var response = client.send(HttpRequest.newBuilder(
                        URI.create(base(catsPath(orgA) + "/" + catA.getId())))
                        .header("X-XSRF-TOKEN", csrf()).DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(403, response.statusCode());
        assertTrue(cats.findById(catA.getId()).isPresent());
        login(adminA);
        // ADMIN delete stays scoped; other organizations keep their profiles.
        var forbidden = client.send(HttpRequest.newBuilder(
                        URI.create(base("/api/v1/organizations/" + orgA.getId()
                                + "/cats/" + catB.getId())))
                        .header("X-XSRF-TOKEN", csrf()).DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(404, forbidden.statusCode());
        assertTrue(cats.findById(catB.getId()).isPresent());
    }
}
