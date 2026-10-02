package org.miezmerker.backend.crypto;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Backend issuer for offline BLE credentials (#17) and claim receipts (#18).
 *
 * <p>Format: JWS compact (JWT), ES256 (ECDSA P-256 + SHA-256). See ADR-0012 for why JWT
 * was chosen over CWT/COSE (simpler across Spring Boot, Web Crypto and ESP32-C3 while
 * remaining standards-based: RFC 7515/7519).
 *
 * <p>Credential claims (offline BLE):
 * {@code iss, sub=userId, org=organizationId, org_slug, dev=deviceId, dpk_x, dpk_y,
 * dpf=device fingerprint, role, scope=["node:sync"], iat, exp, jti, ver=1}.
 *
 * <p>Claim receipt claims: {@code iss, sub=nodeId, org, org_slug, org_name, contact,
 * ndpk_x, ndpk_y, ndpf, iat, exp (long-lived, e.g. 10 years for provisioning trust),
 * jti, ver=1, kind="claim"}.
 */
@Service
public class CredentialIssuerService {
    private static final Logger log = LoggerFactory.getLogger(CredentialIssuerService.class);

    public static final String ISSUER = "miezmerker";
    public static final String KEY_ID = "miezmerker-issuer-v1";
    public static final int CREDENTIAL_VERSION = 1;

    private final ECPrivateKey privateKey;
    private final ECPublicKey publicKey;
    private final long ttlSeconds;

    @org.springframework.beans.factory.annotation.Autowired
    public CredentialIssuerService(
            @Value("${miezmerker.issuer.private-key-pkcs8-b64:}") String privateKeyB64,
            @Value("${miezmerker.issuer.public-key-spki-b64:}") String publicKeySpkiB64,
            @Value("${miezmerker.credential.ttl-hours:168}") long ttlHours) {
        if (privateKeyB64 != null && !privateKeyB64.isBlank()) {
            if (publicKeySpkiB64 == null || publicKeySpkiB64.isBlank()) {
                throw new IllegalArgumentException(
                        "miezmerker.issuer.public-key-spki-b64 is required together with "
                        + "miezmerker.issuer.private-key-pkcs8-b64. See docs/bootstrap.md.");
            }
            try {
                java.security.KeyFactory kf = java.security.KeyFactory.getInstance("EC");
                byte[] privDer = Base64.getDecoder().decode(privateKeyB64.trim());
                byte[] pubDer = Base64.getDecoder().decode(publicKeySpkiB64.trim());
                ECPrivateKey priv = (ECPrivateKey) kf.generatePrivate(
                        new java.security.spec.PKCS8EncodedKeySpec(privDer));
                ECPublicKey pub = (ECPublicKey) kf.generatePublic(
                        new java.security.spec.X509EncodedKeySpec(pubDer));
                this.privateKey = priv;
                this.publicKey = pub;
                this.ttlSeconds = ttlHours * 3600L;
                log.info("Using configured credential issuer key (kid={}).", KEY_ID);
                return;
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid issuer key configuration", e);
            }
        }
        // Ephemeral issuer key (default for local/test). Production must pin a stable key
        // via properties; see IssuerKeyConfig for the stable-key constructor.
        KeyPair kp = EcKeyUtils.generateP256();
        this.privateKey = (ECPrivateKey) kp.getPrivate();
        this.publicKey = (ECPublicKey) kp.getPublic();
        this.ttlSeconds = ttlHours * 3600L;
        log.warn("Using ephemeral credential issuer key (kid={}). "
                + "Configure a stable miezmerker.issuer key for production.", KEY_ID);
    }

    /** Stable-key factory for tests/vectors (not a Spring bean constructor). */
    public static CredentialIssuerService withStableKey(ECPrivateKey privateKey,
            ECPublicKey publicKey, long ttlSeconds) {
        return new CredentialIssuerService(privateKey, publicKey, ttlSeconds, true);
    }

    private CredentialIssuerService(ECPrivateKey privateKey, ECPublicKey publicKey,
            long ttlSeconds, boolean stable) {
        this.privateKey = privateKey;
        this.publicKey = publicKey;
        this.ttlSeconds = ttlSeconds;
    }

    public ECPublicKey getPublicKey() {
        return publicKey;
    }

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    public String publicXB64u() {
        return EcKeyUtils.xOf(publicKey);
    }

    public String publicYB64u() {
        return EcKeyUtils.yOf(publicKey);
    }

    public String publicFingerprint() {
        return EcKeyUtils.fingerprintOf(publicKey);
    }

    public String issueOfflineCredential(UUID userId, UUID organizationId, String orgSlug,
            UUID deviceId, String deviceX, String deviceY, String deviceFingerprint,
            String role, Instant now) {
        Instant expires = now.plusSeconds(ttlSeconds);
        return issueOfflineCredentialWithTimes(userId, organizationId, orgSlug, deviceId, deviceX,
                deviceY, deviceFingerprint, role, now, expires);
    }

    /** Test/edge-case hook for expired vectors (never used by production issuance). */
    public String issueOfflineCredentialWithTimes(UUID userId, UUID organizationId, String orgSlug,
            UUID deviceId, String deviceX, String deviceY, String deviceFingerprint,
            String role, Instant issuedAt, Instant expiresAt) {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(userId.toString())
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(expiresAt))
                .claim("ver", CREDENTIAL_VERSION)
                .claim("kind", "offline")
                .claim("org", organizationId.toString())
                .claim("org_slug", orgSlug)
                .claim("dev", deviceId.toString())
                .claim("dpk_x", deviceX)
                .claim("dpk_y", deviceY)
                .claim("dpf", deviceFingerprint)
                .claim("role", role)
                .claim("scope", List.of("node:sync"))
                .build();
        return sign(claims);
    }

    public String issueClaimReceipt(UUID nodeId, UUID organizationId, String orgSlug,
            String orgName, String contact, String nodeX, String nodeY, String nodeFingerprint,
            Instant now) {
        // Provisioning trust is long-lived: the node stores org + trust anchor durably.
        // 10 years is effectively "until factory reset"; rotation happens via re-claim
        // after reset (new identity), not via receipt expiry.
        Instant expires = now.plusSeconds(10L * 365 * 24 * 3600);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(nodeId.toString())
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(expires))
                .claim("ver", CREDENTIAL_VERSION)
                .claim("kind", "claim")
                .claim("org", organizationId.toString())
                .claim("org_slug", orgSlug)
                .claim("org_name", orgName)
                .claim("contact", contact)
                .claim("ndpk_x", nodeX)
                .claim("ndpk_y", nodeY)
                .claim("ndpf", nodeFingerprint)
                .claim("ipk_x", publicXB64u())
                .claim("ipk_y", publicYB64u())
                .build();
        return sign(claims);
    }

    private String sign(JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(KEY_ID).build(), claims);
            jwt.sign(new ECDSASigner(privateKey));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot sign JWT", e);
        }
    }

    public JWTClaimsSet verify(String compact) throws Exception {
        SignedJWT jwt = SignedJWT.parse(compact);
        if (!KEY_ID.equals(jwt.getHeader().getKeyID())) {
            throw new IllegalArgumentException("Unknown key id");
        }
        if (jwt.getHeader().getAlgorithm() == null
                || !JWSAlgorithm.ES256.equals(jwt.getHeader().getAlgorithm())) {
            throw new IllegalArgumentException("Only ES256 is accepted");
        }
        if (!jwt.verify(new ECDSAVerifier(publicKey))) {
            throw new IllegalArgumentException("Invalid issuer signature");
        }
        JWTClaimsSet claims = jwt.getJWTClaimsSet();
        Date exp = claims.getExpirationTime();
        if (exp == null || exp.toInstant().isBefore(Instant.now().minusSeconds(30))) {
            // 30s clock-skew tolerance for expiry only; iat freshness is checked by callers.
            throw new IllegalArgumentException("Credential expired");
        }
        if (!ISSUER.equals(claims.getIssuer())) {
            throw new IllegalArgumentException("Unknown issuer");
        }
        return claims;
    }

    public Map<String, String> publicJwk() {
        return Map.of(
                "kty", "EC",
                "crv", "P-256",
                "kid", KEY_ID,
                "x", publicXB64u(),
                "y", publicYB64u());
    }
}
