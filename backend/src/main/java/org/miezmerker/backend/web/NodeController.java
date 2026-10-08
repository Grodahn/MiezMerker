package org.miezmerker.backend.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.miezmerker.backend.crypto.CredentialIssuerService;
import org.miezmerker.backend.crypto.EcKeyUtils;
import org.miezmerker.backend.crypto.NodeClaimVerifier;
import org.miezmerker.backend.domain.NodeDevice;
import org.miezmerker.backend.domain.NodeState;
import org.miezmerker.backend.repo.NodeRepository;
import org.miezmerker.backend.repo.OrganizationRepository;
import org.miezmerker.backend.security.AppUserDetails;
import org.miezmerker.backend.security.TenantService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Node provisioning/claiming (#18).
 *
 * <p>Node identity is a persistent random UUIDv4 plus a persistent P-256 device key pair
 * (never MAC/DB id). Claim requires ACTIVE ADMIN + UNCLAIMED node + physical claim mode
 * (verified via the node's signed claim advertisement). The operation is atomic and
 * idempotent for retries. Cross-organization reuse requires factory reset/new identity.
 *
 * <p>Node (#50) = physical bowl + electronics. {@code node_id} is the stable
 * technical identity; {@code displayName} is an optional human-readable bowl
 * label (nullable, never unique, never part of BLE/crypto identity).
 */
@RestController
@RequestMapping("/api/v1/nodes")
public class NodeController {
    private final NodeRepository nodes;
    private final OrganizationRepository organizations;
    private final TenantService tenants;
    private final CredentialIssuerService issuer;
    private final NodeClaimVerifier claimVerifier;

    public NodeController(NodeRepository nodes, OrganizationRepository organizations,
            TenantService tenants, CredentialIssuerService issuer,
            NodeClaimVerifier claimVerifier) {
        this.nodes = nodes;
        this.organizations = organizations;
        this.tenants = tenants;
        this.issuer = issuer;
        this.claimVerifier = claimVerifier;
    }

    @Schema(name = "ClaimNodeRequest")
    public record ClaimNodeRequest(
            @NotNull UUID nodeId,
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{43}$") String publicKeyX,
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{43}$") String publicKeyY,
            @Size(max = 64) String firmwareVersion,
            @NotNull UUID organizationId,
            @NotNull Long timestampMillis,
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{86}$") String claimSignature) {}

    @Schema(name = "ClaimReceiptView")
    public record ClaimReceiptView(UUID nodeId, String organizationId, String receipt,
            String tokenType) {}

    @Schema(name = "NodeView")
    public record NodeView(UUID nodeId, String organizationId, String state,
            String firmwareVersion, String protocolVersion, String statusNote,
            @Schema(type = "string", nullable = true,
                    description = "Optional human-readable bowl label (Node.displayName, "
                    + "trimmed; blank means no name; never unique, never part of "
                    + "BLE/crypto identity)") String displayName,
            String lastContactAt, String claimedAt, String publicKeyX,
            String publicKeyY, String fingerprint) {}

    @Schema(name = "UpdateNodeRequest")
    public record UpdateNodeRequest(
            @Size(max = 64) String firmwareVersion,
            @Size(max = 32) String protocolVersion,
            @Size(max = 500) String statusNote,
            @Size(max = 100, message = "displayName must not exceed 100 characters")
            @Schema(type = "string", nullable = true,
                    description = "Optional bowl label update. Absent/null leaves the name "
                    + "unchanged; blank clears it to null; a value is trimmed "
                    + "(max 100 characters, Unicode allowed) and never unique.") String displayName) {
        public UpdateNodeRequest {
            // Preserve the difference between null (no update) and blank (clear),
            // and validate the trimmed length rather than raw padding.
            if (displayName != null) {
                String normalized = NodeDevice.normalizeDisplayName(displayName);
                displayName = normalized == null ? "" : normalized;
            }
        }
    }

    @Schema(name = "NodeOwnerView")
    public record NodeOwnerView(UUID nodeId, String state, String organizationId,
            String organizationSlug, String organizationName, String publicContact) {}

    private static NodeView toView(NodeDevice n) {
        return new NodeView(n.getNodeId(),
                n.getOrganization() == null ? null : n.getOrganization().getId().toString(),
                n.getState().name(), n.getFirmwareVersion(), n.getProtocolVersion(),
                n.getStatusNote(), n.getDisplayName(),
                n.getLastContactAt() == null ? null : n.getLastContactAt().toString(),
                n.getClaimedAt() == null ? null : n.getClaimedAt().toString(),
                n.getPublicKeyX(), n.getPublicKeyY(), n.getFingerprint());
    }

    @PostMapping(value = "/claim", consumes = "application/json", produces = "application/json")
    @Operation(operationId = "claimNode",
            summary = "Claim an UNCLAIMED node for my organization (ACTIVE ADMIN + claim mode)")
    @Transactional
    public ClaimReceiptView claim(@Valid @RequestBody ClaimNodeRequest request,
            @AuthenticationPrincipal AppUserDetails principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        // Only ACTIVE ADMIN may claim; PENDING/MEMBER/DISABLED get 403 here.
        tenants.requireAdmin(principal.getId(), request.organizationId());
        if (request.nodeId().version() != 4 || request.nodeId().variant() != 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "node identity must be UUIDv4");
        }
        var org = organizations.findById(request.organizationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        String fingerprint;
        try {
            fingerprint = EcKeyUtils.fingerprintOfXY(request.publicKeyX(), request.publicKeyY());
            EcKeyUtils.publicFromXY(request.publicKeyX(), request.publicKeyY());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid node public key");
        }
        nodes.lockClaims();
        NodeDevice existing = nodes.findById(request.nodeId()).orElse(null);
        boolean retry = existing != null && existing.getState() == NodeState.CLAIMED
                && existing.getOrganization().getId().equals(org.getId())
                && existing.getFingerprint().equals(fingerprint);
        try {
            claimVerifier.verify(request.nodeId(), request.publicKeyX(), request.publicKeyY(),
                    request.timestampMillis(), request.claimSignature(), !retry);
        } catch (IllegalArgumentException e) {
            // Includes: outside claim mode (bad signature/marker), stale advertisement.
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "claim mode proof invalid: "
                    + e.getMessage());
        }

        if (existing != null) {
            if (existing.getState() == NodeState.CLAIMED) {
                if (!existing.getOrganization().getId().equals(org.getId())) {
                    // Another organization's node: no takeover without factory reset/new identity.
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "node already claimed by another organization");
                }
                if (!existing.getFingerprint().equals(fingerprint)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "node identity key mismatch; factory reset required");
                }
                // Deterministic retry: same org + same identity returns a fresh receipt
                // without creating partial state.
                String receipt = issuer.issueClaimReceipt(existing.getNodeId(), org.getId(),
                        org.getSlug(), org.getDisplayName(), org.getPublicContact(),
                        existing.getPublicKeyX(), existing.getPublicKeyY(),
                        existing.getFingerprint(), Instant.now());
                return new ClaimReceiptView(existing.getNodeId(), org.getId().toString(), receipt,
                        "Claim-ES256-JWT");
            }
            // Known UNCLAIMED row (e.g. pre-registered): adopt it atomically.
            if (!existing.getFingerprint().equals(fingerprint)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "node identity key mismatch; factory reset required");
            }
            existing.claim(org);
            nodes.save(existing);
            String receipt = issuer.issueClaimReceipt(existing.getNodeId(), org.getId(),
                    org.getSlug(), org.getDisplayName(), org.getPublicContact(),
                    existing.getPublicKeyX(), existing.getPublicKeyY(),
                    existing.getFingerprint(), Instant.now());
            return new ClaimReceiptView(existing.getNodeId(), org.getId().toString(), receipt,
                    "Claim-ES256-JWT");
        }

        // A reset must rotate both the UUID and device key. Reject duplicate keys
        // explicitly instead of letting the unique constraint become a 500.
        if (nodes.findByFingerprint(fingerprint).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "node device key already registered; factory reset must rotate the key");
        }
        // Factory-new node: create CLAIMED atomically (no half-claimed state).
        NodeDevice node = new NodeDevice(request.nodeId(), request.publicKeyX(),
                request.publicKeyY(), fingerprint,
                request.firmwareVersion());
        node.claim(org);
        nodes.save(node);
        String receipt = issuer.issueClaimReceipt(node.getNodeId(), org.getId(), org.getSlug(),
                org.getDisplayName(), org.getPublicContact(), node.getPublicKeyX(),
                node.getPublicKeyY(), node.getFingerprint(), Instant.now());
        return new ClaimReceiptView(node.getNodeId(), org.getId().toString(), receipt,
                "Claim-ES256-JWT");
    }

    @GetMapping(produces = "application/json")
    @Operation(operationId = "listNodes",
            summary = "List nodes of an organization (requires ACTIVE membership)")
    @Transactional(readOnly = true)
    public List<NodeView> list(@RequestParam UUID organizationId,
            @AuthenticationPrincipal AppUserDetails principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        tenants.requireActive(principal.getId(), organizationId);
        return nodes.findByOrganizationId(organizationId).stream().map(NodeController::toView)
                .toList();
    }

    @GetMapping(value = "/{nodeId}", produces = "application/json")
    @Operation(operationId = "getNode",
            summary = "Node details; only members of the owning organization "
                    + "(foreign orgs use the public /owner endpoint)")
    @Transactional(readOnly = true)
    public NodeView get(@PathVariable UUID nodeId,
            @AuthenticationPrincipal AppUserDetails principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        NodeDevice node = nodes.findById(nodeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (node.getOrganization() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        tenants.requireActive(principal.getId(), node.getOrganization().getId());
        return toView(node);
    }

    @GetMapping(value = "/{nodeId}/owner", produces = "application/json")
    @Operation(operationId = "getNodeOwner",
            summary = "Public owner hint for a claimed node; no observations/chip data")
    @Transactional(readOnly = true)
    public NodeOwnerView owner(@PathVariable UUID nodeId) {
        NodeDevice node = nodes.findById(nodeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (node.getOrganization() == null || node.getState() != NodeState.CLAIMED) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        var org = node.getOrganization();
        return new NodeOwnerView(node.getNodeId(), node.getState().name(), org.getId().toString(),
                org.getSlug(), org.getDisplayName(), org.getPublicContact());
    }

    @PatchMapping(value = "/{nodeId}", consumes = "application/json",
            produces = "application/json")
    @Operation(operationId = "updateNode",
            summary = "ACTIVE member updates node metadata (firmware/protocol version, "
                    + "status note, bowl display name)")
    @Transactional
    public NodeView update(@PathVariable UUID nodeId,
            @Valid @RequestBody UpdateNodeRequest request,
            @AuthenticationPrincipal AppUserDetails principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        // Serialize metadata updates with ingest/contact and deployment writes.
        NodeDevice node = nodes.findByIdLocked(nodeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (node.getOrganization() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        try {
            tenants.requireActive(principal.getId(), node.getOrganization().getId());
        } catch (ResponseStatusException e) {
            if (e.getStatusCode() != HttpStatus.FORBIDDEN) throw e;
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (request != null) {
            if (request.firmwareVersion() != null) {
                node.setFirmwareVersion(
                        request.firmwareVersion().isBlank() ? null
                                : request.firmwareVersion().trim());
            }
            if (request.protocolVersion() != null) {
                node.setProtocolVersion(
                        request.protocolVersion().isBlank() ? null
                                : request.protocolVersion().trim());
            }
            if (request.statusNote() != null) {
                node.setStatusNote(
                        request.statusNote().isBlank() ? null : request.statusNote().trim());
            }
            if (request.displayName() != null) {
                // Compact constructor already normalized: "" means blank (clear),
                // otherwise a trimmed non-empty value. Length is validated after
                // trimming (max 100); never silently truncated, never unique.
                if (request.displayName().isEmpty()) {
                    node.setDisplayName(null);
                } else {
                    if (request.displayName().length()
                            > NodeDevice.MAX_DISPLAY_NAME_LENGTH) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "displayName must not exceed "
                                        + NodeDevice.MAX_DISPLAY_NAME_LENGTH
                                        + " characters");
                    }
                    node.setDisplayName(request.displayName());
                }
            }
            nodes.save(node);
        }
        return toView(node);
    }
}
