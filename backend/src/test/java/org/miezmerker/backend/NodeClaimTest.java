package org.miezmerker.backend;

import org.junit.jupiter.api.Tag;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.security.interfaces.ECPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.miezmerker.backend.crypto.CredentialIssuerService;
import org.miezmerker.backend.crypto.EcKeyUtils;
import org.miezmerker.backend.crypto.NodeClaimVerifier;
import org.miezmerker.backend.domain.AppUser;
import org.miezmerker.backend.domain.MembershipRole;
import org.miezmerker.backend.domain.MembershipStatus;
import org.miezmerker.backend.domain.Organization;
import org.miezmerker.backend.domain.OrganizationMembership;
import org.miezmerker.backend.domain.NodeDevice;
import org.miezmerker.backend.repo.AppUserRepository;
import org.miezmerker.backend.repo.MembershipRepository;
import org.miezmerker.backend.repo.NodeRepository;
import org.miezmerker.backend.repo.OrganizationRepository;
import org.miezmerker.backend.support.TestDatabaseCleaner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Acceptance tests for #18: node identity and organization claiming/provisioning.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Tag("node")
@Tag("auth")
class NodeClaimTest {
    @Value("${local.server.port}") int port;

    @Autowired AppUserRepository users;
    @Autowired OrganizationRepository organizations;
    @Autowired MembershipRepository memberships;
    @Autowired NodeRepository nodes;
    @Autowired PasswordEncoder passwords;
    @Autowired TestDatabaseCleaner cleaner;
    @Autowired CredentialIssuerService issuer;
    @Autowired org.miezmerker.backend.crypto.BootstrapTimeSource bootstrapClock;
    @Autowired org.miezmerker.backend.crypto.SecurityClockPolicy clockPolicy;
    @Autowired org.miezmerker.backend.web.ClaimDiagnosticsController diagnostics;

    final ObjectMapper mapper = new ObjectMapper();
    HttpClient client;
    CookieManager cookies;
    // Capture only structured diagnostic events, independent of which test
    // first created the shared Spring context and console output stream.
    final ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> diagnosticEvents = new ch.qos.logback.core.read.ListAppender<>();
    final ch.qos.logback.classic.Logger diagnosticLogger = (ch.qos.logback.classic.Logger)
        org.slf4j.LoggerFactory.getLogger(org.miezmerker.backend.diagnostics.ClaimDiagnostics.class);
    final DiagnosticOutput output = new DiagnosticOutput();
    class DiagnosticOutput {
        String getOut() {
            synchronized (diagnosticEvents.list) {
                return diagnosticEvents.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .collect(java.util.stream.Collectors.joining("\n"));
            }
        }
    }

    @BeforeEach
    void http() {
        diagnosticEvents.list = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        diagnosticEvents.start();
        diagnosticLogger.addAppender(diagnosticEvents);
        cookies = new CookieManager();
        client = HttpClient.newBuilder().cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(5)).build();
    }
    @org.junit.jupiter.api.AfterEach
    void stopDiagnosticCapture() {
        diagnosticLogger.detachAppender(diagnosticEvents);
        diagnosticEvents.stop();
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

    record Seed(Organization orgA, Organization orgB) {}

    Seed seed() {
        cleaner.clean();
        Organization orgA = organizations.save(
                new Organization("orga", "Org A", "help-a@example.org"));
        Organization orgB = organizations.save(new Organization("orgb", "Org B", null));
        AppUser adminA = users.save(new AppUser("admin-a@example.org", passwords.encode("supersecret-password-a")));
        AppUser memberA = users.save(new AppUser("member-a@example.org", passwords.encode("supersecret-password-m")));
        AppUser adminB = users.save(new AppUser("admin-b@example.org", passwords.encode("supersecret-password-b")));
        memberships.save(new OrganizationMembership(orgA, adminA, MembershipRole.ADMIN, MembershipStatus.ACTIVE));
        memberships.save(new OrganizationMembership(orgA, memberA, MembershipRole.MEMBER, MembershipStatus.ACTIVE));
        memberships.save(new OrganizationMembership(orgB, adminB, MembershipRole.ADMIN, MembershipStatus.ACTIVE));
        return new Seed(orgA, orgB);
    }

    record NodeKeys(UUID nodeId, KeyPair kp, String x, String y) {
        static NodeKeys fresh() {
            KeyPair kp = EcKeyUtils.generateP256();
            ECPublicKey pub = (ECPublicKey) kp.getPublic();
            return new NodeKeys(UUID.randomUUID(), kp, EcKeyUtils.xOf(pub), EcKeyUtils.yOf(pub));
        }
    }

    String claimJson(NodeKeys n, UUID orgId, long timestamp, String signature) {
        return "{\"nodeId\":\"" + n.nodeId() + "\",\"publicKeyX\":\"" + n.x()
                + "\",\"publicKeyY\":\"" + n.y() + "\",\"firmwareVersion\":\"test-1\","
                + "\"organizationId\":\"" + orgId + "\",\"timestampMillis\":" + timestamp
                + ",\"claimSignature\":\"" + signature + "\"}";
    }

    @Test
    void unauthenticatedDisabledUserAndDisabledOrganizationCannotClaim() throws Exception {
        Seed s = seed();
        NodeKeys n = NodeKeys.fresh();
        long ts = Instant.now().toEpochMilli();
        String sig = NodeClaimVerifier.signClaim(n.kp().getPrivate(), n.nodeId(), n.x(), n.y(), ts);
        String body = claimJson(n, s.orgA().getId(), ts, sig);
        assertEquals(401, post("/api/v1/nodes/claim", body).statusCode());
        login("admin-a@example.org", "supersecret-password-a");
        var user = users.findByEmail("admin-a@example.org").orElseThrow();
        user.setStatus(org.miezmerker.backend.domain.UserStatus.DISABLED);
        users.save(user);
        assertEquals(403, post("/api/v1/nodes/claim", body).statusCode());
        user.setStatus(org.miezmerker.backend.domain.UserStatus.ACTIVE);
        users.save(user);
        s.orgA().setStatus(org.miezmerker.backend.domain.OrganizationStatus.DISABLED);
        organizations.save(s.orgA());
        assertEquals(403, post("/api/v1/nodes/claim", body).statusCode());
        assertEquals(0, nodes.count());
    }

    @Test
    void escapedOwnerTextCannotCommitAnUndeliverableReceipt() throws Exception {
        Seed s = seed();
        s.orgA().setDisplayName(Character.toString(1).repeat(255));
        s.orgA().setPublicContact(Character.toString(1).repeat(500));
        organizations.saveAndFlush(s.orgA());
        login("admin-a@example.org", "supersecret-password-a");
        NodeKeys n = NodeKeys.fresh();
        long ts = Instant.now().toEpochMilli();
        String sig = NodeClaimVerifier.signClaim(n.kp().getPrivate(), n.nodeId(), n.x(), n.y(), ts);
        assertEquals(422, post("/api/v1/nodes/claim", claimJson(n, s.orgA().getId(), ts, sig)).statusCode());
        assertFalse(nodes.existsById(n.nodeId()));
        s.orgA().setDisplayName("ä".repeat(255));
        s.orgA().setPublicContact(new String(Character.toChars(0x1f63a)).repeat(250));
        organizations.saveAndFlush(s.orgA());
        assertEquals(200, post("/api/v1/nodes/claim", claimJson(n, s.orgA().getId(), ts, sig)).statusCode());
    }

    @Test
    void regularAdminClaimsUnclaimedNodeWithoutPhysicalRelease() throws Exception {
        assertEquals(org.miezmerker.backend.crypto.SecurityClockPolicy.Mode.FIELD_ALPHA_PHONE_CLOCK, clockPolicy.mode());
        // No signed UTC proof/health prerequisite for a legitimate alpha claim.
        bootstrapClock.reportHealthy(false);
        assertThrows(IllegalStateException.class, () -> bootstrapClock.issuanceMillis());
        Seed s = seed();
        login("admin-a@example.org", "supersecret-password-a");
        NodeKeys n = NodeKeys.fresh();
        long ts = Instant.now().toEpochMilli();
        String sig = NodeClaimVerifier.signClaim(n.kp().getPrivate(), n.nodeId(), n.x(), n.y(), ts);

        var res = post("/api/v1/nodes/claim", claimJson(n, s.orgA().getId(), ts, sig));
        assertEquals(200, res.statusCode(), res.body());
        var body = mapper.readTree(res.body());
        assertEquals(n.nodeId().toString(), body.get("nodeId").asText());
        assertNotNull(body.get("receipt").asText());

        // Claim receipt verifies against the issuer trust anchor and names the org.
        var claims = issuer.verify(body.get("receipt").asText());
        assertEquals("claim", claims.getStringClaim("kind"));
        assertEquals(s.orgA().getId().toString(), claims.getStringClaim("org"));
        assertEquals("Org A", claims.getStringClaim("org_name"));

        // Node is now listed in its organization.
        var list = get("/api/v1/nodes?organizationId=" + s.orgA().getId());
        assertEquals(200, list.statusCode(), list.body());
        assertTrue(list.body().contains(n.nodeId().toString()));
        var detail = mapper.readTree(get("/api/v1/nodes/" + n.nodeId()).body());
        assertEquals(n.x(), detail.get("publicKeyX").asText());
        assertEquals(n.y(), detail.get("publicKeyY").asText());
        assertEquals(EcKeyUtils.fingerprintOfXY(n.x(), n.y()), detail.get("fingerprint").asText());
    }

    @Test
    void memberCannotClaim() throws Exception {
        Seed s = seed();
        login("member-a@example.org", "supersecret-password-m");
        NodeKeys n = NodeKeys.fresh();
        long ts = Instant.now().toEpochMilli();
        String sig = NodeClaimVerifier.signClaim(n.kp().getPrivate(), n.nodeId(), n.x(), n.y(), ts);
        var res = post("/api/v1/nodes/claim", claimJson(n, s.orgA().getId(), ts, sig));
        assertEquals(403, res.statusCode(), res.body());
    }

    @Test
    void adminCannotClaimWithForgedOrStaleIdentityProof() throws Exception {
        Seed s = seed();
        login("admin-a@example.org", "supersecret-password-a");
        NodeKeys n = NodeKeys.fresh();
        long ts = Instant.now().toEpochMilli();
        // Forged node identity: signature over garbage / wrong key.
        KeyPair other = EcKeyUtils.generateP256();
        String badSig = NodeClaimVerifier.signClaim(other.getPrivate(), n.nodeId(), n.x(), n.y(), ts);
        var res = post("/api/v1/nodes/claim", claimJson(n, s.orgA().getId(), ts, badSig));
        assertEquals(403, res.statusCode(), res.body());

        // Stale advertisement is also rejected.
        long stale = Instant.now().minusSeconds(3600).toEpochMilli();
        String staleSig = NodeClaimVerifier.signClaim(n.kp().getPrivate(), n.nodeId(), n.x(), n.y(), stale);
        var res2 = post("/api/v1/nodes/claim", claimJson(n, s.orgA().getId(), stale, staleSig));
        assertEquals(403, res2.statusCode(), res2.body());
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (!(output.getOut().contains("code=MM-CLAIM-005") && output.getOut().contains("code=MM-BLE-013"))
                && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(output.getOut().contains("code=MM-CLAIM-005"));
        assertTrue(output.getOut().contains("code=MM-BLE-013"));
        assertFalse(output.getOut().contains(badSig));
        assertFalse(output.getOut().contains(staleSig));
    }

    @Test
    void interruptedClaimRetriesDeterministically() throws Exception {
        Seed s = seed();
        login("admin-a@example.org", "supersecret-password-a");
        NodeKeys n = NodeKeys.fresh();
        long ts = Instant.now().toEpochMilli();
        String sig = NodeClaimVerifier.signClaim(n.kp().getPrivate(), n.nodeId(), n.x(), n.y(), ts);
        var first = post("/api/v1/nodes/claim", claimJson(n, s.orgA().getId(), ts, sig));
        assertEquals(200, first.statusCode(), first.body());

        // Simulate interrupted commit before the node persisted: retry with a fresh timestamp.
        long ts2 = Instant.now().toEpochMilli();
        String sig2 = NodeClaimVerifier.signClaim(n.kp().getPrivate(), n.nodeId(), n.x(), n.y(), ts2);
        var retry = post("/api/v1/nodes/claim", claimJson(n, s.orgA().getId(), ts2, sig2));
        assertEquals(200, retry.statusCode(), retry.body());
        assertEquals(n.nodeId().toString(),
                mapper.readTree(retry.body()).get("nodeId").asText());

        // Exactly one node row exists; no half-claimed duplicate.
        assertEquals(1, nodes.findAll().size());
    }

    @Test
    void claimedNodeRejectsAnotherOrganization() throws Exception {
        Seed s = seed();
        login("admin-a@example.org", "supersecret-password-a");
        NodeKeys n = NodeKeys.fresh();
        long ts = Instant.now().toEpochMilli();
        String sig = NodeClaimVerifier.signClaim(n.kp().getPrivate(), n.nodeId(), n.x(), n.y(), ts);
        var first = post("/api/v1/nodes/claim", claimJson(n, s.orgA().getId(), ts, sig));
        assertEquals(200, first.statusCode(), first.body());

        login("admin-b@example.org", "supersecret-password-b");
        long ts2 = Instant.now().toEpochMilli();
        String sig2 = NodeClaimVerifier.signClaim(n.kp().getPrivate(), n.nodeId(), n.x(), n.y(), ts2);
        var takeover = post("/api/v1/nodes/claim", claimJson(n, s.orgB().getId(), ts2, sig2));
        assertEquals(409, takeover.statusCode(), takeover.body());
    }

    @Test
    void foreignOrganizationSeesOnlyPublicOwnerMetadata() throws Exception {
        Seed s = seed();
        login("admin-a@example.org", "supersecret-password-a");
        NodeKeys n = NodeKeys.fresh();
        long ts = Instant.now().toEpochMilli();
        String sig = NodeClaimVerifier.signClaim(n.kp().getPrivate(), n.nodeId(), n.x(), n.y(), ts);
        assertEquals(200, post("/api/v1/nodes/claim", claimJson(n, s.orgA().getId(), ts, sig)).statusCode());

        // Foreign user: public owner hint works without login.
        cookies = new CookieManager();
        client = HttpClient.newBuilder().cookieHandler(cookies).build();
        var hidden = get("/api/v1/nodes/" + n.nodeId() + "/owner");
        assertEquals(200, hidden.statusCode(), hidden.body());
        assertEquals("CLAIMED", mapper.readTree(hidden.body()).get("state").asText());
        assertTrue(mapper.readTree(hidden.body()).get("organizationId").isNull());
        assertTrue(mapper.readTree(hidden.body()).get("publicContact").isNull());
        assertFalse(hidden.body().contains("Org A"));
        // Public owner metadata is deliberate only for discoverable owners.
        s.orgA().setDiscoverable(true);
        organizations.save(s.orgA());
        var owner = get("/api/v1/nodes/" + n.nodeId() + "/owner");
        assertEquals(200, owner.statusCode(), owner.body());
        var body = mapper.readTree(owner.body());
        assertEquals(s.orgA().getId().toString(), body.get("organizationId").asText());
        assertEquals("Org A", body.get("organizationName").asText());
        assertEquals("help-a@example.org", body.get("publicContact").asText());
        assertFalse(owner.body().contains("chip"));
        assertFalse(owner.body().contains("observation"));
        assertFalse(owner.body().contains("publicKey"));
        assertFalse(owner.body().contains("fingerprint"));

        // Foreign user cannot read the full node record.
        login("admin-b@example.org", "supersecret-password-b");
        assertEquals(403, get("/api/v1/nodes/" + n.nodeId()).statusCode());
        assertEquals(403, get("/api/v1/nodes?organizationId=" + s.orgA().getId()).statusCode());
    }

    @Test
    void fakeNodeWithoutDevicePrivateKeyCannotAuthenticate() throws Exception {
        Seed s = seed();
        login("admin-a@example.org", "supersecret-password-a");
        // Attacker copies a nodeId but cannot sign with the real node private key.
        NodeKeys real = NodeKeys.fresh();
        UUID copiedId = real.nodeId();
        KeyPair fakeKp = EcKeyUtils.generateP256();
        ECPublicKey fakePub = (ECPublicKey) fakeKp.getPublic();
        String fx = EcKeyUtils.xOf(fakePub);
        String fy = EcKeyUtils.yOf(fakePub);
        long ts = Instant.now().toEpochMilli();
        // Attacker signs with the WRONG key for the copied id+fake pubkey combo is self-consistent,
        // so this claim would create a DIFFERENT identity only if nodeId were fresh. With the copied
        // nodeId + different pubkey the backend must reject after the real claim exists.
        long tsReal = Instant.now().toEpochMilli();
        String realSig = NodeClaimVerifier.signClaim(real.kp().getPrivate(), real.nodeId(),
                real.x(), real.y(), tsReal);
        String realJson = claimJson(real, s.orgA().getId(), tsReal, realSig);
        assertEquals(200, post("/api/v1/nodes/claim", realJson).statusCode());

        String fakeSig = NodeClaimVerifier.signClaim(fakeKp.getPrivate(), copiedId, fx, fy, ts);
        String fakeJson = "{\"nodeId\":\"" + copiedId + "\",\"publicKeyX\":\"" + fx
                + "\",\"publicKeyY\":\"" + fy + "\",\"firmwareVersion\":\"fake\","
                + "\"organizationId\":\"" + s.orgA().getId() + "\",\"timestampMillis\":" + ts
                + ",\"claimSignature\":\"" + fakeSig + "\"}";
        var res = post("/api/v1/nodes/claim", fakeJson);
        // Same nodeId with a different device key: identity collision -> conflict, no takeover.
        assertEquals(409, res.statusCode(), res.body());
    }

    @Test
    void sameOwnerCanRecoverReceiptWithOriginalExpiredProof() throws Exception {
        Seed s = seed();
        NodeKeys n = NodeKeys.fresh();
        NodeDevice committed = new NodeDevice(n.nodeId(), n.x(), n.y(),
                EcKeyUtils.fingerprintOfXY(n.x(), n.y()), "test-1");
        committed.claim(s.orgA());
        nodes.save(committed);
        long ts = Instant.now().minusSeconds(3600).toEpochMilli();
        String sig = NodeClaimVerifier.signClaim(n.kp().getPrivate(), n.nodeId(), n.x(), n.y(), ts);
        login("admin-a@example.org", "supersecret-password-a");
        assertEquals(200, post("/api/v1/nodes/claim",
                claimJson(n, s.orgA().getId(), ts, sig)).statusCode());
        String wrong = NodeClaimVerifier.signClaim(EcKeyUtils.generateP256().getPrivate(),
                n.nodeId(), n.x(), n.y(), ts);
        assertEquals(403, post("/api/v1/nodes/claim",
                claimJson(n, s.orgA().getId(), ts, wrong)).statusCode());
        login("admin-b@example.org", "supersecret-password-b");
        assertEquals(403, post("/api/v1/nodes/claim",
                claimJson(n, s.orgB().getId(), ts, sig)).statusCode());
        assertEquals(s.orgA().getId(), nodes.findById(n.nodeId()).orElseThrow()
                .getOrganization().getId());
    }

    @Test
    void concurrentClaimsCannotOverwriteOwnership() throws Exception {
        // Cover both an absent factory-new identity and a pre-registered UNCLAIMED row.
        for (boolean preregistered : new boolean[] {false, true}) {
            Seed s = seed();
            NodeKeys n = NodeKeys.fresh();
            if (preregistered) nodes.save(new NodeDevice(n.nodeId(), n.x(), n.y(),
                    EcKeyUtils.fingerprintOfXY(n.x(), n.y()), "test-1"));
            login("admin-a@example.org", "supersecret-password-a");
            HttpClient clientA = client;
            String tokenA = csrf();
            http();
            login("admin-b@example.org", "supersecret-password-b");
            HttpClient clientB = client;
            String tokenB = csrf();
            long ts = Instant.now().toEpochMilli();
            String sig = NodeClaimVerifier.signClaim(n.kp().getPrivate(), n.nodeId(), n.x(), n.y(), ts);
            var requestA = HttpRequest.newBuilder(URI.create(base("/api/v1/nodes/claim")))
                    .header("Content-Type", "application/json").header("X-XSRF-TOKEN", tokenA)
                    .POST(HttpRequest.BodyPublishers.ofString(claimJson(n, s.orgA().getId(), ts, sig))).build();
            var requestB = HttpRequest.newBuilder(URI.create(base("/api/v1/nodes/claim")))
                    .header("Content-Type", "application/json").header("X-XSRF-TOKEN", tokenB)
                    .POST(HttpRequest.BodyPublishers.ofString(claimJson(n, s.orgB().getId(), ts, sig))).build();
            var a = clientA.sendAsync(requestA, HttpResponse.BodyHandlers.ofString());
            var b = clientB.sendAsync(requestB, HttpResponse.BodyHandlers.ofString());
            var responseA = a.get(10, java.util.concurrent.TimeUnit.SECONDS);
            var responseB = b.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(java.util.Set.of(200, 409),
                    java.util.Set.of(responseA.statusCode(), responseB.statusCode()));
            UUID winner = responseA.statusCode() == 200 ? s.orgA().getId() : s.orgB().getId();
            assertEquals(winner, nodes.findById(n.nodeId()).orElseThrow().getOrganization().getId());
            assertEquals(1, nodes.count());
        }
    }

    @Test
    void inactiveAdminsCannotClaim() throws Exception {
        for (MembershipStatus status : new MembershipStatus[] {
                MembershipStatus.PENDING, MembershipStatus.DISABLED}) {
            Seed s = seed();
            AppUser inactive = users.save(new AppUser("inactive@example.org", passwords.encode("inactive-password")));
            memberships.save(new OrganizationMembership(s.orgA(), inactive, MembershipRole.ADMIN, status));
            http();
            login("inactive@example.org", "inactive-password");
            NodeKeys n = NodeKeys.fresh();
            long ts = Instant.now().toEpochMilli();
            String sig = NodeClaimVerifier.signClaim(n.kp().getPrivate(), n.nodeId(), n.x(), n.y(), ts);
            assertEquals(403, post("/api/v1/nodes/claim", claimJson(n, s.orgA().getId(), ts, sig)).statusCode());
            assertEquals(0, nodes.count());
        }
    }

    @Test
    void rejectsOversizedClaimSignatureBeforeProcessing() throws Exception {
        Seed s = seed();
        login("admin-a@example.org", "supersecret-password-a");
        NodeKeys n = NodeKeys.fresh();
        assertEquals(400, post("/api/v1/nodes/claim",
                claimJson(n, s.orgA().getId(), Instant.now().toEpochMilli(), "A".repeat(1024))).statusCode());
        assertEquals(0, nodes.count());
    }

    @Test
    void resetMustRotateDeviceKeyAndDuplicateKeyReturnsConflict() throws Exception {
        Seed s = seed();
        login("admin-a@example.org", "supersecret-password-a");
        NodeKeys original = NodeKeys.fresh();
        long ts = Instant.now().toEpochMilli();
        String sig = NodeClaimVerifier.signClaim(original.kp().getPrivate(), original.nodeId(),
                original.x(), original.y(), ts);
        assertEquals(200, post("/api/v1/nodes/claim", claimJson(original, s.orgA().getId(), ts, sig)).statusCode());
        NodeKeys reused = new NodeKeys(UUID.randomUUID(), original.kp(), original.x(), original.y());
        String reusedSig = NodeClaimVerifier.signClaim(reused.kp().getPrivate(), reused.nodeId(),
                reused.x(), reused.y(), ts);
        assertEquals(409, post("/api/v1/nodes/claim", claimJson(reused, s.orgA().getId(), ts, reusedSig)).statusCode());
        assertEquals(1, nodes.count());
    }

    @Test
    void rejectsNonRandomNodeIdentity() throws Exception {
        Seed s = seed();
        login("admin-a@example.org", "supersecret-password-a");
        NodeKeys generated = NodeKeys.fresh();
        NodeKeys n = new NodeKeys(new UUID(0, 1), generated.kp(), generated.x(), generated.y());
        long ts = Instant.now().toEpochMilli();
        String sig = NodeClaimVerifier.signClaim(n.kp().getPrivate(), n.nodeId(), n.x(), n.y(), ts);
        assertEquals(400, post("/api/v1/nodes/claim", claimJson(n, s.orgA().getId(), ts, sig)).statusCode());
        assertEquals(0, nodes.count());
    }

    @Test
    void nodeIdentityIsUuidNotMacOrDbId() {
        // node_id is a random UUIDv4 supplied by the device; the DB never invents it.
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertEquals(4, a.version());
        assertNotEquals(a, b);
        assertTrue(a.toString().matches(
                "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"));
    }

    String diagnosticJson(UUID org) {
        return "{\"attemptId\":\"" + UUID.randomUUID() + "\",\"organizationId\":\"" + org + "\",\"events\":[{"
            + "\"sequence\":1,\"phase\":\"claim_advert\",\"status\":\"failed\",\"code\":\"MM-CLAIM-004\",\"durationMs\":2,\"elapsedMs\":8}]}";
    }

    @Test void diagnosticsReadChunkedBodiesWithoutHoldingDatabaseTransactions() throws Exception {
        var seed = seed();
        var user = users.findByEmail("admin-a@example.org").orElseThrow();
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        byte[] body = diagnosticJson(seed.orgA().getId()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var request = new org.springframework.mock.web.MockHttpServletRequest() {
            @Override public jakarta.servlet.ServletInputStream getInputStream() {
                return new org.springframework.mock.web.DelegatingServletInputStream(new java.io.ByteArrayInputStream(body) {
                    private void check() {
                        reads.incrementAndGet();
                        assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive(),
                            "A slow diagnostic body must not occupy a claim DB connection");
                    }
                    @Override public synchronized int read() { check(); return super.read(); }
                    @Override public synchronized int read(byte[] b, int off, int len) { check(); return super.read(b,off,len); }
                });
            }
        };
        var principal = new org.miezmerker.backend.security.AppUserDetails(user.getId(), "admin-a@example.org", "unused",
            org.miezmerker.backend.domain.UserStatus.ACTIVE);
        assertEquals(204,diagnostics.collect(request,principal).getStatusCode().value());
        assertTrue(reads.get() > 0);
    }

    @Test void diagnosticsRejectUnauthorizedCsrfForeignTenantAndMember() throws Exception {
        var seed = seed();
        String body = diagnosticJson(seed.orgA().getId());
        assertEquals(401, post("/api/v1/nodes/claim-diagnostics",body).statusCode());
        login("admin-a@example.org", "supersecret-password-a");
        var noCsrf = client.send(HttpRequest.newBuilder(URI.create(base("/api/v1/nodes/claim-diagnostics")))
            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(403,noCsrf.statusCode());
        assertEquals(204,post("/api/v1/nodes/claim-diagnostics",body).statusCode());
        assertEquals(403,post("/api/v1/nodes/claim-diagnostics",diagnosticJson(seed.orgB().getId())).statusCode());
        login("member-a@example.org", "supersecret-password-m");
        assertEquals(403,post("/api/v1/nodes/claim-diagnostics",body).statusCode());
        assertTrue(nodes.findAll().isEmpty()); // Telemetry never reserves a Node.
    }

    @Test void diagnosticsRejectSecretsInjectionAndUnboundedPayloads() throws Exception {
        var seed = seed(); login("admin-a@example.org", "supersecret-password-a");
        String body = diagnosticJson(seed.orgA().getId());
        for (String invalid : java.util.List.of(
            body.replace("\"events\":", "\"receipt\":\"secret-jws\",\"events\":"),
            body.replace("claim_advert", "claim_advert\\nforged=ok"),
            body.replace("MM-CLAIM-004", "secret-jws"),
            body.replace("\"durationMs\":2", "\"durationMs\":-1"),
            body.replace("\"durationMs\":2", "\"durationMs\":2,\"durationMs\":3"),
            body + "{}", body.replace("\"sequence\":1", "\"sequence\":1,\"token\":\"secret-jws\""))) {
            assertEquals(400,post("/api/v1/nodes/claim-diagnostics",invalid).statusCode());
        }
        assertEquals(413,post("/api/v1/nodes/claim-diagnostics"," ".repeat(12289)).statusCode());
        assertFalse(output.getOut().contains("secret-jws"));
        assertFalse(output.getOut().contains("forged=ok"));
        assertTrue(nodes.findAll().isEmpty());
    }

    @Test void diagnosticsHaveBoundedUserRate() throws Exception {
        var seed = seed(); login("admin-a@example.org", "supersecret-password-a");
        for (int i=0;i<12;i++) assertEquals(204,post("/api/v1/nodes/claim-diagnostics",diagnosticJson(seed.orgA().getId())).statusCode());
        assertEquals(429,post("/api/v1/nodes/claim-diagnostics",diagnosticJson(seed.orgA().getId())).statusCode());
    }

    @Test void correlatedClaimLogsOnlySafeMilestones() throws Exception {
        var seed=seed(); login("admin-a@example.org","supersecret-password-a");
        var n=NodeKeys.fresh(); long now=System.currentTimeMillis();
        String signature=NodeClaimVerifier.signClaim(n.kp().getPrivate(),n.nodeId(),n.x(),n.y(),now);
        String attempt=UUID.randomUUID().toString();
        var response=client.send(HttpRequest.newBuilder(URI.create(base("/api/v1/nodes/claim")))
            .header("Content-Type","application/json").header("X-XSRF-TOKEN",csrf())
            .header("X-MM-Claim-Attempt",attempt)
            .POST(HttpRequest.BodyPublishers.ofString(claimJson(n,seed.orgA().getId(),now,signature))).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,response.statusCode());
        assertEquals(attempt,response.headers().firstValue("X-MM-Claim-Attempt").orElseThrow());
        // The bounded native AsyncAppender must remain nonblocking for claims.
        long deadline=System.nanoTime()+2_000_000_000L;
        while (!output.getOut().contains("attemptId="+attempt+" source=backend untrusted=false phase=complete status=ok") && System.nanoTime()<deadline) Thread.sleep(10);
        var lines=output.getOut().lines().filter(line->line.contains("attemptId="+attempt)).toList();
        for (String phase: java.util.List.of("authorization_csrf","active_admin","organization","claim_reservation","identity_time_policy","ownership_binding","receipt_issuance","transaction_completion","complete")) {
            assertTrue(lines.stream().anyMatch(line->line.contains("phase="+phase+" status=ok")),phase);
        }
        assertFalse(output.getOut().contains(signature));
        assertFalse(output.getOut().contains(n.x()));
        assertFalse(output.getOut().contains(mapper.readTree(response.body()).path("receipt").asText()));
    }
}
