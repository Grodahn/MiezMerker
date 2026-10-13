package org.miezmerker.backend.crypto;

import java.nio.charset.StandardCharsets;
import java.security.interfaces.ECPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Claim-advertisement verification for #18.
 *
 * <p>Canonical claim message (v1, ASCII, {@code \n}-joined):
 * <pre>
 * MM-CLAIM-v1\n{nodeId}\n{publicKeyX_b64u}\n{publicKeyY_b64u}\n{timestampMillis}\nclaim-mode
 * </pre>
 * The node signs these bytes with its device private key (ECDSA/SHA256, raw 64-byte
 * {@code r||s} transported as base64url). Any ready UNCLAIMED node may sign
 * this proof without a physical gate (#154). The historical {@code claim-mode}
 * suffix is retained as a domain-separation marker for wire compatibility; it
 * is not evidence of a button press. Signature and 5-minute freshness remain
 * mandatory for first ownership reservation. Same-owner/key recovery verifies
 * authenticity while allowing the original expired proof.
 */
@Service
public class NodeClaimVerifier {
    private static final long FRESHNESS_MILLIS = 5 * 60 * 1000L;

    public static byte[] claimMessage(UUID nodeId, String x, String y, long timestampMillis) {
        String text = "MM-CLAIM-v1\n" + nodeId + "\n" + x + "\n" + y + "\n" + timestampMillis
                + "\nclaim-mode";
        return text.getBytes(StandardCharsets.UTF_8);
    }

    public void verify(UUID nodeId, String x, String y, long timestampMillis,
            String signatureB64u) {
        verify(nodeId, x, y, timestampMillis, signatureB64u, true);
    }

    public void verify(UUID nodeId, String x, String y, long timestampMillis,
            String signatureB64u, boolean requireFresh) {
        if (timestampMillis <= 0) {
            throw new IllegalArgumentException("missing timestamp");
        }
        long age = Math.abs(Instant.now().toEpochMilli() - timestampMillis);
        if (requireFresh && age > FRESHNESS_MILLIS) {
            throw new IllegalArgumentException("stale claim advertisement");
        }
        byte[] sig;
        try {
            sig = Base64.getUrlDecoder().decode(signatureB64u);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid claim signature encoding");
        }
        ECPublicKey key;
        try {
            key = EcKeyUtils.publicFromXY(x, y);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid node public key");
        }
        byte[] message = claimMessage(nodeId, x, y, timestampMillis);
        if (!EcKeyUtils.verifyRaw(key, message, sig)) {
            throw new IllegalArgumentException("invalid claim signature");
        }
    }

    /** Helper for tests/fakes: sign a claim advertisement with the node private key. */
    public static String signClaim(java.security.PrivateKey nodePrivate, UUID nodeId, String x,
            String y, long timestampMillis) {
        byte[] message = claimMessage(nodeId, x, y, timestampMillis);
        byte[] raw = EcKeyUtils.signRaw(nodePrivate, message);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }
}
