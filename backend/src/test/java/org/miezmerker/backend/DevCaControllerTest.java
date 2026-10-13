package org.miezmerker.backend;

import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.miezmerker.backend.admin.DevCaController;
import org.miezmerker.backend.domain.AppUser;
import org.miezmerker.backend.domain.MembershipRole;
import org.miezmerker.backend.domain.MembershipStatus;
import org.miezmerker.backend.domain.Organization;
import org.miezmerker.backend.domain.OrganizationMembership;
import org.miezmerker.backend.repo.AppUserRepository;
import org.miezmerker.backend.repo.MembershipRepository;
import org.miezmerker.backend.repo.OrganizationRepository;
import org.miezmerker.backend.support.TestDatabaseCleaner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

/** The DEV-only CA endpoint must remain behind the normal ADMIN security gate. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "miezmerker.dev-ca.enabled=true",
    "miezmerker.dev-ca.public-cert-path=src/test/resources/dev-ca/rootCA.pem"
})
@ActiveProfiles({"test", "dev"})
@Tag("admin")
@Tag("auth")
class DevCaControllerTest {
    @Value("${local.server.port}") int port;
    @Autowired AppUserRepository users;
    @Autowired OrganizationRepository organizations;
    @Autowired MembershipRepository memberships;
    @Autowired PasswordEncoder passwords;
    @Autowired TestDatabaseCleaner cleaner;

    HttpClient client;
    String base(String path) { return "http://127.0.0.1:" + port + path; }
    HttpResponse<byte[]> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base(path))).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    @BeforeEach
    void setup() {
        client = HttpClient.newBuilder().cookieHandler(new CookieManager())
                .followRedirects(HttpClient.Redirect.NEVER).build();
        cleaner.clean();
        Organization org = organizations.save(new Organization("ca-dev-fixture", "Dev CA test", null));
        AppUser admin = users.save(new AppUser("devca-admin@example.org",
                passwords.encode("known-test-password")));
        AppUser member = users.save(new AppUser("devca-member@example.org",
                passwords.encode("known-member-password")));
        memberships.save(new OrganizationMembership(org, admin, MembershipRole.ADMIN,
                MembershipStatus.ACTIVE));
        memberships.save(new OrganizationMembership(org, member, MembershipRole.MEMBER,
                MembershipStatus.ACTIVE));
    }

    void login(String email, String password) throws Exception {
        HttpResponse<byte[]> page = get("/admin/login");
        assertEquals(200, page.statusCode());
        String html = new String(page.body(), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(html);
        assertTrue(m.find(), "CSRF token must be present");
        String form = "email=" + URLEncoder.encode(email, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8)
                + "&_csrf=" + URLEncoder.encode(m.group(1), StandardCharsets.UTF_8);
        HttpResponse<byte[]> result = client.send(HttpRequest.newBuilder(URI.create(base("/admin/login")))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(302, result.statusCode());
    }

    @Test
    void defaultUnauthenticatedAndMemberUsersCannotDownloadCertificate() throws Exception {
        assertEquals(302, get("/admin/dev/root-ca.crt").statusCode());
        login("devca-member@example.org", "known-member-password");
        assertEquals(403, get("/admin/dev/root-ca.crt").statusCode());
    }

    @Test
    void adminReceivesOnlyPublicCertificateWithNoStoreHeaders() throws Exception {
        login("devca-admin@example.org", "known-test-password");
        assertEquals(200, get("/admin/").statusCode());
        assertTrue(new String(get("/admin/").body(), StandardCharsets.UTF_8)
                .contains("/admin/dev/root-ca.crt"));
        HttpResponse<byte[]> response = get("/admin/dev/root-ca.crt");
        assertEquals(200, response.statusCode());
        assertEquals("no-store", response.headers().firstValue("cache-control").orElse(""));
        assertEquals("nosniff", response.headers().firstValue("x-content-type-options").orElse(""));
        assertTrue(response.headers().firstValue("content-disposition").orElse("")
                .contains("attachment"));
        X509Certificate ca = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new java.io.ByteArrayInputStream(response.body()));
        assertTrue(ca.getBasicConstraints() >= 0);
        assertArrayEquals(ca.getEncoded(), response.body());

        for (String path : new String[] {
                "/admin/dev/rootCA-key.pem", "/admin/dev/dev-key.pem",
                "/admin/dev/../rootCA-key.pem", "/admin/dev/root-ca.crt?file=dev-key.pem",
                "/admin/dev/root-ca.crt/secret"}) {
            assertNotEquals(200, get(path).statusCode(), path);
        }
    }

    @Test
    void malformedOrPrivateMaterialIsRejectedBeforeStartup(@TempDir Path folder) throws Exception {
        Path ca = folder.resolve("rootCA.pem");
        Files.writeString(ca, "-----BEGIN PRIVATE KEY-----\\nBAD\\n-----END PRIVATE KEY-----\\n");
        assertThrows(IllegalStateException.class, () -> new DevCaController(ca.toString()));
        Files.writeString(ca, "not a PEM certificate");
        assertThrows(IllegalStateException.class, () -> new DevCaController(ca.toString()));
        assertThrows(IllegalStateException.class, () -> new DevCaController(folder.toString()));
        assertThrows(IllegalStateException.class, () -> new DevCaController(""));
    }
}
