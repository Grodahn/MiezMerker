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
class NodeTimeBootstrapTest {
    static final KeyPair SIGNER = EcKeyUtils.generateP256();
    @org.springframework.test.context.DynamicPropertySource
    static void signer(org.springframework.test.context.DynamicPropertyRegistry r) {
        r.add("miezmerker.issuer.private-key-pkcs8-b64", () -> EcKeyUtils.pkcs8B64(SIGNER.getPrivate()));
        r.add("miezmerker.issuer.public-key-spki-b64", () -> Base64.getEncoder().encodeToString(SIGNER.getPublic().getEncoded()));
    }
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    org.miezmerker.backend.crypto.BootstrapTimeSource time;
    @Autowired org.miezmerker.backend.crypto.NodeTimeProofVerifier verifier;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    org.miezmerker.backend.security.TenantService tenantGate;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    CredentialIssuerService issuerGate;
    static final String PATH = "/api/v1/nodes/time-bootstrap";
    static final long UTC = 1791748800123L;
    org.miezmerker.backend.crypto.NodeTimeProtocol.Challenge challenge(NodeKeys n) {
        byte[] pub = new byte[65]; pub[0]=4;
        System.arraycopy(EcKeyUtils.b64uDecode(n.x()),0,pub,1,32);
        System.arraycopy(EcKeyUtils.b64uDecode(n.y()),0,pub,33,32);
        byte[] nonce = new byte[32]; new java.security.SecureRandom().nextBytes(nonce);
        var c = new org.miezmerker.backend.crypto.NodeTimeProtocol.Challenge(n.nodeId().toString(),UUID.randomUUID().toString(),
                42,EcKeyUtils.b64u(nonce),java.math.BigInteger.valueOf(1000),EcKeyUtils.b64u(pub),
                EcKeyUtils.fingerprintOfXY(n.x(),n.y()),EcKeyUtils.b64u(new byte[64]),1);
        return new org.miezmerker.backend.crypto.NodeTimeProtocol.Challenge(c.nodeId(),c.incarnation(),c.bootCounter(),c.challengeNonce(),
                c.challengeUptimeMs(),c.devicePublicKey(),c.devicePublicKeyFingerprint(),EcKeyUtils.b64u(EcKeyUtils.signRaw(n.kp().getPrivate(),
                        org.miezmerker.backend.crypto.NodeTimeProtocol.challengeBytes(c))),1);
    }
    String request(Seed s, String state, org.miezmerker.backend.crypto.NodeTimeProtocol.Challenge c) {
        return mapper.writeValueAsString(new org.miezmerker.backend.crypto.NodeTimeProtocol.Request(s.orgA().getId().toString(),state,c));
    }
    NodeDevice claimed(Seed s, NodeKeys n) {
        var node = new NodeDevice(n.nodeId(),n.x(),n.y(),EcKeyUtils.fingerprintOfXY(n.x(),n.y()),"test");
        node.claim(s.orgA()); return nodes.save(node);
    }

    @Test void memberCanIssueForOwnedClaimedNodeAndProofIsExactlyBound() throws Exception {
        var s=seed(); var n=NodeKeys.fresh(); claimed(s,n); var c=challenge(n);
        login("member-a@example.org","supersecret-password-m");
        org.mockito.Mockito.when(time.issuanceMillis()).thenReturn(UTC);
        var res=post(PATH,request(s,"CLAIMED",c)); assertEquals(200,res.statusCode(),res.body());
        assertEquals("no-store",res.headers().firstValue("Cache-Control").orElseThrow());
        String proof=mapper.readTree(res.body()).path("proof").asText();
        var p=verifier.verify(proof,c);
        assertEquals(UTC,p.path("utc_ms").asLong()); assertEquals(42,p.path("boot").asLong());
        assertEquals(c.nodeId(),p.path("node").asText()); assertEquals(c.incarnation(),p.path("incarnation").asText());
        assertEquals(c.challengeNonce(),p.path("nonce").asText()); assertEquals(c.devicePublicKeyFingerprint(),p.path("dpf").asText());
        assertEquals("node-time-bootstrap",p.path("kind").asText()); assertEquals(1,p.path("ver").asInt());
        assertEquals(CredentialIssuerService.ISSUER,p.path("iss").asText()); assertEquals(CredentialIssuerService.KEY_ID,p.path("kid").asText());
        assertEquals(4,UUID.fromString(p.path("jti").asText()).version());
        assertThrows(Exception.class,()->issuer.verify(proof));
        var retry=post(PATH,request(s,"CLAIMED",c)); assertEquals(200,retry.statusCode(),retry.body());
        var second=verifier.verify(mapper.readTree(retry.body()).path("proof").asText(),c);
        assertNotEquals(p.path("jti").asText(),second.path("jti").asText());
        assertEquals(1,nodes.count()); assertEquals(s.orgA().getId(),nodes.findById(n.nodeId()).orElseThrow().getOrganization().getId());
    }
    @Test void adminPreClaimIssuesWithoutCreatingOwnership() throws Exception {
        var s=seed(); var n=NodeKeys.fresh(); var c=challenge(n);
        login("admin-a@example.org","supersecret-password-a"); org.mockito.Mockito.when(time.issuanceMillis()).thenReturn(UTC);
        assertEquals(200,post(PATH,request(s,"UNCLAIMED",c)).statusCode()); assertEquals(0,nodes.count());
        nodes.save(new NodeDevice(n.nodeId(),n.x(),n.y(),c.devicePublicKeyFingerprint(),"test"));
        assertEquals(200,post(PATH,request(s,"UNCLAIMED",c)).statusCode());
        assertEquals(org.miezmerker.backend.domain.NodeState.UNCLAIMED,nodes.findById(n.nodeId()).orElseThrow().getState());
        assertNull(nodes.findById(n.nodeId()).orElseThrow().getOrganization());
    }
    @Test void freshAdminBootstrapThenClaimEnablesNormalSyncIdentityAndCredential() throws Exception {
        var s=seed(); var n=NodeKeys.fresh(); var c=challenge(n);
        login("admin-a@example.org","supersecret-password-a");
        long now=Instant.now().toEpochMilli();
        org.mockito.Mockito.when(time.issuanceMillis()).thenReturn(now);
        var issued=post(PATH,request(s,"UNCLAIMED",c));
        assertEquals(200,issued.statusCode(),issued.body());
        long trustedUtc=verifier.verify(mapper.readTree(issued.body()).path("proof").asText(),c).path("utc_ms").asLong();
        assertEquals(0,nodes.count()); // Time alone reserves no ownership.
        String claim=mapper.writeValueAsString(java.util.Map.of(
                "nodeId",n.nodeId().toString(),"organizationId",s.orgA().getId().toString(),
                "publicKeyX",n.x(),"publicKeyY",n.y(),"firmwareVersion","test-157",
                "timestampMillis",trustedUtc,"claimSignature",NodeClaimVerifier.signClaim(
                        n.kp().getPrivate(),n.nodeId(),n.x(),n.y(),trustedUtc)));
        var registered=post("/api/v1/nodes/claim",claim);
        assertEquals(200,registered.statusCode(),registered.body());
        var receipt=issuer.verify(mapper.readTree(registered.body()).path("receipt").asText());
        assertEquals("claim",receipt.getStringClaim("kind"));
        assertEquals(s.orgA().getId().toString(),receipt.getStringClaim("org"));
        assertEquals(org.miezmerker.backend.domain.NodeState.CLAIMED,nodes.findById(n.nodeId()).orElseThrow().getState());
        var key=get("/api/v1/nodes/"+n.nodeId()+"?organizationId="+s.orgA().getId());
        assertEquals(200,key.statusCode(),key.body());
        assertEquals(n.x(),mapper.readTree(key.body()).path("publicKeyX").asText());
        var phone=(ECPublicKey)EcKeyUtils.generateP256().getPublic();
        var device=post("/api/v1/devices",mapper.writeValueAsString(java.util.Map.of(
                "publicKeyX",EcKeyUtils.xOf(phone),"publicKeyY",EcKeyUtils.yOf(phone),"label","157")));
        assertEquals(200,device.statusCode(),device.body());
        var credential=post("/api/v1/devices/"+mapper.readTree(device.body()).path("id").asText()+"/credentials",
                mapper.writeValueAsString(java.util.Map.of("organizationId",s.orgA().getId().toString())));
        assertEquals(200,credential.statusCode(),credential.body());
        var claims=issuer.verify(mapper.readTree(credential.body()).path("credential").asText());
        assertEquals(java.util.List.of("node:sync"),claims.getStringListClaim("scope"));
        assertEquals(s.orgA().getId().toString(),claims.getStringClaim("org"));
    }
    @Test void unauthenticatedDisabledAccountsMembershipsAndOrganizationsFailClosed() throws Exception {
        var s=seed(); var c=challenge(NodeKeys.fresh());
        assertEquals(401,post(PATH,request(s,"UNCLAIMED",c)).statusCode());
        login("admin-a@example.org","supersecret-password-a");
        var user=users.findByEmail("admin-a@example.org").orElseThrow();
        user.setStatus(org.miezmerker.backend.domain.UserStatus.DISABLED); users.save(user);
        assertEquals(403,post(PATH,request(s,"UNCLAIMED",c)).statusCode());
        user.setStatus(org.miezmerker.backend.domain.UserStatus.ACTIVE); users.save(user);
        var m=memberships.findByOrganizationIdAndUserId(s.orgA().getId(),user.getId()).orElseThrow();
        m.setStatusDirect(MembershipStatus.DISABLED); m = memberships.save(m);
        assertEquals(403,post(PATH,request(s,"UNCLAIMED",c)).statusCode());
        m.setStatusDirect(MembershipStatus.ACTIVE); m = memberships.save(m);
        s.orgA().setStatus(org.miezmerker.backend.domain.OrganizationStatus.DISABLED); organizations.save(s.orgA());
        assertEquals(403,post(PATH,request(s,"UNCLAIMED",c)).statusCode()); assertEquals(0,nodes.count());
        org.mockito.Mockito.verifyNoInteractions(time);
    }
    @Test void memberAndGlobalSysadminCannotBootstrapUnclaimedWithoutAdminMembership() throws Exception {
        var s=seed(); var c=challenge(NodeKeys.fresh()); login("member-a@example.org","supersecret-password-m");
        assertEquals(403,post(PATH,request(s,"UNCLAIMED",c)).statusCode());
        var global=users.save(new AppUser("sys@example.org",passwords.encode("sys-password-long")));
        try (var connection=dataSource.getConnection()) {
            assertTrue(org.miezmerker.backend.bootstrap.SystemRoleMaintenance.apply(connection,UUID.randomUUID(),
                    global.getId(),"GRANT","operator","issue129 test"));
        }
        http(); login("sys@example.org","sys-password-long");
        assertEquals(403,post(PATH,request(s,"UNCLAIMED",c)).statusCode()); org.mockito.Mockito.verifyNoInteractions(time);
    }
    @Test void foreignOrgClaimedImpersonationAndKeySubstitutionHaveSameGenericDenial() throws Exception {
        var s=seed(); var n=NodeKeys.fresh(); claimed(s,n); var c=challenge(n);
        login("admin-b@example.org","supersecret-password-b");
        for(String state:java.util.List.of("CLAIMED","UNCLAIMED")) {
            var body=mapper.readTree(request(s,state,c)); ((tools.jackson.databind.node.ObjectNode)body).put("organizationId",s.orgB().getId().toString());
            var res=post(PATH,body.toString()); assertEquals(403,res.statusCode(),res.body());
            assertFalse(res.body().contains(s.orgA().getId().toString())); assertFalse(res.body().contains(c.devicePublicKeyFingerprint()));
        }
        login("admin-a@example.org","supersecret-password-a");
        var other=NodeKeys.fresh(); var substitute=new NodeKeys(n.nodeId(),other.kp(),other.x(),other.y());
        assertEquals(403,post(PATH,request(s,"CLAIMED",challenge(substitute))).statusCode());
        assertEquals(1,nodes.count()); assertEquals(c.devicePublicKeyFingerprint(),nodes.findById(n.nodeId()).orElseThrow().getFingerprint());
        org.mockito.Mockito.verifyNoInteractions(time);
    }
    @Test void reservedSameOwnerAdminCanBootstrapAfterInterruptedClaim() throws Exception {
        var s=seed(); var n=NodeKeys.fresh(); claimed(s,n); var c=challenge(n);
        login("admin-a@example.org","supersecret-password-a");
        org.mockito.Mockito.when(time.issuanceMillis()).thenReturn(UTC);
        assertEquals(200,post(PATH,request(s,"UNCLAIMED",c)).statusCode());
        assertEquals(1,nodes.count());
        assertEquals(s.orgA().getId(),nodes.findById(n.nodeId()).orElseThrow().getOrganization().getId());
        login("member-a@example.org","supersecret-password-m");
        assertEquals(403,post(PATH,request(s,"UNCLAIMED",c)).statusCode());
    }
    @Test void malformedFieldsEncodingsAndLargeBodiesAreRejectedBeforeIssuance() throws Exception {
        var s=seed(); var c=challenge(NodeKeys.fresh()); login("admin-a@example.org","supersecret-password-a");
        String valid=request(s,"UNCLAIMED",c);
        for(String bad:java.util.List.of(valid.replace("\"version\":1","\"version\":2"),
                valid.replace("\"version\":1","\"version\":1,\"version\":1"), valid+"{}",
                valid.replace("\"bootCounter\":42","\"bootCounter\":4294967296"),
                valid.replace("\"bootCounter\":42","\"bootCounter\":0"),
                valid.replace("\"challengeUptimeMs\":1000","\"challengeUptimeMs\":18446744073709551616"),
                valid.replace(c.nodeId(),"1-1-1-1-1"),valid.replace(c.challengeNonce(),c.challengeNonce()+"="),
                valid.replace(c.devicePublicKeyFingerprint(),EcKeyUtils.b64u(new byte[32])),
                valid.replace(c.devicePublicKey(),EcKeyUtils.b64u(new byte[65])),
                valid.replace(c.deviceChallengeSignature(),"AA"),valid.replace(c.challengeNonce(),EcKeyUtils.b64u(new byte[32])),
                valid.replace("\"registrationState\"","\"utc_ms\":0,\"registrationState\""))) {
            assertEquals(400,post(PATH,bad).statusCode(),bad);
        }
        assertEquals(413,post(PATH," ".repeat(4097)).statusCode()); org.mockito.Mockito.verifyNoInteractions(time);
        var chunked=HttpRequest.newBuilder(URI.create(base(PATH))).header("Content-Type","application/json").header("X-XSRF-TOKEN",csrf())
            .POST(HttpRequest.BodyPublishers.ofInputStream(()->new java.io.ByteArrayInputStream(" ".repeat(4097).getBytes(java.nio.charset.StandardCharsets.UTF_8)))).build();
        assertEquals(413,client.send(chunked,HttpResponse.BodyHandlers.ofString()).statusCode());
    }
    @Test void signatureTamperingAndPhoneTimeCannotProduceProof() throws Exception {
        var s=seed(); var c=challenge(NodeKeys.fresh()); login("admin-a@example.org","supersecret-password-a");
        String valid=request(s,"UNCLAIMED",c);
        for(String bad:java.util.List.of(valid.replace(c.nodeId(),UUID.randomUUID().toString()),
                valid.replace(c.incarnation(),UUID.randomUUID().toString()),valid.replace("\"bootCounter\":42","\"bootCounter\":43"),
                valid.replace(c.challengeNonce(),EcKeyUtils.b64u(new byte[]{1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0})),
                valid.replace(c.deviceChallengeSignature(),EcKeyUtils.b64u(new byte[64])))) {
            assertEquals(403,post(PATH,bad).statusCode());
        }
        assertEquals(400,post(PATH,valid.replace("\"registrationState\"","\"phoneTime\":999,\"registrationState\"")).statusCode());
        org.mockito.Mockito.verifyNoInteractions(time);
    }
    @Test void unhealthyClockFailsClosedAndUnclaimedRetryLimitIsEnforced() throws Exception {
        var s=seed(); var c=challenge(NodeKeys.fresh()); login("admin-a@example.org","supersecret-password-a");
        org.mockito.Mockito.when(time.issuanceMillis()).thenThrow(new IllegalStateException("unhealthy"));
        var unavailable = post(PATH,request(s,"UNCLAIMED",c));
        assertEquals(503,unavailable.statusCode());
        assertEquals("utc-unconfirmed",unavailable.headers().firstValue("X-MM-Time-Bootstrap").orElseThrow());
        assertEquals("no-store",unavailable.headers().firstValue("Cache-Control").orElseThrow());
        org.mockito.Mockito.doReturn(UTC).when(time).issuanceMillis();
        for(int i=0;i<4;i++) assertEquals(200,post(PATH,request(s,"UNCLAIMED",c)).statusCode());
        assertEquals(429,post(PATH,request(s,"UNCLAIMED",c)).statusCode()); assertEquals(0,nodes.count());
    }
    @Test void missingStableKeyDeniesIssuanceAndOnlyAuthorizedAdminGetsDiagnostic() throws Exception {
        var s=seed(); var n=NodeKeys.fresh(); var c=challenge(n);
        org.mockito.Mockito.doReturn(false).when(issuerGate).hasStableKey();
        login("member-a@example.org","supersecret-password-m");
        var forbidden=post(PATH,request(s,"UNCLAIMED",c));
        assertEquals(403,forbidden.statusCode());
        assertTrue(forbidden.headers().firstValue("X-MM-Time-Bootstrap").isEmpty());
        login("admin-a@example.org","supersecret-password-a");
        var unavailable=post(PATH,request(s,"UNCLAIMED",c));
        assertEquals(503,unavailable.statusCode());
        assertEquals("issuer-key-missing",unavailable.headers().firstValue("X-MM-Time-Bootstrap").orElseThrow());
        var diagnosticBody=mapper.readTree(unavailable.body());
        assertEquals("MM-API-009",diagnosticBody.path("code").asText());
        assertEquals("Service Unavailable",diagnosticBody.path("message").asText());
        assertFalse(unavailable.body().contains("issuer-key-missing"));
        assertFalse(unavailable.body().contains(c.devicePublicKeyFingerprint()));
        claimed(s,n);
        login("member-a@example.org","supersecret-password-m");
        var generic=post(PATH,request(s,"CLAIMED",c));
        assertEquals(503,generic.statusCode());
        assertEquals(unavailable.body(),generic.body());
        assertTrue(generic.headers().firstValue("X-MM-Time-Bootstrap").isEmpty());
        org.mockito.Mockito.verifyNoInteractions(time);
    }
    @Test void concurrentClaimSerializesBootstrapAndOwnershipRecheck() throws Exception {
        var s=seed(); var n=NodeKeys.fresh(); var c=challenge(n);
        // Hold the exact shared lock while a bootstrap reaches validation, then commit foreign ownership.
        login("admin-a@example.org","supersecret-password-a"); String token=csrf();
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        var validated=new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(inv->{ var membership=inv.callRealMethod(); validated.countDown(); return membership; })
                .when(tenantGate).requireActive(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        final java.util.concurrent.CompletableFuture<HttpResponse<String>>[] response=new java.util.concurrent.CompletableFuture[1];
        tx.execute(status->{ nodes.lockClaims();
            response[0]=client.sendAsync(HttpRequest.newBuilder(URI.create(base(PATH))).header("Content-Type","application/json")
                    .header("X-XSRF-TOKEN",token).POST(HttpRequest.BodyPublishers.ofString(request(s,"UNCLAIMED",c))).build(),HttpResponse.BodyHandlers.ofString());
            try { assertTrue(validated.await(5,java.util.concurrent.TimeUnit.SECONDS)); }
            catch (InterruptedException e) { throw new IllegalStateException(e); }
            var node=new NodeDevice(n.nodeId(),n.x(),n.y(),c.devicePublicKeyFingerprint(),"test"); node.claim(s.orgB()); nodes.saveAndFlush(node);
            return null;
        });
        assertEquals(403,response[0].get(10,java.util.concurrent.TimeUnit.SECONDS).statusCode());
        org.mockito.Mockito.verifyNoInteractions(time);
        assertEquals(s.orgB().getId(),nodes.findById(n.nodeId()).orElseThrow().getOrganization().getId());
    }
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired javax.sql.DataSource dataSource;
    @Test void openApiMatchesNumericLosslessUptimeAndStrictRequestContract() throws Exception {
        var res=get("/api/v1/openapi"); assertEquals(200,res.statusCode()); var spec=mapper.readTree(res.body());
        assertEquals("issueNodeTimeProof",spec.at("/paths/~1api~1v1~1nodes~1time-bootstrap/post/operationId").asText());
        var schema=spec.at("/components/schemas/NodeTimeChallengeV1");
        assertEquals("integer",schema.at("/properties/challengeUptimeMs/type").asText());
        assertEquals("uint64",schema.at("/properties/challengeUptimeMs/format").asText());
        assertTrue(schema.at("/properties/challengeUptimeMs/description").asText().contains("18446744073709551615"));
        assertFalse(schema.path("additionalProperties").asBoolean(true));
        assertEquals(9,schema.path("required").size());
        assertEquals("#/components/schemas/NodeTimeBootstrapRequest",spec.at("/paths/~1api~1v1~1nodes~1time-bootstrap/post/requestBody/content/application~1json/schema/$ref").asText());
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("target"));
        java.nio.file.Files.writeString(java.nio.file.Path.of("target","openapi.json"),res.body()+"\n");
    }
    @Test void unauthorizedIdentityAttemptsCannotExhaustVictimNodeOrOrganizationBudget() throws Exception {
        var s=seed(); var n=NodeKeys.fresh(); claimed(s,n); var c=challenge(n);
        login("admin-b@example.org","supersecret-password-b");
        String foreign=mapper.writeValueAsString(new org.miezmerker.backend.crypto.NodeTimeProtocol.Request(s.orgB().getId().toString(),"CLAIMED",c));
        // Ten attempts previously exhausted the victim's Node-wide limit before ownership denial.
        for(int i=0;i<10;i++) assertEquals(403,post(PATH,foreign).statusCode());
        for(int i=0;i<10;i++) assertEquals(403,post(PATH,request(s,"CLAIMED",c)).statusCode());
        org.mockito.Mockito.verifyNoInteractions(time);
        login("member-a@example.org","supersecret-password-m");
        String wrongSignature=request(s,"CLAIMED",c).replace(c.deviceChallengeSignature(),EcKeyUtils.b64u(new byte[64]));
        for(int i=0;i<10;i++) assertEquals(403,post(PATH,wrongSignature).statusCode());
        var other=NodeKeys.fresh(); var wrongKey=new NodeKeys(n.nodeId(),other.kp(),other.x(),other.y());
        for(int i=0;i<10;i++) assertEquals(403,post(PATH,request(s,"CLAIMED",challenge(wrongKey))).statusCode());
        org.mockito.Mockito.when(time.issuanceMillis()).thenReturn(UTC);
        var res=post(PATH,request(s,"CLAIMED",c)); assertEquals(200,res.statusCode(),res.body());
        verifier.verify(mapper.readTree(res.body()).path("proof").asText(),c);
    }
    @Test void malformedAttemptsConsumeUserBudgetAndMissingCsrfIsRejected() throws Exception {
        var s=seed(); login("admin-a@example.org","supersecret-password-a");
        var withoutCsrf=client.send(HttpRequest.newBuilder(URI.create(base(PATH))).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(request(s,"UNCLAIMED",challenge(NodeKeys.fresh())))).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(403,withoutCsrf.statusCode());
        for(int i=0;i<30;i++) assertEquals(400,post(PATH,"{}").statusCode());
        assertEquals(429,post(PATH,"{}").statusCode()); org.mockito.Mockito.verifyNoInteractions(time);
    }
    @Test void bootstrapProducesNoDeploymentCatOrObservationAndCannotReuseRegisteredKey() throws Exception {
        var s=seed(); var n=NodeKeys.fresh(); claimed(s,n);
        login("admin-a@example.org","supersecret-password-a"); org.mockito.Mockito.when(time.issuanceMillis()).thenReturn(UTC);
        var jdbc=new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        var tables=java.util.List.of("node_deployments","cats","raw_observations");
        var counts=new java.util.ArrayList<Long>();
        for(String table:tables) counts.add(jdbc.queryForObject("select count(*) from "+table,Long.class));
        var fresh=NodeKeys.fresh(); assertEquals(200,post(PATH,request(s,"UNCLAIMED",challenge(fresh))).statusCode());
        var reused=new NodeKeys(UUID.randomUUID(),n.kp(),n.x(),n.y());
        assertEquals(403,post(PATH,request(s,"UNCLAIMED",challenge(reused))).statusCode());
        for(int i=0;i<tables.size();i++) assertEquals(counts.get(i),jdbc.queryForObject("select count(*) from "+tables.get(i),Long.class));
        assertEquals(1,nodes.count());
    }
    @Value("${local.server.port}") int port;

    @Autowired AppUserRepository users;
    @Autowired OrganizationRepository organizations;
    @Autowired MembershipRepository memberships;
    @Autowired NodeRepository nodes;
    @Autowired PasswordEncoder passwords;
    @Autowired TestDatabaseCleaner cleaner;
    @Autowired CredentialIssuerService issuer;

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


}
