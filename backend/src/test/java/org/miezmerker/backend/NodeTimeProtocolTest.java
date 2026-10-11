package org.miezmerker.backend;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.miezmerker.backend.crypto.*;
import org.miezmerker.backend.service.NodeTimeRateLimiter;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.ECPrivateKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicLong;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Tag("node") @Tag("auth")
class NodeTimeProtocolTest {
    private final JsonMapper mapper = new JsonMapper();
    private final JsonNode fixture = NodeTimeProtocol.json(readFixture());
    private static byte[] readFixture() {
        try { return Files.readAllBytes(Path.of("..","protocol","fixtures","node-time-v1.json")); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private NodeTimeProtocol.Challenge challenge() {
        var f=fixture.path("challenge");
        return new NodeTimeProtocol.Challenge(f.path("nodeId").asText(),f.path("incarnation").asText(),f.path("bootCounter").asLong(),
                f.path("challengeNonce").asText(),f.path("challengeUptimeMs").bigIntegerValue(),f.path("devicePublicKey").asText(),
                f.path("devicePublicKeyFingerprint").asText(),f.path("deviceChallengeSignature").asText(),1);
    }
    private NodeTimeProtocol.Acceptance acceptance() {
        var a=fixture.path("acceptance");
        return new NodeTimeProtocol.Acceptance(a.path("nodeId").asText(),a.path("incarnation").asText(),a.path("bootCounter").asLong(),
                a.path("proofDigest").asText(),a.path("challengeNonceDigest").asText(),a.path("acceptedUptimeMs").bigIntegerValue(),
                a.path("anchorId").asText(),a.path("signature").asText(),1);
    }
    private CredentialIssuerService issuer() throws Exception {
        var f=fixture.path("issuerPublicKey");
        var pub=EcKeyUtils.publicFromXY(f.path("x").asText(),f.path("y").asText());
        var priv=(ECPrivateKey)KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(BigInteger.ONE,pub.getParams()));
        return CredentialIssuerService.withStableKey(priv,pub,3600);
    }
    @Test void independentlyGeneratedGoldenBytesAndSignaturesStayCompatible() throws Exception {
        var c=challenge(); var proof=fixture.path("proof").asText(); var a=acceptance();
        assertArrayEquals(HexFormat.of().parseHex(fixture.path("challengeSigningHex").asText()),NodeTimeProtocol.challengeBytes(c));
        assertArrayEquals(HexFormat.of().parseHex(fixture.path("acceptanceSigningHex").asText()),NodeTimeProtocol.acceptanceBytes(c,proof,a));
        NodeTimeProtocol.verifyChallenge(c);
        var verifier=new NodeTimeProofVerifier(issuer());
        assertEquals(fixture.path("expectedUtcMs").asLong(),verifier.verify(proof,c).path("utc_ms").asLong());
        verifier.verifyAcceptance(proof,c,a,NodeTimeProtocol.key(c));
        assertEquals(169,NodeTimeProtocol.challengeBytes(c).length);
        assertEquals(149,NodeTimeProtocol.acceptanceBytes(c,proof,a).length);
    }
    @Test void negativeSignedProofFixturesRejectPurposeIssuerKeyAlgorithmAndBindings() throws Exception {
        var verifier=new NodeTimeProofVerifier(issuer()); var c=challenge();
        for(var bad:fixture.path("negativeProofs")) assertThrows(IllegalArgumentException.class,()->verifier.verify(bad.asText(),c));
        assertThrows(Exception.class,()->new OfflineAuthService(issuer()).verifyForOrganization(fixture.path("proof").asText(),java.util.UUID.randomUUID()));
        String credential=issuer().issueOfflineCredential(java.util.UUID.randomUUID(),java.util.UUID.randomUUID(),"test",
                java.util.UUID.randomUUID(),EcKeyUtils.xOf(NodeTimeProtocol.key(c)),EcKeyUtils.yOf(NodeTimeProtocol.key(c)),
                c.devicePublicKeyFingerprint(),"MEMBER",Instant.now());
        assertThrows(IllegalArgumentException.class,()->verifier.verify(credential,c));
        assertThrows(IllegalArgumentException.class,()->verifier.verify("A".repeat(2049),c));
    }
    @Test void duplicateClaimsTrailingJsonNoncanonicalEncodingAndNumericOverflowReject() throws Exception {
        var c=challenge(); var verifier=new NodeTimeProofVerifier(issuer()); String proof=fixture.path("proof").asText();
        String[] parts=proof.split("\\.");
        String payload=new String(EcKeyUtils.b64uDecode(parts[1]),StandardCharsets.UTF_8);
        for(String invalid:java.util.List.of(payload.replace("\"ver\":1","\"ver\":1,\"ver\":1"),payload+"{}",
                payload.replace("\"boot\":42","\"boot\":18446744073709551658"),
                payload.replace("\"ver\":1","\"ver\":18446744073709551617"),
                payload.replace("\"utc_ms\":1791748800123","\"utc_ms\":18446744073709551616"))) {
            assertThrows(IllegalArgumentException.class,()->verifier.verify(parts[0]+"."+EcKeyUtils.b64u(invalid.getBytes(StandardCharsets.UTF_8))+"."+parts[2],c));
        }
        assertThrows(IllegalArgumentException.class,()->verifier.verify(parts[0]+"=."+parts[1]+"."+parts[2],c));
    }
    @Test void acceptanceRequiresExactProofKeyAndAllInstallationBindings() throws Exception {
        var c=challenge(); var a=acceptance(); String proof=fixture.path("proof").asText(); var verifier=new NodeTimeProofVerifier(issuer());
        String different=issuer().issueTimeProof(c,fixture.path("expectedUtcMs").asLong());
        assertThrows(IllegalArgumentException.class,()->verifier.verifyAcceptance(different,c,a,NodeTimeProtocol.key(c)));
        assertThrows(IllegalArgumentException.class,()->verifier.verifyAcceptance(proof,c,a,(ECPublicKey)EcKeyUtils.generateP256().getPublic()));
        var mutations=java.util.List.of(
                new NodeTimeProtocol.Acceptance(java.util.UUID.randomUUID().toString(),a.incarnation(),a.bootCounter(),a.proofDigest(),a.challengeNonceDigest(),a.acceptedUptimeMs(),a.anchorId(),a.signature(),1),
                new NodeTimeProtocol.Acceptance(a.nodeId(),java.util.UUID.randomUUID().toString(),a.bootCounter(),a.proofDigest(),a.challengeNonceDigest(),a.acceptedUptimeMs(),a.anchorId(),a.signature(),1),
                new NodeTimeProtocol.Acceptance(a.nodeId(),a.incarnation(),43,a.proofDigest(),a.challengeNonceDigest(),a.acceptedUptimeMs(),a.anchorId(),a.signature(),1),
                new NodeTimeProtocol.Acceptance(a.nodeId(),a.incarnation(),42,a.proofDigest(),EcKeyUtils.b64u(new byte[32]),a.acceptedUptimeMs(),a.anchorId(),a.signature(),1),
                new NodeTimeProtocol.Acceptance(a.nodeId(),a.incarnation(),42,a.proofDigest(),a.challengeNonceDigest(),BigInteger.valueOf(1301),a.anchorId(),a.signature(),1),
                new NodeTimeProtocol.Acceptance(a.nodeId(),a.incarnation(),42,a.proofDigest(),a.challengeNonceDigest(),BigInteger.valueOf(999),a.anchorId(),a.signature(),1),
                new NodeTimeProtocol.Acceptance(a.nodeId(),a.incarnation(),42,a.proofDigest(),a.challengeNonceDigest(),a.acceptedUptimeMs(),java.util.UUID.randomUUID().toString(),a.signature(),1),
                new NodeTimeProtocol.Acceptance(a.nodeId(),a.incarnation(),42,a.proofDigest(),a.challengeNonceDigest(),a.acceptedUptimeMs(),a.anchorId(),EcKeyUtils.b64u(new byte[64]),1));
        for(var bad:mutations) assertThrows(IllegalArgumentException.class,()->verifier.verifyAcceptance(proof,c,bad,NodeTimeProtocol.key(c)));
        var alteredUptime=new NodeTimeProtocol.Challenge(c.nodeId(),c.incarnation(),c.bootCounter(),c.challengeNonce(),
                a.acceptedUptimeMs(),c.devicePublicKey(),c.devicePublicKeyFingerprint(),c.deviceChallengeSignature(),1);
        assertThrows(IllegalArgumentException.class,()->verifier.verifyAcceptance(proof,alteredUptime,a,NodeTimeProtocol.key(c)));
    }
    @Test void unsignedUptimeAndCounterBoundariesUseExactLittleEndianBytes() {
        var c=challenge();
        var boundary=new NodeTimeProtocol.Challenge(c.nodeId(),c.incarnation(),0xffff_ffffL,c.challengeNonce(),
                BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE),c.devicePublicKey(),c.devicePublicKeyFingerprint(),c.deviceChallengeSignature(),1);
        byte[] bytes=NodeTimeProtocol.challengeBytes(boundary);
        for(int i=60;i<72;i++) assertEquals((byte)255,bytes[i]);
        String json=mapper.writeValueAsString(new NodeTimeProtocol.Request("22222222-2222-4222-8222-222222222222","UNCLAIMED",boundary));
        assertEquals(boundary.challengeUptimeMs(),NodeTimeProtocol.parseRequest(json.getBytes(StandardCharsets.UTF_8)).challenge().challengeUptimeMs());
        assertThrows(IllegalArgumentException.class,()->NodeTimeProtocol.verifyChallenge(boundary));
    }
    @Test void injectedUtcClockHealthAndStableKeyFailClosed() throws Exception {
        var clock=Clock.fixed(Instant.ofEpochMilli(1791748800123L),ZoneOffset.UTC);
        var source=new BootstrapTimeSource(clock,false);
        assertThrows(IllegalStateException.class,source::issuanceMillis);
        source.reportHealthy(true); assertEquals(1791748800123L,source.issuanceMillis());
        source.reportHealthy(false); assertThrows(IllegalStateException.class,source::issuanceMillis);
        assertThrows(IllegalStateException.class,()->new CredentialIssuerService("","",1).issueTimeProof(challenge(),1791748800123L));
    }
    @Test void concurrentRateLimitsBoundCapacityExpireMonotonicallyAndNeverResetActiveEntries() throws Exception {
        var now=new AtomicLong(0); var limiter=new NodeTimeRateLimiter(now::get); var allowed=new java.util.concurrent.atomic.AtomicInteger();
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var jobs=new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for(int i=0;i<100;i++) jobs.add(executor.submit(()->{try{limiter.acquire("node","same",10);allowed.incrementAndGet();}
                catch(org.springframework.web.server.ResponseStatusException e){assertEquals(429,e.getStatusCode().value());}}));
            for(var job:jobs) job.get();
        }
        assertEquals(10,allowed.get());
        for(int i=0;i<9999;i++) limiter.acquire("user",i,30);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->limiter.acquire("user","overflow",30));
        now.set(60_000_000_000L); limiter.acquire("node","same",10);
    }
    @Test void unrelatedDeviceKeyDomainAndIssuerCannotVerify() throws Exception {
        var c=challenge(); var other=EcKeyUtils.generateP256();
        for(byte[] bytes:java.util.List.of(NodeTimeProtocol.challengeBytes(c),NodeTimeProtocol.decode(c.challengeNonce(),32))) {
            var bad=new NodeTimeProtocol.Challenge(c.nodeId(),c.incarnation(),c.bootCounter(),c.challengeNonce(),c.challengeUptimeMs(),
                c.devicePublicKey(),c.devicePublicKeyFingerprint(),EcKeyUtils.b64u(EcKeyUtils.signRaw(other.getPrivate(),bytes)),1);
            assertThrows(IllegalArgumentException.class,()->NodeTimeProtocol.verifyChallenge(bad));
        }
        var wrongIssuer=CredentialIssuerService.withStableKey((ECPrivateKey)other.getPrivate(),(ECPublicKey)other.getPublic(),3600);
        assertThrows(IllegalArgumentException.class,()->new NodeTimeProofVerifier(issuer()).verify(wrongIssuer.issueTimeProof(c,1791748800123L),c));
    }
}
