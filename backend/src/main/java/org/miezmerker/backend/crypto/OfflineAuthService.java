package org.miezmerker.backend.crypto;

import com.nimbusds.jwt.JWTClaimsSet;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Offline authorization checks for #17. All methods are pure and reusable by later BLE work (#6):
 * credential parsing/verification plus challenge/response proof-of-possession.
 *
 * <p>Node-side verification must be possible completely offline given only the issuer public
 * key, the presented credential and the challenge just issued. The credential embeds the
 * AppDevice public key ({@code dpk_x}/{@code dpk_y}), so the node can verify the challenge
 * signature without any backend contact.
 */
@Service
public class OfflineAuthService {
    private final CredentialIssuerService issuer;
    private final SecureRandom random = new SecureRandom();

    public OfflineAuthService(CredentialIssuerService issuer) {
        this.issuer = issuer;
    }

    /** 32-byte unpredictable challenge (node -> PWA per BLE connection). */
    public byte[] newChallenge() {
        byte[] challenge = new byte[32];
        random.nextBytes(challenge);
        return challenge;
    }

    public record VerifiedCredential(UUID userId, UUID organizationId, UUID deviceId,
            String deviceX, String deviceY, String deviceFingerprint, String role,
            List<String> scope, Instant issuedAt, Instant expiresAt) {}

    /**
     * Verify an offline credential for the given organization.
     *
     * @throws IllegalArgumentException if signature/expiry/org/scope/role checks fail
     */
    public VerifiedCredential verifyForOrganization(String compact, UUID expectedOrgId) throws Exception {
        JWTClaimsSet claims = issuer.verify(compact);
        if (!"offline".equals(claims.getStringClaim("kind"))) {
            throw new IllegalArgumentException("Not an offline credential");
        }
        Integer ver = extractInt(claims, "ver");
        if (ver == null || ver != CredentialIssuerService.CREDENTIAL_VERSION) {
            throw new IllegalArgumentException("Unsupported credential version");
        }
        String org = claims.getStringClaim("org");
        if (org == null || !org.equals(expectedOrgId.toString())) {
            throw new IllegalArgumentException("Credential organization mismatch");
        }
        List<String> scope = claims.getStringListClaim("scope");
        if (scope == null || !scope.contains("node:sync")) {
            throw new IllegalArgumentException("Missing node:sync scope");
        }
        String role = claims.getStringClaim("role");
        if (!"ADMIN".equals(role) && !"MEMBER".equals(role)) {
            throw new IllegalArgumentException("Unknown role in credential");
        }
        String deviceX = claims.getStringClaim("dpk_x");
        String deviceY = claims.getStringClaim("dpk_y");
        String deviceFp = claims.getStringClaim("dpf");
        if (deviceX == null || deviceY == null || deviceFp == null) {
            throw new IllegalArgumentException("Credential misses device binding");
        }
        // Fingerprint must match the embedded key; prevents fingerprint substitution.
        String recomputed = EcKeyUtils.fingerprintOfXY(deviceX, deviceY);
        if (!recomputed.equals(deviceFp)) {
            throw new IllegalArgumentException("Device fingerprint mismatch");
        }
        UUID userId = UUID.fromString(claims.getSubject());
        UUID deviceId = UUID.fromString(claims.getStringClaim("dev"));
        return new VerifiedCredential(userId, expectedOrgId, deviceId, deviceX, deviceY, deviceFp,
                role, scope, claims.getIssueTime().toInstant(), claims.getExpirationTime().toInstant());
    }

    /**
     * Verify proof-of-possession: {@code signature} must be raw 64-byte ECDSA/SHA256 over the
     * exact {@code challenge} bytes under the device key from the credential.
     */
    public boolean verifyProofOfPossession(String deviceX, String deviceY,
            byte[] challenge, byte[] rawSignature) {
        if (challenge == null || challenge.length != 32) {
            return false;
        }
        try {
            ECPublicKey key = EcKeyUtils.publicFromXY(deviceX, deviceY);
            return EcKeyUtils.verifyRaw(key, challenge, rawSignature);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Full offline decision used by nodes (and tests): credential valid for org AND
     * challenge signature valid under the credential-bound device key.
     */
    public boolean authorizeSync(String credential, UUID expectedOrgId,
            byte[] challenge, byte[] rawSignature) {
        try {
            VerifiedCredential verified = verifyForOrganization(credential, expectedOrgId);
            return verifyProofOfPossession(verified.deviceX(), verified.deviceY(),
                    challenge, rawSignature);
        } catch (Exception e) {
            return false;
        }
    }

    private static Integer extractInt(JWTClaimsSet claims, String name) {
        try {
            Object v = claims.getClaim(name);
            if (v instanceof Number n && n.doubleValue() == n.intValue()) {
                return n.intValue();
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
