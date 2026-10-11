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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.imageio.ImageIO;
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
import org.miezmerker.backend.domain.RawObservation;
import org.miezmerker.backend.domain.ShareAudience;
import org.miezmerker.backend.domain.ShareScope;
import org.miezmerker.backend.repo.AppUserRepository;
import org.miezmerker.backend.repo.CatRepository;
import org.miezmerker.backend.repo.MembershipRepository;
import org.miezmerker.backend.repo.NodeRepository;
import org.miezmerker.backend.repo.OrganizationRepository;
import org.miezmerker.backend.repo.RawObservationRepository;
import org.miezmerker.backend.service.SharePolicyService;
import org.miezmerker.backend.support.TestDatabaseCleaner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

/**
 * Real HTTP acceptance tests for #95 (ADR 0016): organization-local cat
 * photos, tenant isolation, observation-backed CARE + PHOTO delivery,
 * revocation, upload validation and storage consistency.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "miezmerker.media.storage-dir=target/cat-photos-test")
@ActiveProfiles("test")
@Tag("auth")
class CatPhotoTest {
    @Value("${local.server.port}") int port;
    @Value("${miezmerker.media.storage-dir}") String storageDir;

    @Autowired AppUserRepository users;
    @Autowired OrganizationRepository organizations;
    @Autowired MembershipRepository memberships;
    @Autowired CatRepository cats;
    @Autowired NodeRepository nodes;
    @Autowired RawObservationRepository observations;
    @Autowired SharePolicyService shares;
    @Autowired PasswordEncoder passwords;
    @Autowired TestDatabaseCleaner cleaner;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    final ObjectMapper json = new ObjectMapper();
    final String password = "cat-photo-test-password";

    Organization orgA, orgB, orgC;
    AppUser memberA, adminA, adminB, adminC, sysadmin;
    Cat catA, catB, catC;
    RawObservation proofA;
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
        orgA = discoverable("photo-a", "Photo Org A");
        orgB = discoverable("photo-b", "Photo Org B");
        orgC = discoverable("photo-c", "Photo Org C");
        memberA = user("member-a");
        adminA = user("admin-a");
        adminB = user("admin-b");
        adminC = user("admin-c");
        sysadmin = user("sysadmin");
        memberships.save(new OrganizationMembership(orgA, memberA, MembershipRole.MEMBER, MembershipStatus.ACTIVE));
        memberships.save(new OrganizationMembership(orgA, adminA, MembershipRole.ADMIN, MembershipStatus.ACTIVE));
        memberships.save(new OrganizationMembership(orgB, adminB, MembershipRole.ADMIN, MembershipStatus.ACTIVE));
        memberships.save(new OrganizationMembership(orgC, adminC, MembershipRole.ADMIN, MembershipStatus.ACTIVE));
        try (var connection = dataSource.getConnection()) {
            assertTrue(SystemRoleMaintenance.apply(connection, UUID.randomUUID(),
                    sysadmin.getId(), "GRANT", "operator-1", "cat photo test"));
        }
        catA = cats.save(new Cat(orgA, "123", "Minka", null, null));
        catB = cats.save(new Cat(orgB, "123", "Schwarzi", null, null));
        catC = cats.save(new Cat(orgC, "123", "Third", null, null));
        NodeDevice nodeA = node(orgA);
        proofA = observations.save(new RawObservation(orgA, nodeA, 1, "123",
                null, "UNKNOWN", null, null, null, null, null));
        client = HttpClient.newBuilder()
                .cookieHandler(new CookieManager())
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    Organization discoverable(String slug, String name) {
        Organization org = new Organization(slug, name, null);
        org.setDiscoverable(true);
        return organizations.save(org);
    }

    AppUser user(String prefix) {
        return users.save(new AppUser(prefix + "@catphoto.test", passwords.encode(password)));
    }

    NodeDevice node(Organization org) {
        NodeDevice node = new NodeDevice(UUID.randomUUID(), "0".repeat(64),
                "1".repeat(64), UUID.randomUUID().toString(), "test");
        node.claim(org);
        return nodes.save(node);
    }

    String base(String path) {
        return "http://localhost:" + port + path;
    }

    String csrf() throws Exception {
        var res = client.send(HttpRequest.newBuilder(
                URI.create(base("/api/v1/auth/csrf"))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode(), res.body());
        return json.readTree(res.body()).get("token").asText();
    }

    void login(AppUser user) throws Exception {
        var res = client.send(HttpRequest.newBuilder(URI.create(base("/api/v1/auth/login")))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", csrf())
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"" + user.getEmail()
                        + "\",\"password\":\"" + password + "\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode(), res.body());
    }

    String photoPath(Organization org, Cat cat) {
        return "/api/v1/organizations/" + org.getId() + "/cats/" + cat.getId() + "/photo";
    }

    HttpResponse<byte[]> putPhoto(Organization org, Cat cat, byte[] bytes,
            String filename, String contentType) throws Exception {
        String boundary = "----catphototest" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n")
                .getBytes(StandardCharsets.UTF_8));
        body.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(bytes);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return client.send(HttpRequest.newBuilder(URI.create(base(photoPath(org, cat))))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("X-XSRF-TOKEN", csrf())
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    HttpResponse<byte[]> getPhoto(Organization org, Cat cat) throws Exception {
        return getPhotoById(org, cat.getId());
    }

    HttpResponse<byte[]> getPhotoById(Organization org, UUID catId) throws Exception {
        return client.send(HttpRequest.newBuilder(
                URI.create(base("/api/v1/organizations/" + org.getId() + "/cats/" + catId + "/photo")))
                .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    HttpResponse<byte[]> deletePhoto(Organization org, Cat cat) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base(photoPath(org, cat))))
                .header("X-XSRF-TOKEN", csrf())
                .DELETE().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    HttpResponse<byte[]> sharedPhoto(Organization recipient, UUID proof, UUID source) throws Exception {
        String body = "{\"ownObservationRef\":\"" + proof + "\",\"sourceOrganizationId\":\"" + source + "\"}";
        return client.send(HttpRequest.newBuilder(
                URI.create(base("/api/v1/organizations/" + recipient.getId() + "/shared-care/photo")))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", csrf())
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    void grant(Organization owner, AppUser admin, ShareScope scope, Organization recipient) {
        shares.upsert(admin.getId(), owner.getId(), scope, ShareAudience.ALLOWLIST,
                List.of(recipient.getId()));
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

    static byte[] png(int width, int height, Color color) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "PNG", output));
        return output.toByteArray();
    }

    /** Injects a fake EXIF APP1 segment (with a GPS marker) after SOI. */
    static byte[] jpegWithExifGps(byte[] plain) {
        assertEquals(0xFF, plain[0] & 0xFF);
        assertEquals(0xD8, plain[1] & 0xFF);
        byte[] payload = "Exif\0\0GPS-IFD-fake-gps-coordinates".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(plain[0]);
        output.write(plain[1]);
        output.write(0xFF);
        output.write(0xE1);
        int length = payload.length + 2;
        output.write((length >> 8) & 0xFF);
        output.write(length & 0xFF);
        output.write(payload, 0, payload.length);
        output.write(plain, 2, plain.length - 2);
        return output.toByteArray();
    }

    static boolean isJpeg(byte[] bytes) {
        return bytes.length > 3 && (bytes[0] & 0xFF) == 0xFF
                && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF;
    }

    static String body(HttpResponse<byte[]> response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    /** Removes the volatile timestamp so controlled error shapes compare equal. */
    static String stripVolatile(String text) {
        return text.replaceAll("\"timestamp\":\"[^\"]*\",?", "");
    }

    @Test
    void sameChipKeepsIndependentPhotosAcrossOrganizations() throws Exception {
        login(memberA);
        assertEquals(200, putPhoto(orgA, catA, jpeg(64, 64, Color.RED), "minka.jpg", "image/jpeg").statusCode());
        login(adminB);
        byte[] blue = jpeg(64, 64, Color.BLUE);
        assertEquals(200, putPhoto(orgB, catB, blue, "schwarzi.jpg", "image/jpeg").statusCode());
        byte[] readB = getPhoto(orgB, catB).body();
        assertEquals(200, getPhoto(orgB, catB).statusCode());
        assertTrue(isJpeg(readB));

        // Replacing A's photo never modifies B's photo for the same chip.
        login(memberA);
        byte[] readA = getPhoto(orgA, catA).body();
        assertTrue(isJpeg(readA));
        assertFalse(java.util.Arrays.equals(readA, readB));
        assertEquals(200, putPhoto(orgA, catA, jpeg(64, 64, Color.GREEN), "minka-neu.jpg", "image/jpeg").statusCode());
        login(adminB);
        assertArrayEquals(readB, getPhoto(orgB, catB).body());
    }

    @Test
    void authorizedLocalUploadReadReplaceDeleteLifecycle() throws Exception {
        login(memberA);
        byte[] first = jpeg(80, 60, Color.RED);
        var stored = putPhoto(orgA, catA, first, "a.jpg", "image/jpeg");
        assertEquals(200, stored.statusCode(), body(stored));
        assertTrue(body(stored).contains("\"hasPhoto\":true"), body(stored));

        var read = getPhoto(orgA, catA);
        assertEquals(200, read.statusCode());
        assertTrue(read.headers().firstValue("Content-Type").orElse("").contains("image/jpeg"));
        assertTrue(isJpeg(read.body()));
        String etag = read.headers().firstValue("ETag").orElseThrow();
        assertTrue(read.headers().firstValue("Cache-Control").orElse("").contains("private"));

        var revalidated = client.send(HttpRequest.newBuilder(URI.create(base(photoPath(orgA, catA))))
                .header("If-None-Match", etag).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(304, revalidated.statusCode());

        byte[] second = png(120, 90, Color.GREEN);
        var replaced = putPhoto(orgA, catA, second, "b.png", "image/png");
        assertEquals(200, replaced.statusCode(), body(replaced));
        var reread = getPhoto(orgA, catA);
        assertEquals(200, reread.statusCode());
        assertTrue(isJpeg(reread.body()), "PNG uploads are normalized to JPEG");
        assertFalse(java.util.Arrays.equals(read.body(), reread.body()));
        assertFalse(reread.headers().firstValue("ETag").orElseThrow().equals(etag));

        assertEquals(204, deletePhoto(orgA, catA).statusCode());
        assertEquals(404, getPhoto(orgA, catA).statusCode());
        assertEquals(404, deletePhoto(orgA, catA).statusCode());
    }

    @Test
    void unauthorizedLocalModificationIsRejected() throws Exception {
        login(memberA);
        assertEquals(200, putPhoto(orgA, catA, jpeg(32, 32, Color.RED), "a.jpg", "image/jpeg").statusCode());

        // A member of B has no authority in A's tenant.
        login(adminB);
        assertEquals(403, getPhoto(orgA, catA).statusCode());
        assertEquals(403, putPhoto(orgA, catA, jpeg(32, 32, Color.BLUE), "x.jpg", "image/jpeg").statusCode());
        assertEquals(403, deletePhoto(orgA, catA).statusCode());

        // A stranger with no membership at all is rejected the same way.
        AppUser stranger = user("stranger");
        memberships.save(new OrganizationMembership(orgC, stranger, MembershipRole.MEMBER, MembershipStatus.ACTIVE));
        login(stranger);
        assertEquals(403, getPhoto(orgA, catA).statusCode());
        assertEquals(403, putPhoto(orgA, catA, jpeg(32, 32, Color.BLUE), "x.jpg", "image/jpeg").statusCode());

        // The owner's photo survived every rejected mutation.
        login(memberA);
        assertEquals(200, getPhoto(orgA, catA).statusCode());
    }

    @Test
    void sysadminWithoutMembershipCannotReadPrivatePhotos() throws Exception {
        login(memberA);
        assertEquals(200, putPhoto(orgA, catA, jpeg(32, 32, Color.RED), "a.jpg", "image/jpeg").statusCode());
        assertTrue(systemIsSysadmin());
        login(sysadmin);
        assertEquals(403, getPhoto(orgA, catA).statusCode());
        assertEquals(403, putPhoto(orgA, catA, jpeg(32, 32, Color.BLUE), "x.jpg", "image/jpeg").statusCode());
        assertEquals(403, deletePhoto(orgA, catA).statusCode());
        assertEquals(403, sharedPhoto(orgA, proofA.getId(), orgB.getId()).statusCode(),
                "SYSADMIN without membership has no recipient context either");
    }

    boolean systemIsSysadmin() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM app_user_system_roles WHERE user_id = ?",
                Integer.class, sysadmin.getId()) == 1;
    }

    @Test
    void careOnlyDoesNotPermitForeignPhotos() throws Exception {
        login(adminB);
        assertEquals(200, putPhoto(orgB, catB, jpeg(48, 48, Color.BLUE), "b.jpg", "image/jpeg").statusCode());
        grant(orgB, adminB, ShareScope.CARE, orgA);
        login(memberA);
        var denied = sharedPhoto(orgA, proofA.getId(), orgB.getId());
        assertEquals(404, denied.statusCode());
        assertEquals("no-store", denied.headers().firstValue("Cache-Control").orElseThrow());
    }

    @Test
    void carePlusPhotoPermitsAuthorizedPhotoAccess() throws Exception {
        login(adminB);
        byte[] uploaded = jpeg(48, 48, Color.BLUE);
        assertEquals(200, putPhoto(orgB, catB, uploaded, "b.jpg", "image/jpeg").statusCode());
        grant(orgB, adminB, ShareScope.CARE, orgA);
        grant(orgB, adminB, ShareScope.PHOTO, orgA);
        login(memberA);
        var allowed = sharedPhoto(orgA, proofA.getId(), orgB.getId());
        assertEquals(200, allowed.statusCode());
        assertEquals("no-store", allowed.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(allowed.headers().firstValue("Content-Type").orElse("").contains("image/jpeg"));
        assertTrue(isJpeg(allowed.body()));
        assertTrue(allowed.headers().firstValue("X-Content-Type-Options").orElse("").contains("nosniff"));
        // The shared read serves exactly the owner's normalized bytes.
        login(adminB);
        assertArrayEquals(getPhoto(orgB, catB).body(), allowed.body());
    }

    @Test
    void photoWithoutEffectiveCareDoesNotPermitAccess() throws Exception {
        login(adminB);
        assertEquals(200, putPhoto(orgB, catB, jpeg(48, 48, Color.BLUE), "b.jpg", "image/jpeg").statusCode());
        grant(orgB, adminB, ShareScope.PHOTO, orgA);
        login(memberA);
        assertEquals(404, sharedPhoto(orgA, proofA.getId(), orgB.getId()).statusCode());
    }

    @Test
    void hiddenDisabledOrRevokedOwnerDeniesAccess() throws Exception {
        login(adminB);
        assertEquals(200, putPhoto(orgB, catB, jpeg(48, 48, Color.BLUE), "b.jpg", "image/jpeg").statusCode());
        grant(orgB, adminB, ShareScope.CARE, orgA);
        grant(orgB, adminB, ShareScope.PHOTO, orgA);
        login(memberA);
        assertEquals(200, sharedPhoto(orgA, proofA.getId(), orgB.getId()).statusCode());

        shares.revoke(adminB.getId(), orgB.getId(), ShareScope.PHOTO);
        assertEquals(404, sharedPhoto(orgA, proofA.getId(), orgB.getId()).statusCode(),
                "revocation blocks subsequent reads immediately");

        grant(orgB, adminB, ShareScope.PHOTO, orgA);
        assertEquals(200, sharedPhoto(orgA, proofA.getId(), orgB.getId()).statusCode());
        shares.setDiscoverable(adminB.getId(), orgB.getId(), false);
        assertEquals(404, sharedPhoto(orgA, proofA.getId(), orgB.getId()).statusCode(),
                "hidden owner denies exactly like a missing grant");
        orgB.setDiscoverable(true);
        organizations.save(orgB);
        // A disabled owner denies as well; restore afterwards for isolation.
        orgB.setStatus(org.miezmerker.backend.domain.OrganizationStatus.DISABLED);
        organizations.save(orgB);
        assertEquals(404, sharedPhoto(orgA, proofA.getId(), orgB.getId()).statusCode());
        orgB.setStatus(org.miezmerker.backend.domain.OrganizationStatus.ACTIVE);
        organizations.save(orgB);
    }

    @Test
    void forgedObservationProofIsRejected() throws Exception {
        login(adminB);
        assertEquals(200, putPhoto(orgB, catB, jpeg(48, 48, Color.BLUE), "b.jpg", "image/jpeg").statusCode());
        grant(orgB, adminB, ShareScope.CARE, orgA);
        grant(orgB, adminB, ShareScope.PHOTO, orgA);
        login(memberA);
        assertEquals(400, sharedPhoto(orgA, UUID.randomUUID(), orgB.getId()).statusCode());
        // B's own observation is not A's proof.
        NodeDevice nodeB = node(orgB);
        RawObservation foreign = observations.save(new RawObservation(orgB, nodeB, 9, "123",
                null, "UNKNOWN", null, null, null, null, null));
        assertEquals(400, sharedPhoto(orgA, foreign.getId(), orgB.getId()).statusCode());
        // A locally created cat id is not observation proof either.
        assertEquals(400, sharedPhoto(orgA, catA.getId(), orgB.getId()).statusCode());
    }

    @Test
    void revocationBlocksSubsequentImageReads() throws Exception {
        login(adminB);
        assertEquals(200, putPhoto(orgB, catB, jpeg(48, 48, Color.BLUE), "b.jpg", "image/jpeg").statusCode());
        grant(orgB, adminB, ShareScope.CARE, orgA);
        grant(orgB, adminB, ShareScope.PHOTO, orgA);
        login(memberA);
        assertEquals(200, sharedPhoto(orgA, proofA.getId(), orgB.getId()).statusCode());
        shares.revoke(adminB.getId(), orgB.getId(), ShareScope.CARE);
        assertEquals(404, sharedPhoto(orgA, proofA.getId(), orgB.getId()).statusCode(),
                "revoking CARE also removes the PHOTO prerequisite");
    }

    @Test
    void foreignImageIdentifiersCannotBeEnumerated() throws Exception {
        login(adminB);
        assertEquals(200, putPhoto(orgB, catB, jpeg(48, 48, Color.BLUE), "b.jpg", "image/jpeg").statusCode());
        login(memberA);
        var deniedGrant = sharedPhoto(orgA, proofA.getId(), orgB.getId());
        var deniedUnknown = sharedPhoto(orgA, proofA.getId(), UUID.randomUUID());
        var deniedOwn = getPhotoById(orgA, UUID.randomUUID());
        assertEquals(404, deniedGrant.statusCode());
        assertEquals(404, deniedUnknown.statusCode());
        assertEquals(404, deniedOwn.statusCode());
        // Identical shape apart from the volatile timestamp and the
        // caller-owned request path: denied, unknown and missing cases are
        // indistinguishable.
        assertEquals(stripVolatile(body(deniedGrant)), stripVolatile(body(deniedUnknown)));
        for (HttpResponse<byte[]> response : List.of(deniedGrant, deniedUnknown, deniedOwn)) {
            String text = body(response);
            assertTrue(text.contains("\"status\":404"), text);
            assertFalse(text.contains("storage"), text);
            assertFalse(text.contains("cat-photos"), text);
            assertFalse(text.contains("target"), text);
            assertFalse(text.contains("Exception"), text);
        }
        // C's member guessing A's photo ids learns nothing either.
        login(adminC);
        assertEquals(403, getPhoto(orgA, catA).statusCode());
    }

    @Test
    void malformedOversizedAndUnsupportedFilesAreRejected() throws Exception {
        login(memberA);
        byte[] photo = jpeg(64, 64, Color.RED);
        assertEquals(200, putPhoto(orgA, catA, photo, "ok.jpg", "image/jpeg").statusCode());
        byte[] current = getPhoto(orgA, catA).body();

        assertEquals(400, putPhoto(orgA, catA,
                "<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>".getBytes(StandardCharsets.UTF_8),
                "evil.svg", "image/svg+xml").statusCode());
        assertEquals(400, putPhoto(orgA, catA,
                "<html><body>not an image</body></html>".getBytes(StandardCharsets.UTF_8),
                "evil.html", "text/html").statusCode());
        assertEquals(400, putPhoto(orgA, catA, "plain text".getBytes(StandardCharsets.UTF_8),
                "evil.txt", "text/plain").statusCode());
        byte[] truncated = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00};
        assertEquals(400, putPhoto(orgA, catA, truncated, "truncated.png", "image/png").statusCode());
        assertEquals(400, putPhoto(orgA, catA, new byte[0], "empty.jpg", "image/jpeg").statusCode());

        byte[] oversized = new byte[5_500_000];
        oversized[0] = (byte) 0xFF;
        oversized[1] = (byte) 0xD8;
        oversized[2] = (byte) 0xFF;
        int oversizedStatus = putPhoto(orgA, catA, oversized, "huge.jpg", "image/jpeg").statusCode();
        assertTrue(oversizedStatus == 400 || oversizedStatus == 413,
                "oversized upload must be rejected, got " + oversizedStatus);

        // Every rejected upload left the current photo untouched.
        assertArrayEquals(current, getPhoto(orgA, catA).body());
    }

    @Test
    void exifAndGpsMetadataAreRemoved() throws Exception {
        login(memberA);
        byte[] exif = jpegWithExifGps(jpeg(96, 72, Color.ORANGE));
        assertTrue(new String(exif, StandardCharsets.UTF_8).contains("GPS-IFD-fake-gps-coordinates"));
        assertEquals(200, putPhoto(orgA, catA, exif, "with-exif.jpg", "image/jpeg").statusCode());
        byte[] normalized = getPhoto(orgA, catA).body();
        assertTrue(isJpeg(normalized));
        String text = new String(normalized, StandardCharsets.ISO_8859_1);
        assertFalse(text.contains("Exif"), "EXIF segment must not survive normalization");
        assertFalse(text.contains("GPS"), "GPS metadata must not survive normalization");
        assertNotNull(ImageIO.read(new java.io.ByteArrayInputStream(normalized)));
    }

    @Test
    void pathTraversalAndMaliciousFilenamesAreRejected() throws Exception {
        login(memberA);
        byte[] photo = jpeg(48, 48, Color.CYAN);
        assertEquals(200, putPhoto(orgA, catA, photo, "../../etc/passwd", "image/jpeg").statusCode());
        assertEquals(200, putPhoto(orgA, catA, photo, "..\\windows\\win.ini", "image/jpeg").statusCode());
        assertEquals(200, putPhoto(orgA, catA, photo, "/abs/path.jpg", "image/jpeg").statusCode());
        assertEquals(200, getPhoto(orgA, catA).statusCode());

        Path dir = Path.of(storageDir).toAbsolutePath().normalize();
        Set<String> keys = Set.copyOf(jdbc.queryForList(
                "SELECT storage_key FROM cat_photos", String.class));
        try (var files = Files.list(dir)) {
            List<String> names = files.map(path -> path.getFileName().toString()).toList();
            assertFalse(names.isEmpty());
            for (String name : names) {
                assertTrue(name.matches("[0-9a-f]{32}\\.jpg"), "only random storage names: " + name);
                assertTrue(keys.contains(name), "every file is referenced: " + name);
            }
        }
        assertFalse(Files.exists(dir.resolve("passwd")));
        assertFalse(Files.exists(dir.resolve("win.ini")));
    }

    @Test
    void concurrentReplacementAndDeletionStayConsistent() throws Exception {
        login(memberA);
        assertEquals(200, putPhoto(orgA, catA, jpeg(48, 48, Color.RED), "a.jpg", "image/jpeg").statusCode());
        login(adminB);
        byte[] blueBytes = jpeg(48, 48, Color.BLUE);
        assertEquals(200, putPhoto(orgB, catB, blueBytes, "b.jpg", "image/jpeg").statusCode());
        byte[] blueRead = getPhoto(orgB, catB).body();

        int writers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(writers + 2);
        CountDownLatch ready = new CountDownLatch(1);
        List<Future<Integer>> outcomes = new ArrayList<>();
        for (int i = 0; i < writers; i++) {
            final int shade = i * 20;
            outcomes.add(pool.submit(() -> {
                ready.await();
                HttpClient threadClient = HttpClient.newBuilder()
                        .cookieHandler(new CookieManager())
                        .connectTimeout(Duration.ofSeconds(10)).build();
                // Separate session per thread: login as memberA.
                String token = json.readTree(threadClient.send(HttpRequest.newBuilder(
                        URI.create(base("/api/v1/auth/csrf"))).GET().build(),
                        HttpResponse.BodyHandlers.ofString()).body()).get("token").asText();
                var loginRes = threadClient.send(HttpRequest.newBuilder(
                        URI.create(base("/api/v1/auth/login")))
                        .header("Content-Type", "application/json")
                        .header("X-XSRF-TOKEN", token)
                        .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\""
                                + memberA.getEmail() + "\",\"password\":\"" + password + "\"}")).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, loginRes.statusCode());
                byte[] update = jpeg(40, 40, new Color(shade % 256, 100, 150));
                if (shade % 40 == 0) {
                    String deleteToken = json.readTree(threadClient.send(HttpRequest.newBuilder(
                            URI.create(base("/api/v1/auth/csrf"))).GET().build(),
                            HttpResponse.BodyHandlers.ofString()).body()).get("token").asText();
                    return threadClient.send(HttpRequest.newBuilder(
                            URI.create(base(photoPath(orgA, catA))))
                            .header("X-XSRF-TOKEN", deleteToken)
                            .DELETE().build(),
                            HttpResponse.BodyHandlers.ofByteArray()).statusCode();
                }
                return putPhotoAs(threadClient, orgA, catA, update);
            }));
        }
        ready.countDown();
        Set<Integer> allowed = Set.of(200, 204, 404);
        List<Integer> observed = new ArrayList<>();
        for (Future<Integer> outcome : outcomes) {
            observed.add(outcome.get());
        }
        assertTrue(allowed.containsAll(observed),
                "concurrent photo mutation must stay in controlled states, got " + observed);
        pool.shutdown();

        // B's independent photo for the same chip is unaffected.
        login(adminB);
        assertArrayEquals(blueRead, getPhoto(orgB, catB).body());

        // A's final state is either a valid photo or cleanly absent.
        login(memberA);
        var terminal = getPhoto(orgA, catA);
        assertTrue(terminal.statusCode() == 200 || terminal.statusCode() == 404);
        if (terminal.statusCode() == 200) {
            assertTrue(isJpeg(terminal.body()));
            assertNotNull(ImageIO.read(new java.io.ByteArrayInputStream(terminal.body())));
        }
        // No orphaned files: every stored file is still referenced.
        Set<String> keys = Set.copyOf(jdbc.queryForList(
                "SELECT storage_key FROM cat_photos WHERE organization_id = ?",
                String.class, orgA.getId()));
        Path dir = Path.of(storageDir).toAbsolutePath().normalize();
        try (var files = Files.list(dir)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (name.startsWith("upload-")) continue;
                Set<String> allKeys = Set.copyOf(jdbc.queryForList(
                        "SELECT storage_key FROM cat_photos", String.class));
                assertTrue(allKeys.contains(name), "orphaned file: " + name);
            }
        }
        long rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM cat_photos WHERE organization_id = ?",
                Long.class, orgA.getId());
        assertTrue(rows <= 1, "at most one photo row per cat");
        assertEquals(rows, keys.size());
    }

    int putPhotoAs(HttpClient threadClient, Organization org, Cat cat, byte[] bytes) throws Exception {
        String boundary = "----catphotothread" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        body.write("Content-Disposition: form-data; name=\"file\"; filename=\"t.jpg\"\r\n"
                .getBytes(StandardCharsets.UTF_8));
        body.write("Content-Type: image/jpeg\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        body.write(bytes);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        String token = json.readTree(threadClient.send(HttpRequest.newBuilder(
                URI.create(base("/api/v1/auth/csrf"))).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body()).get("token").asText();
        return threadClient.send(HttpRequest.newBuilder(URI.create(base(photoPath(org, cat))))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("X-XSRF-TOKEN", token)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(),
                HttpResponse.BodyHandlers.ofByteArray()).statusCode();
    }

    @Test
    void catViewsExposePhotoPresenceWithoutStorageDetails() throws Exception {
        login(memberA);
        var before = client.send(HttpRequest.newBuilder(
                URI.create(base("/api/v1/organizations/" + orgA.getId() + "/cats")))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, before.statusCode());
        assertTrue(before.body().contains("\"hasPhoto\":false"), before.body());

        assertEquals(200, putPhoto(orgA, catA, jpeg(48, 48, Color.RED), "a.jpg", "image/jpeg").statusCode());
        var after = client.send(HttpRequest.newBuilder(
                URI.create(base("/api/v1/organizations/" + orgA.getId() + "/cats/" + catA.getId())))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, after.statusCode());
        assertTrue(after.body().contains("\"hasPhoto\":true"), after.body());
        assertFalse(after.body().contains("storage"), after.body());
        assertFalse(after.body().contains("cat-photos"), after.body());
    }

    @Test
    void deletingACatRemovesItsPhotoBytes() throws Exception {
        login(adminA);
        assertEquals(200, putPhoto(orgA, catA, jpeg(48, 48, Color.RED), "a.jpg", "image/jpeg").statusCode());
        assertEquals(200, getPhoto(orgA, catA).statusCode());
        String token = csrf();
        var deleted = client.send(HttpRequest.newBuilder(
                URI.create(base("/api/v1/organizations/" + orgA.getId() + "/cats/" + catA.getId())))
                .header("X-XSRF-TOKEN", token).DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, deleted.statusCode(), deleted.body());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM cat_photos WHERE organization_id = ?", Long.class, orgA.getId()));
        Path dir = Path.of(storageDir).toAbsolutePath().normalize();
        try (var files = Files.list(dir)) {
            assertTrue(files.toList().isEmpty(), "cat deletion must not orphan photo files");
        }
    }
}
