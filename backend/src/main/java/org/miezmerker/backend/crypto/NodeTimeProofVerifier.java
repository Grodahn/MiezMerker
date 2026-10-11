package org.miezmerker.backend.crypto;

import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.ECPublicKey;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Evidence verification only: no anchor persistence or claim/authorization decision. */
@Component
public class NodeTimeProofVerifier {
    private final CredentialIssuerService issuer;
    public NodeTimeProofVerifier(CredentialIssuerService issuer) { this.issuer = issuer; }

    public JsonNode verify(String proof, NodeTimeProtocol.Challenge challenge) {
        NodeTimeProtocol.validate(challenge);
        try {
            if (proof == null || proof.length() > 2048) throw new IllegalArgumentException();
            String[] parts = proof.split("\\.",-1);
            if (parts.length != 3) throw new IllegalArgumentException();
            byte[][] decoded = new byte[3][];
            for (int i=0;i<3;i++) {
                if (!parts[i].matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException();
                decoded[i] = EcKeyUtils.b64uDecode(parts[i]);
                if (!EcKeyUtils.b64u(decoded[i]).equals(parts[i])) throw new IllegalArgumentException();
            }
            if (decoded[2].length != 64) throw new IllegalArgumentException();
            JsonNode header = NodeTimeProtocol.json(decoded[0]);
            NodeTimeProtocol.fields(header,"alg","kid");
            if (!"ES256".equals(NodeTimeProtocol.text(header,"alg"))
                    || !CredentialIssuerService.KEY_ID.equals(NodeTimeProtocol.text(header,"kid"))) throw new IllegalArgumentException();
            JsonNode p = NodeTimeProtocol.json(decoded[1]);
            NodeTimeProtocol.fields(p,"iss","kid","kind","ver","node","incarnation","boot","nonce","dpf","utc_ms","jti");
            if (!CredentialIssuerService.ISSUER.equals(NodeTimeProtocol.text(p,"iss"))
                    || !CredentialIssuerService.KEY_ID.equals(NodeTimeProtocol.text(p,"kid"))
                    || !"node-time-bootstrap".equals(NodeTimeProtocol.text(p,"kind"))
                    || !p.path("ver").isIntegralNumber() || !p.path("ver").canConvertToLong() || p.path("ver").asLong() != 1
                    || !p.path("boot").isIntegralNumber() || !p.path("boot").canConvertToLong() || p.path("boot").asLong() != challenge.bootCounter()
                    || !challenge.nodeId().equals(NodeTimeProtocol.text(p,"node"))
                    || !challenge.incarnation().equals(NodeTimeProtocol.text(p,"incarnation"))
                    || !challenge.challengeNonce().equals(NodeTimeProtocol.text(p,"nonce"))
                    || !challenge.devicePublicKeyFingerprint().equals(NodeTimeProtocol.text(p,"dpf"))
                    || !p.path("utc_ms").isIntegralNumber() || !p.path("utc_ms").canConvertToLong()
                    || p.path("utc_ms").asLong() <= 0) throw new IllegalArgumentException();
            NodeTimeProtocol.uuid(NodeTimeProtocol.text(p,"jti"));
            if (!SignedJWT.parse(proof).verify(new ECDSAVerifier(issuer.getPublicKey()))) throw new IllegalArgumentException();
            return p;
        } catch (Exception e) { throw new IllegalArgumentException("Invalid time proof"); }
    }

    /** trustedDeviceKey MUST be registered or subsequently verified by physical claiming (#132). */
    public void verifyAcceptance(String proof, NodeTimeProtocol.Challenge c,
            NodeTimeProtocol.Acceptance a, ECPublicKey trustedDeviceKey) {
        verify(proof,c);
        if (!EcKeyUtils.fingerprintOf(trustedDeviceKey).equals(c.devicePublicKeyFingerprint()))
            throw new IllegalArgumentException("Invalid acceptance key");
        // Challenge uptime is not a JWS/acceptance field: its device signature preserves
        // the latency bound used by downstream uncertainty reconstruction.
        NodeTimeProtocol.verifyChallenge(c);
        if (!EcKeyUtils.verifyRaw(trustedDeviceKey,NodeTimeProtocol.acceptanceBytes(c,proof,a),
                        NodeTimeProtocol.decode(a.signature(),64)))
            throw new IllegalArgumentException("Invalid acceptance");
    }
}
