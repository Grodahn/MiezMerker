package org.miezmerker.backend.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.miezmerker.backend.crypto.CredentialIssuerService;
import org.miezmerker.backend.crypto.EcKeyUtils;
import org.miezmerker.backend.domain.AppDevice;
import org.miezmerker.backend.domain.MembershipStatus;
import org.miezmerker.backend.domain.OrganizationMembership;
import org.miezmerker.backend.repo.AppDeviceRepository;
import org.miezmerker.backend.repo.AppUserRepository;
import org.miezmerker.backend.repo.MembershipRepository;
import org.miezmerker.backend.repo.OrganizationRepository;
import org.miezmerker.backend.security.AppUserDetails;
import org.miezmerker.backend.security.TenantService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * AppDevice registration and offline credential issuance (#17).
 *
 * <p>No shared organization secret: each browser/device installation owns a P-256 key pair.
 * The backend binds the public key to the user and issues short-lived, organization-bound
 * JWT credentials (ES256) that embed the device public key for offline proof-of-possession.
 */
@RestController
@RequestMapping("/api/v1")
public class DeviceController {
    private final AppDeviceRepository devices;
    private final AppUserRepository users;
    private final MembershipRepository memberships;
    private final OrganizationRepository organizations;
    private final TenantService tenants;
    private final CredentialIssuerService issuer;

    public DeviceController(AppDeviceRepository devices, AppUserRepository users,
            MembershipRepository memberships, OrganizationRepository organizations,
            TenantService tenants, CredentialIssuerService issuer) {
        this.devices = devices;
        this.users = users;
        this.memberships = memberships;
        this.organizations = organizations;
        this.tenants = tenants;
        this.issuer = issuer;
    }

    @Schema(name = "RegisterDeviceRequest")
    public record RegisterDeviceRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{43}$") String publicKeyX,
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{43}$") String publicKeyY,
            @Size(max = 120) String label) {}

    @Schema(name = "DeviceView")
    public record DeviceView(UUID id, String fingerprint, String label, String createdAt,
            String lastSeenAt, String revokedAt) {}

    @Schema(name = "IssueCredentialRequest")
    public record IssueCredentialRequest(@NotBlank String organizationId) {}

    @Schema(name = "CredentialView")
    public record CredentialView(String credential, String tokenType, long expiresInSeconds,
            String organizationId, String deviceId) {}

    private static DeviceView toView(AppDevice d) {
        return new DeviceView(d.getId(), d.getFingerprint(), d.getLabel(),
                d.getCreatedAt().toString(), d.getLastSeenAt().toString(),
                d.getRevokedAt() == null ? null : d.getRevokedAt().toString());
    }

    @PostMapping(value = "/devices", consumes = "application/json", produces = "application/json")
    @Operation(operationId = "registerDevice",
            summary = "Register the current user's AppDevice public key (P-256)")
    @Transactional
    public DeviceView register(@Valid @RequestBody RegisterDeviceRequest request,
            @AuthenticationPrincipal AppUserDetails principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        String fingerprint;
        try {
            fingerprint = EcKeyUtils.fingerprintOfXY(request.publicKeyX(), request.publicKeyY());
            // Validate the point parses as P-256.
            EcKeyUtils.publicFromXY(request.publicKeyX(), request.publicKeyY());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid public key");
        }
        var user = users.findById(principal.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        var existing = devices.findByFingerprint(fingerprint).orElse(null);
        if (existing != null) {
            if (!existing.getUser().getId().equals(principal.getId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "device already registered");
            }
            if (existing.isRevoked()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "device revoked");
            }
            existing.touch();
            devices.save(existing);
            return toView(existing);
        }
        AppDevice device = new AppDevice(user, request.publicKeyX(), request.publicKeyY(),
                fingerprint, request.label());
        devices.save(device);
        return toView(device);
    }

    @GetMapping(value = "/devices", produces = "application/json")
    @Operation(operationId = "listDevices", summary = "List my AppDevices")
    @Transactional(readOnly = true)
    public List<DeviceView> list(@AuthenticationPrincipal AppUserDetails principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return devices.findByUserId(principal.getId()).stream().map(DeviceController::toView)
                .toList();
    }

    @PostMapping(value = "/devices/{deviceId}/revoke", produces = "application/json")
    @Operation(operationId = "revokeDevice",
            summary = "Revoke my AppDevice; it receives no further credentials")
    @Transactional
    public DeviceView revoke(@PathVariable UUID deviceId,
            @AuthenticationPrincipal AppUserDetails principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        AppDevice device = devices.findById(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!device.getUser().getId().equals(principal.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        device.revoke();
        devices.save(device);
        return toView(device);
    }

    @PostMapping(value = "/devices/{deviceId}/credentials", consumes = "application/json",
            produces = "application/json")
    @Operation(operationId = "issueOfflineCredential",
            summary = "Issue a signed, time-limited, organization-bound offline credential "
                    + "for my AppDevice (blocked when membership/device is not ACTIVE)")
    @Transactional
    public CredentialView issue(@PathVariable UUID deviceId,
            @Valid @RequestBody IssueCredentialRequest request,
            @AuthenticationPrincipal AppUserDetails principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        UUID organizationId;
        try {
            organizationId = UUID.fromString(request.organizationId());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid organization id");
        }
        AppDevice device = devices.findById(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!device.getUser().getId().equals(principal.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (device.isRevoked()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "device revoked");
        }
        OrganizationMembership membership = tenants.requireActive(principal.getId(), organizationId);
        // PENDING/DISABLED memberships never reach here: requireActive throws 403.
        var org = organizations.findById(organizationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        device.touch();
        devices.save(device);
        String jwt = issuer.issueOfflineCredential(principal.getId(), organizationId,
                org.getSlug(), device.getId(), device.getPublicKeyX(), device.getPublicKeyY(),
                device.getFingerprint(), membership.getRole().name(), java.time.Instant.now());
        return new CredentialView(jwt, "Offline-BLE-ES256-JWT", issuer.getTtlSeconds(),
                organizationId.toString(), device.getId().toString());
    }
}
