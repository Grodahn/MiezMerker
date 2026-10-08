package org.miezmerker.backend;

import org.junit.jupiter.api.Tag;

import com.nimbusds.jwt.JWTClaimsSet;
import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.miezmerker.backend.crypto.CredentialIssuerService;
import org.miezmerker.backend.crypto.EcKeyUtils;
import org.miezmerker.backend.crypto.NodeClaimVerifier;

/**
 * Generates deterministic interoperability vectors for #17/#18 (committed under
 * {@code protocol/fixtures/}). Uses fixed keys, fixed ids and far-future expiry so that
 * ESP32-C3 firmware and PWA/BLE work can verify exactly the same bytes and signatures.
 *
 * <p>ECDSA signatures themselves use random nonces; the committed signatures are single
 * valid examples, not expected byte-identical outputs of future sign operations.
 */
@Tag("auth")
class InteropVectorTest {
    private static final java.nio.file.Path FIXTURE = java.nio.file.Path.of("..", "protocol",
            "fixtures", "offline-credential-v1.json");
    // Fixed keys generated once for vectors (P-256). Do NOT use in production.
    // Issuer keypair for vectors:
    // (generated 2026-10-02, committed for interop; production keys come from env/file)
    @Test
    void generateVectors() throws Exception {
        // Fixed deterministic key material: derive from hashed labels (not SecureRandom)
        // so regeneration yields identical keys. We hash a label with SHA-256 to get a
        // private scalar candidate, then find the first valid P-256 scalar by incrementing.
        KeyPair issuerKp = deterministicKey("miezmerker-vector-issuer-v1");
        KeyPair deviceKp = deterministicKey("miezmerker-vector-device-v1");
        KeyPair nodeKp = deterministicKey("miezmerker-vector-node-v1");

        ECPublicKey issuerPub = (ECPublicKey) issuerKp.getPublic();
        ECPublicKey devicePub = (ECPublicKey) deviceKp.getPublic();
        ECPublicKey nodePub = (ECPublicKey) nodeKp.getPublic();

        UUID userId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        UUID orgId = UUID.fromString("22222222-2222-4222-8222-222222222222");
        UUID deviceId = UUID.fromString("33333333-3333-4333-8333-333333333333");
        UUID nodeId = UUID.fromString("44444444-4444-4444-8444-444444444444");

        String deviceX = EcKeyUtils.xOf(devicePub);
        String deviceY = EcKeyUtils.yOf(devicePub);
        String deviceFp = EcKeyUtils.fingerprintOf(devicePub);
        String nodeX = EcKeyUtils.xOf(nodePub);
        String nodeY = EcKeyUtils.yOf(nodePub);
        String nodeFp = EcKeyUtils.fingerprintOf(nodePub);

        CredentialIssuerService issuer = CredentialIssuerService.withStableKey(
                (ECPrivateKey) issuerKp.getPrivate(), issuerPub, 7 * 24 * 3600L);
        Instant iat = Instant.parse("2026-10-02T00:00:00Z");
        Instant exp = Instant.parse("2030-10-02T00:00:00Z");
        String credential = issuer.issueOfflineCredentialWithTimes(userId, orgId, "vector-org",
                deviceId, deviceX, deviceY, deviceFp, "MEMBER", iat, exp);
        String claimReceipt = issuer.issueClaimReceipt(nodeId, orgId, "vector-org", "Vector Org",
                "help@vector.example", nodeX, nodeY, nodeFp, iat);

        // Fixed 32-byte challenge 00..1f and one valid PoP signature by the device key.
        byte[] challenge = new byte[32];
        for (int i = 0; i < 32; i++) {
            challenge[i] = (byte) i;
        }
        byte[] popSig = EcKeyUtils.signRaw(deviceKp.getPrivate(), challenge);

        // Fixed claim advertisement (timestamp fixed for vectors; live verification
        // enforces 5-minute freshness, so vectors document format, not freshness).
        long claimTs = 1759363200000L; // 2026-10-02T00:00:00Z
        String claimSig = NodeClaimVerifier.signClaim(nodeKp.getPrivate(), nodeId, nodeX, nodeY,
                claimTs);
        byte[] claimMessage = NodeClaimVerifier.claimMessage(nodeId, nodeX, nodeY, claimTs);

        var mapper = new tools.jackson.databind.ObjectMapper();
        var root = mapper.createObjectNode();
        root.put("format", "miezmerker-offline-v1");
        root.put("description", "Interop vectors for JWT ES256 offline credentials, PoP and claim. "
                + "Verify signatures with P-256/SHA-256; ECDSA signatures are single valid examples.");
        var algs = mapper.createObjectNode();
        algs.put("credential", "JWS compact, ES256 (ECDSA P-256 + SHA-256), kid=miezmerker-issuer-v1");
        algs.put("challenge_signature", "ECDSA P-256 + SHA-256 over raw 32-byte challenge, raw 64-byte r||s base64url");
        algs.put("fingerprint", "base64url(SHA-256(0x04 || x || y))");
        algs.put("claim_message", "ASCII 'MM-CLAIM-v1\\n{nodeId}\\n{x}\\n{y}\\n{timestampMillis}\\nclaim-mode'");
        root.set("algorithms", algs);

        var issuerNode = mapper.createObjectNode();
        issuerNode.put("kid", CredentialIssuerService.KEY_ID);
        issuerNode.put("x", EcKeyUtils.xOf(issuerPub));
        issuerNode.put("y", EcKeyUtils.yOf(issuerPub));
        issuerNode.put("fingerprint", EcKeyUtils.fingerprintOf(issuerPub));
        root.set("issuer_public_key", issuerNode);

        var devNode = mapper.createObjectNode();
        devNode.put("x", deviceX);
        devNode.put("y", deviceY);
        devNode.put("fingerprint", deviceFp);
        root.set("device_public_key", devNode);

        var nodeKeyNode = mapper.createObjectNode();
        nodeKeyNode.put("x", nodeX);
        nodeKeyNode.put("y", nodeY);
        nodeKeyNode.put("fingerprint", nodeFp);
        root.set("node_public_key", nodeKeyNode);

        root.put("user_id", userId.toString());
        root.put("organization_id", orgId.toString());
        root.put("organization_slug", "vector-org");
        root.put("device_id", deviceId.toString());
        root.put("node_id", nodeId.toString());
        root.put("credential_jwt", credential);
        root.put("claim_receipt_jwt", claimReceipt);
        root.put("challenge_hex", HexFormat.of().formatHex(challenge));
        root.put("challenge_signature_b64u",
                Base64.getUrlEncoder().withoutPadding().encodeToString(popSig));
        root.put("claim_timestamp_millis", claimTs);
        root.put("claim_message_ascii", new String(claimMessage, java.nio.charset.StandardCharsets.UTF_8));
        root.put("claim_signature_b64u", claimSig);
        root.put("credential_expires", exp.toString());
        root.put("revocation_note", "DISABLED membership/device blocks new issuance; "
                + "already-issued credentials stay valid until exp (no immediate offline revocation).");

        java.nio.file.Path alt = java.nio.file.Path.of("target", "interop-vectors.json");
        java.nio.file.Files.createDirectories(alt.toAbsolutePath().getParent());
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n";
        java.nio.file.Files.writeString(alt, json);
        // Verify the committed bytes. Generating random signatures must never overwrite
        // the shared golden fixture or hide a broken/corrupted published credential.
        var fixed = mapper.readTree(java.nio.file.Files.readString(FIXTURE));
        org.junit.jupiter.api.Assertions.assertEquals(issuer.publicXB64u(),
                fixed.get("issuer_public_key").get("x").asText());
        org.junit.jupiter.api.Assertions.assertEquals(orgId.toString(),
                issuer.verify(fixed.get("credential_jwt").asText()).getStringClaim("org"));
        byte[] fixedChallenge = HexFormat.of().parseHex(fixed.get("challenge_hex").asText());
        byte[] fixedProof = EcKeyUtils.b64uDecode(fixed.get("challenge_signature_b64u").asText());
        org.junit.jupiter.api.Assertions.assertTrue(EcKeyUtils.verifyRaw(devicePub, fixedChallenge, fixedProof));
    }

    private static KeyPair deterministicKey(String label) throws Exception {
        java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-256");
        byte[] seed = sha.digest(label.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        // Use SHA1PRNG with fixed seed for deterministic P-256 generation in tests.
        java.security.SecureRandom random = java.security.SecureRandom.getInstance("SHA1PRNG");
        random.setSeed(seed);
        java.security.KeyPairGenerator gen = java.security.KeyPairGenerator.getInstance("EC");
        gen.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"), random);
        return gen.generateKeyPair();
    }
}
