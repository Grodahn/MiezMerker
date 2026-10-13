package org.miezmerker.backend.service;

import java.util.UUID;
import org.miezmerker.backend.crypto.BootstrapTimeSource;
import org.miezmerker.backend.crypto.CredentialIssuerService;
import org.miezmerker.backend.crypto.EcKeyUtils;
import org.miezmerker.backend.crypto.NodeTimeProtocol;
import org.miezmerker.backend.domain.NodeState;
import org.miezmerker.backend.repo.NodeRepository;
import org.miezmerker.backend.security.TenantService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class NodeTimeService {
    @io.swagger.v3.oas.annotations.media.Schema(name="NodeTimeProofView",requiredProperties={"proof","tokenType","issuer","keyId","version"})
    public record ProofView(String proof, String tokenType, String issuer, String keyId, int version) {}
    private final NodeRepository nodes;
    private final TenantService tenants;
    private final CredentialIssuerService issuer;
    private final BootstrapTimeSource time;
    private final NodeTimeRateLimiter limits;
    public NodeTimeService(NodeRepository nodes, TenantService tenants, CredentialIssuerService issuer,
            BootstrapTimeSource time, NodeTimeRateLimiter limits) {
        this.nodes = nodes;
        this.tenants = tenants;
        this.issuer = issuer;
        this.time = time;
        this.limits = limits;
    }
    @Transactional
    public ProofView issue(UUID user, NodeTimeProtocol.Request request) {
        UUID org = NodeTimeProtocol.uuid(request.organizationId());
        var c = request.challenge();
        // Tenant context first; never return any owning organization's metadata.
        tenants.requireActive(user,org);
        try { NodeTimeProtocol.verifyChallenge(c); }
        catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Time bootstrap denied"); }
        // Same DB lock as claiming: protects absent rows as well as registered identities.
        nodes.lockClaims();
        var existing = nodes.findById(NodeTimeProtocol.uuid(c.nodeId())).orElse(null);
        boolean claimed = "CLAIMED".equals(request.registrationState());
        if (claimed) {
            if (existing == null || existing.getState() != NodeState.CLAIMED
                    || existing.getOrganization() == null || !org.equals(existing.getOrganization().getId())) throw denied();
        } else {
            tenants.requireAdmin(user,org);
            // Interrupted registration can reserve ownership before receipt delivery.
            // After reboot that same ADMIN/key needs time again to verify a receipt.
            // The checks below still bind the exact registered device key.
            if (existing != null && (existing.getState() != NodeState.UNCLAIMED || existing.getOrganization() != null)
                    && (existing.getState() != NodeState.CLAIMED || existing.getOrganization() == null
                        || !org.equals(existing.getOrganization().getId()))) throw denied();
            if (existing == null && nodes.findByFingerprint(c.devicePublicKeyFingerprint()).isPresent()) throw denied();
        }
        if (existing != null && (!existing.getFingerprint().equals(c.devicePublicKeyFingerprint())
                || !existing.getPublicKeyX().equals(EcKeyUtils.xOf(NodeTimeProtocol.key(c)))
                || !existing.getPublicKeyY().equals(EcKeyUtils.yOf(NodeTimeProtocol.key(c))))) throw denied();
        // Shared victim budgets only after membership, possession and registered ownership/key checks.
        limits.acquire("org",org,120);
        limits.acquire("node",c.nodeId(),10);
        if (!claimed) limits.acquire("unclaimed-user",user,5);
        // Sample only after validation/lock waits. No async signing or client wall time.
        String proof;
        try { proof = issuer.issueTimeProof(c,time.issuanceMillis()); }
        catch (IllegalStateException e) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Time issuance unavailable"); }
        return new ProofView(proof,"Node-Time-ES256-JWS",CredentialIssuerService.ISSUER,CredentialIssuerService.KEY_ID,1);
    }
    private static ResponseStatusException denied() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN,"Time bootstrap denied");
    }
}
