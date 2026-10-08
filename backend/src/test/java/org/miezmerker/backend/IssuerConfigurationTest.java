package org.miezmerker.backend;

import org.junit.jupiter.api.Tag;

import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.miezmerker.backend.crypto.CredentialIssuerService;
import org.miezmerker.backend.crypto.EcKeyUtils;
import static org.junit.jupiter.api.Assertions.*;

@Tag("auth")
class IssuerConfigurationTest {
    @Test
    void configuredDerKeysSurviveRestartAndMismatchedKeysFailAtStartup() throws Exception {
        var pair = EcKeyUtils.generateP256();
        String privateKey = EcKeyUtils.pkcs8B64(pair.getPrivate());
        String publicKey = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        assertThrows(IllegalArgumentException.class, () -> new CredentialIssuerService("", publicKey, 168));
        assertThrows(IllegalArgumentException.class, () -> new CredentialIssuerService(privateKey, "", 168));
        var first = new CredentialIssuerService(privateKey, publicKey, 168);
        var restarted = new CredentialIssuerService(privateKey, publicKey, 168);
        var device = (ECPublicKey) EcKeyUtils.generateP256().getPublic();
        String token = first.issueOfflineCredential(UUID.randomUUID(), UUID.randomUUID(), "org",
                UUID.randomUUID(), EcKeyUtils.xOf(device), EcKeyUtils.yOf(device),
                EcKeyUtils.fingerprintOf(device), "MEMBER", Instant.now());
        assertNotNull(restarted.verify(token));
        var other = EcKeyUtils.generateP256();
        assertThrows(IllegalArgumentException.class, () -> new CredentialIssuerService(privateKey,
                Base64.getEncoder().encodeToString(other.getPublic().getEncoded()), 168));
        assertThrows(IllegalArgumentException.class, () -> CredentialIssuerService.withStableKey(
                (ECPrivateKey) pair.getPrivate(), (ECPublicKey) other.getPublic(), 3600));
    }

    @Test
    void nonP256IssuerKeysAreRejected() throws Exception {
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        var pair = generator.generateKeyPair();
        assertThrows(IllegalArgumentException.class, () -> new CredentialIssuerService(
                EcKeyUtils.pkcs8B64(pair.getPrivate()),
                Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()), 168));
    }
}
