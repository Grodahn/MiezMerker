package org.miezmerker.backend;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.miezmerker.backend.crypto.CredentialIssuerService;
import org.miezmerker.backend.crypto.EcKeyUtils;
import org.miezmerker.backend.crypto.OfflineAuthService;
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
import org.miezmerker.backend.support.TestDatabaseCleaner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Acceptance tests for #17: signed offline BLE credentials and AppDevice identities.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OfflineCredentialTest {
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
    @Autowired TestDatabaseCleaner cleaner;
    @Autowired CredentialIssuerService issuer;
    @Autowired OfflineAuthService offline;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;

    final ObjectMapper mapper = new ObjectMapper();
    HttpClient client;
    CookieManager cookies;

    @BeforeEach
    void http() {
        cookies = new CookieManager();
        client = HttpClient.newBuilder().cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(5)).build();
    }

    String base(String path) {
        return "http://localhost:" + port + path;
    }

    String csrf() throws Exception {
        var res = client.send(HttpRequest.newBuilder(URI.create(base("/api/v1/auth/csrf")))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode());
        return mapper.readTree(res.body()).get("token").asText();
    }

    HttpResponse<String> post(String path, String json) throws Exception {
        String token = csrf();
        return client.send(HttpRequest.newBuilder(URI.create(base(path)))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", token)
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base(path))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    void login(String email, String password) throws Exception {
        var res = post("/api/v1/auth/login",
                "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
        assertEquals(200, res.statusCode(), res.body());
    }

    record Seed(Organization orgA, Organization orgB, AppUser adminA, AppUser memberA) {}

    Seed seed() {
        cleaner.clean();
        Organization orgA = organizations.save(new Organization("orga", "Org A", "contact-a@example.org"));
        Organization orgB = organizations.save(new Organization("orgb", "Org B", null));
        AppUser adminA = users.save(new AppUser("admin-a@example.org", passwords.encode("supersecret-password-a")));
        AppUser memberA = users.save(new AppUser("member-a@example.org", passwords.encode("supersecret-password-m")));
        memberships.save(new OrganizationMembership(orgA, adminA, MembershipRole.ADMIN, MembershipStatus.ACTIVE));
        memberships.save(new OrganizationMembership(orgA, memberA, MembershipRole.MEMBER, MembershipStatus.ACTIVE));
        AppUser adminB = users.save(new AppUser("admin-b@example.org", passwords.encode("supersecret-password-b")));
        memberships.save(new OrganizationMembership(orgB, adminB, MembershipRole.ADMIN, MembershipStatus.ACTIVE));
        return new Seed(orgA, orgB, adminA, memberA);
    }

    record DeviceKeys(KeyPair kp, String x, String y, String fingerprint) {}

    static DeviceKeys newDevice() {
        KeyPair kp = EcKeyUtils.generateP256();
        ECPublicKey pub = (ECPublicKey) kp.getPublic();
        String x = EcKeyUtils.xOf(pub);
        String y = EcKeyUtils.yOf(pub);
        return new DeviceKeys(kp, x, y, EcKeyUtils.fingerprintOf(pub));
    }

    String registerDevice(String x, String y) throws Exception {
        var res = post("/api/v1/devices",
                "{\"publicKeyX\":\"" + x + "\",\"publicKeyY\":\"" + y + "\"}");
        assertEquals(200, res.statusCode(), res.body());
        return mapper.readTree(res.body()).get("id").asText();
    }

    String issueCredential(String deviceId, UUID orgId) throws Exception {
        var res = post("/api/v1/devices/" + deviceId + "/credentials",
                "{\"organizationId\":\"" + orgId + "\"}");
        assertEquals(200, res.statusCode(), res.body());
        var body = mapper.readTree(res.body());
        assertEquals("Offline-BLE-ES256-JWT", body.get("tokenType").asText());
        return body.get("credential").asText();
    }

    @Test
    void memberAndAdminCredentialsVerifyOffline() throws Exception {
        Seed s = seed();
        login("member-a@example.org", "supersecret-password-m");
        DeviceKeys dev = newDevice();
        String deviceId = registerDevice(dev.x(), dev.y());
        String credential = issueCredential(deviceId, s.orgA().getId());

        // Offline verification needs only the issuer public key: no backend contact.
        var verified = offline.verifyForOrganization(credential, s.orgA().getId());
        assertEquals("MEMBER", verified.role());
        assertEquals(dev.fingerprint(), verified.deviceFingerprint());

        byte[] challenge = offline.newChallenge();
        assertEquals(32, challenge.length);
        byte[] sig = EcKeyUtils.signRaw(dev.kp().getPrivate(), challenge);
        assertEquals(64, sig.length);
        assertTrue(offline.verifyProofOfPossession(dev.x(), dev.y(), challenge, sig));
        assertTrue(offline.authorizeSync(credential, s.orgA().getId(), challenge, sig));

        // ADMIN credential also works.
        login("admin-a@example.org", "supersecret-password-a");
        DeviceKeys adminDev = newDevice();
        String adminDeviceId = registerDevice(adminDev.x(), adminDev.y());
        String adminCred = issueCredential(adminDeviceId, s.orgA().getId());
        var adminVerified = offline.verifyForOrganization(adminCred, s.orgA().getId());
        assertEquals("ADMIN", adminVerified.role());
        byte[] c2 = offline.newChallenge();
        byte[] s2 = EcKeyUtils.signRaw(adminDev.kp().getPrivate(), c2);
        assertTrue(offline.authorizeSync(adminCred, s.orgA().getId(), c2, s2));
    }

    @Test
    void credentialForWrongOrganizationIsRejected() throws Exception {
        Seed s = seed();
        login("member-a@example.org", "supersecret-password-m");
        DeviceKeys dev = newDevice();
        String deviceId = registerDevice(dev.x(), dev.y());
        String credential = issueCredential(deviceId, s.orgA().getId());

        // Presented at org B's node: must fail.
        assertFalse(offline.authorizeSync(credential, s.orgB().getId(), offline.newChallenge(),
                EcKeyUtils.signRaw(dev.kp().getPrivate(), new byte[32])));
        try {
            offline.verifyForOrganization(credential, s.orgB().getId());
            fail("wrong-org credential must be rejected");
        } catch (IllegalArgumentException expected) {
        }
        // Even with a valid signature over the attacker's challenge, org binding fails first.
        byte[] challenge = offline.newChallenge();
        byte[] sig = EcKeyUtils.signRaw(dev.kp().getPrivate(), challenge);
        assertFalse(offline.authorizeSync(credential, s.orgB().getId(), challenge, sig));
    }

    @Test
    void expiredAndTamperedCredentialsAreRejected() throws Exception {
        Seed s = seed();
        login("member-a@example.org", "supersecret-password-m");
        DeviceKeys dev = newDevice();
        String deviceId = registerDevice(dev.x(), dev.y());
        UUID userId = users.findByEmail("member-a@example.org").orElseThrow().getId();

        // Expired: issued 10 days ago, expired 9 days ago.
        String expired = issuer.issueOfflineCredentialWithTimes(userId, s.orgA().getId(),
                s.orgA().getSlug(), UUID.fromString(deviceId), dev.x(), dev.y(),
                dev.fingerprint(), "MEMBER",
                Instant.now().minusSeconds(10 * 24 * 3600),
                Instant.now().minusSeconds(9 * 24 * 3600));
        try {
            offline.verifyForOrganization(expired, s.orgA().getId());
            fail("expired credential must be rejected");
        } catch (IllegalArgumentException expected) {
        }

        // Tampered payload: flip one char in the JWT body.
        String valid = issueCredential(deviceId, s.orgA().getId());
        String[] parts = valid.split("\\.");
        assertEquals(3, parts.length);
        String payload = parts[1];
        char flipped = payload.charAt(5) == 'A' ? 'B' : 'A';
        String tampered = parts[0] + "." + payload.substring(0, 5) + flipped
                + payload.substring(6) + "." + parts[2];
        try {
            offline.verifyForOrganization(tampered, s.orgA().getId());
            fail("tampered credential must be rejected");
        } catch (Exception expected) {
        }
    }

    @Test
    void copiedCredentialWithoutPrivateKeyIsUseless() throws Exception {
        Seed s = seed();
        login("member-a@example.org", "supersecret-password-m");
        DeviceKeys victim = newDevice();
        String deviceId = registerDevice(victim.x(), victim.y());
        String credential = issueCredential(deviceId, s.orgA().getId());

        // Attacker copies the JWT but owns a different private key.
        DeviceKeys attacker = newDevice();
        byte[] challenge = offline.newChallenge();
        byte[] attackerSig = EcKeyUtils.signRaw(attacker.kp().getPrivate(), challenge);
        assertFalse(offline.verifyProofOfPossession(victim.x(), victim.y(), challenge, attackerSig));
        assertFalse(offline.authorizeSync(credential, s.orgA().getId(), challenge, attackerSig));

        // Random garbage signature also fails.
        assertFalse(offline.authorizeSync(credential, s.orgA().getId(), challenge, new byte[64]));
    }

    @Test
    void freshChallengeSucceedsButReplayOnDifferentChallengeFails() throws Exception {
        Seed s = seed();
        login("member-a@example.org", "supersecret-password-m");
        DeviceKeys dev = newDevice();
        String deviceId = registerDevice(dev.x(), dev.y());
        String credential = issueCredential(deviceId, s.orgA().getId());

        byte[] challenge1 = offline.newChallenge();
        byte[] sig1 = EcKeyUtils.signRaw(dev.kp().getPrivate(), challenge1);
        assertTrue(offline.authorizeSync(credential, s.orgA().getId(), challenge1, sig1));

        // Replay sig1 against a fresh challenge2: must fail (signature binds the challenge).
        byte[] challenge2 = offline.newChallenge();
        assertFalse(challenge1.length == 0);
        if (java.util.Arrays.equals(challenge1, challenge2)) {
            challenge2[0] ^= 0x01;
        }
        assertFalse(offline.authorizeSync(credential, s.orgA().getId(), challenge2, sig1));
        // Wrong challenge length fails as well.
        assertFalse(offline.authorizeSync(credential, s.orgA().getId(), new byte[16], sig1));
    }

    @Test
    void revocationSerializesWithIssuanceAndReregistration() throws Exception {
        Seed s = seed();
        login("member-a@example.org", "supersecret-password-m");
        DeviceKeys dev = newDevice();
        String deviceId = registerDevice(dev.x(), dev.y());
        String token = csrf();
        var requests = java.util.List.of(
                HttpRequest.newBuilder(URI.create(base("/api/v1/devices/" + deviceId + "/credentials")))
                    .header("Content-Type", "application/json").header("X-XSRF-TOKEN", token)
                    .POST(HttpRequest.BodyPublishers.ofString("{\"organizationId\":\"" + s.orgA().getId() + "\"}")).build(),
                HttpRequest.newBuilder(URI.create(base("/api/v1/devices")))
                    .header("Content-Type", "application/json").header("X-XSRF-TOKEN", token)
                    .POST(HttpRequest.BodyPublishers.ofString("{\"publicKeyX\":\"" + dev.x()
                            + "\",\"publicKeyY\":\"" + dev.y() + "\"}")).build());
        var pending = new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .execute(status -> {
                    var locked = devices.findLockedById(UUID.fromString(deviceId)).orElseThrow();
                    var responses = requests.stream().map(request -> client.sendAsync(request,
                            HttpResponse.BodyHandlers.ofString())).toList();
                    for (var response : responses) {
                        assertThrows(java.util.concurrent.TimeoutException.class,
                                () -> response.get(500, java.util.concurrent.TimeUnit.MILLISECONDS));
                    }
                    locked.revoke();
                    devices.saveAndFlush(locked);
                    return responses;
                });
        for (var response : pending) {
            var denied = response.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(403, denied.statusCode(), denied.body());
        }
        assertTrue(devices.findById(UUID.fromString(deviceId)).orElseThrow().isRevoked());
    }

    @Test
    void revokedDeviceReceivesNoNewCredential() throws Exception {
        Seed s = seed();
        login("member-a@example.org", "supersecret-password-m");
        DeviceKeys dev = newDevice();
        String deviceId = registerDevice(dev.x(), dev.y());
        // First issuance works.
        issueCredential(deviceId, s.orgA().getId());

        var revoked = post("/api/v1/devices/" + deviceId + "/revoke", "{}");
        assertEquals(200, revoked.statusCode(), revoked.body());

        var denied = post("/api/v1/devices/" + deviceId + "/credentials",
                "{\"organizationId\":\"" + s.orgA().getId() + "\"}");
        assertEquals(403, denied.statusCode(), denied.body());
    }

    @Test
    void pendingMembershipReceivesNoCredential() throws Exception {
        Seed s = seed();
        AppUser pending = users.save(
                new AppUser("pending@example.org", passwords.encode("supersecret-password-p")));
        memberships.save(new OrganizationMembership(s.orgA(), pending, MembershipRole.MEMBER,
                MembershipStatus.PENDING));
        login("pending@example.org", "supersecret-password-p");
        DeviceKeys dev = newDevice();
        String deviceId = registerDevice(dev.x(), dev.y());
        var denied = post("/api/v1/devices/" + deviceId + "/credentials",
                "{\"organizationId\":\"" + s.orgA().getId() + "\"}");
        assertEquals(403, denied.statusCode(), denied.body());
    }

    @Test
    void offCurveDeviceRegistrationIsRejected() throws Exception {
        seed();
        login("member-a@example.org", "supersecret-password-m");
        String zero = EcKeyUtils.b64u(new byte[32]);
        var response = post("/api/v1/devices", "{\"publicKeyX\":\"" + zero
                + "\",\"publicKeyY\":\"" + zero + "\"}");
        assertEquals(400, response.statusCode(), response.body());
    }

    @Test
    void recentlyExpiredAndFutureCredentialsAreRejected() throws Exception {
        Seed s = seed();
        DeviceKeys dev = newDevice();
        Instant now = Instant.now();
        for (Instant[] times : new Instant[][] {
                {now.minusSeconds(60), now.minusSeconds(1)},
                {now.plusSeconds(60), now.plusSeconds(3600)},
                {now, now.minusSeconds(1)}}) {
            String credential = issuer.issueOfflineCredentialWithTimes(s.memberA().getId(),
                    s.orgA().getId(), s.orgA().getSlug(), UUID.randomUUID(), dev.x(), dev.y(),
                    dev.fingerprint(), "MEMBER", times[0], times[1]);
            assertThrows(IllegalArgumentException.class,
                    () -> offline.verifyForOrganization(credential, s.orgA().getId()));
        }
        assertThrows(IllegalArgumentException.class, () -> new CredentialIssuerService("", "", 0));
        assertThrows(IllegalArgumentException.class, () -> new CredentialIssuerService("", "", -1));
        assertThrows(ArithmeticException.class,
                () -> new CredentialIssuerService("", "", Long.MAX_VALUE));
    }

    @Test
    void fingerprintBindingPreventsSubstitution() throws Exception {
        // A credential whose fingerprint does not match its embedded key is rejected,
        // even if the issuer signature were somehow valid (defense in depth, also checked offline).
        Seed s = seed();
        DeviceKeys dev = newDevice();
        UUID userId = users.findByEmail("member-a@example.org").orElseThrow().getId();
        String forged = issuer.issueOfflineCredentialWithTimes(userId, s.orgA().getId(),
                s.orgA().getSlug(), UUID.randomUUID(), dev.x(), dev.y(),
                EcKeyUtils.b64u(new byte[32]), // wrong fingerprint, valid base64url shape
                "MEMBER", Instant.now(), Instant.now().plusSeconds(3600));
        try {
            offline.verifyForOrganization(forged, s.orgA().getId());
            fail("fingerprint substitution must be rejected");
        } catch (IllegalArgumentException expected) {
        }
    }
}
