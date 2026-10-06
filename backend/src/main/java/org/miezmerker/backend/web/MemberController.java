package org.miezmerker.backend.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.miezmerker.backend.domain.AppUser;
import org.miezmerker.backend.domain.MembershipRole;
import org.miezmerker.backend.domain.MembershipStatus;
import org.miezmerker.backend.domain.Organization;
import org.miezmerker.backend.domain.OrganizationMembership;
import org.miezmerker.backend.repo.AppUserRepository;
import org.miezmerker.backend.repo.MembershipRepository;
import org.miezmerker.backend.repo.OrganizationRepository;
import org.miezmerker.backend.security.AppUserDetails;
import org.miezmerker.backend.security.TenantService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Test-phase member administration (#16). No public self-registration and no invite mails yet
 * (#19 deferred): an ACTIVE ADMIN creates/activates/disables memberships server-side.
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/members")
public class MemberController {
    private final OrganizationRepository organizations;
    private final AppUserRepository users;
    private final MembershipRepository memberships;
    private final TenantService tenants;
    private final PasswordEncoder passwords;

    public MemberController(OrganizationRepository organizations, AppUserRepository users,
            MembershipRepository memberships, TenantService tenants, PasswordEncoder passwords) {
        this.organizations = organizations;
        this.users = users;
        this.memberships = memberships;
        this.tenants = tenants;
        this.passwords = passwords;
    }

    @Schema(name = "MemberView")
    public record MemberView(UUID membershipId, UUID userId, String email,
            @Schema(type = "string", nullable = true) String displayName,
            String role, String status) {}

    @Schema(name = "CreateMemberRequest")
    public record CreateMemberRequest(
            @Email @NotBlank @Size(max = 320) String email,
            @Schema(minLength = 12, maxLength = 72,
                    description = "Initial password: at least 12 characters, at most 72 UTF-8 bytes") String password,
            @NotNull MembershipRole role,
            @Size(max = 255, message = "displayName must not exceed 255 characters")
            @Schema(type = "string", nullable = true,
                    description = "Optional human-readable display name (global AppUser.displayName, "
                    + "trimmed; blank means no name; shown in every organization of this user)") String displayName) {
        // password is required when the user does not exist yet; optional when adding a
        // membership for an existing user. Validation of presence happens in the handler.
        public CreateMemberRequest {
            displayName = AppUser.normalizeDisplayName(displayName);
        }
    }

    @Schema(name = "UpdateMemberRequest")
    public record UpdateMemberRequest(MembershipRole role, MembershipStatus status,
            @Size(max = 255, message = "displayName must not exceed 255 characters")
            @Schema(type = "string", nullable = true,
                    description = "Optional display name update for the underlying global AppUser. "
                    + "Absent/null leaves the name unchanged; blank clears it to null; "
                    + "a value is trimmed and stored globally (visible in all organizations).") String displayName) {
        public UpdateMemberRequest {
            // Preserve the difference between null (no update) and blank (clear).
            if (displayName != null) {
                String normalized = AppUser.normalizeDisplayName(displayName);
                displayName = normalized == null ? "" : normalized;
            }
        }
    }

    private static MemberView toView(OrganizationMembership m) {
        return new MemberView(m.getId(), m.getUser().getId(), m.getUser().getEmail(),
                m.getUser().getDisplayName(), m.getRole().name(), m.getStatus().name());
    }

    @GetMapping(produces = "application/json")
    @Operation(operationId = "listMembers",
            summary = "ADMIN lists members of their own organization")
    @Transactional(readOnly = true)
    public List<MemberView> list(@PathVariable UUID organizationId,
            @AuthenticationPrincipal AppUserDetails principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        tenants.requireAdmin(principal.getId(), organizationId);
        return memberships.findByOrganizationIdWithUser(organizationId).stream()
                .map(MemberController::toView)
                .toList();
    }

    @PostMapping(consumes = "application/json", produces = "application/json")
    @Operation(operationId = "createMember",
            summary = "ADMIN creates a user and/or ACTIVE membership (test-phase bootstrap, no mail)")
    @Transactional
    public MemberView create(@PathVariable UUID organizationId,
            @Valid @RequestBody CreateMemberRequest request,
            @AuthenticationPrincipal AppUserDetails principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        tenants.requireAdmin(principal.getId(), organizationId);
        Organization org = organizations.findById(organizationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        // Do not use field validation for secrets: validation errors log rejected values.
        if (request.password() != null && (request.password().length() < 12
                || request.password().getBytes(StandardCharsets.UTF_8).length > 72)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "password must have at least 12 characters and at most 72 UTF-8 bytes");
        }
        String email = AppUser.normalizeEmail(request.email());
        String normalizedName = AppUser.normalizeDisplayName(request.displayName());
        if (normalizedName != null && normalizedName.length() > AppUser.MAX_DISPLAY_NAME_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "displayName must not exceed " + AppUser.MAX_DISPLAY_NAME_LENGTH + " characters");
        }
        AppUser user = users.findByEmail(email).orElse(null);
        if (user == null) {
            if (request.password() == null || request.password().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "password required for new user");
            }
            user = users.save(new AppUser(email, passwords.encode(request.password()), normalizedName));
        } else if (normalizedName != null) {
            // Existing user gains a membership in another organization: an explicitly
            // supplied name updates the global AppUser.displayName (visible everywhere).
            // Absent/blank in this path never clears an existing name; use PATCH to clear.
            user.setDisplayName(normalizedName);
            user = users.save(user);
        }
        if (memberships.findByOrganizationIdAndUserId(organizationId, user.getId()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "already a member");
        }
        OrganizationMembership membership =
                new OrganizationMembership(org, user, request.role(), MembershipStatus.ACTIVE);
        memberships.save(membership);
        return toView(membership);
    }

    @PatchMapping(value = "/{membershipId}", consumes = "application/json",
            produces = "application/json")
    @Operation(operationId = "updateMember",
            summary = "ADMIN changes role, activates/disables a membership, or maintains the "
                    + "global display name (blank clears; global across all organizations)")
    @Transactional
    public MemberView update(@PathVariable UUID organizationId,
            @PathVariable UUID membershipId,
            @Valid @RequestBody UpdateMemberRequest request,
            @AuthenticationPrincipal AppUserDetails principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        tenants.requireAdmin(principal.getId(), organizationId);
        OrganizationMembership membership = memberships.findById(membershipId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!membership.getOrganization().getId().equals(organizationId)) {
            // Never leak or mutate another tenant's membership through this path.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (request.role() != null) {
            membership.setRole(request.role());
        }
        if (request.status() != null) {
            if (request.status() == MembershipStatus.ACTIVE) {
                membership.activate();
            } else if (request.status() == MembershipStatus.DISABLED) {
                membership.disable();
            } else {
                membership.setStatusDirect(request.status());
            }
        }
        if (request.displayName() != null) {
            // Resolved via the org-scoped membership, never via an arbitrary user id:
            // only the member of this organization can be renamed, and the change is
            // global (AppUser.displayName shows in every organization of that user).
            String patchName = AppUser.normalizeDisplayName(request.displayName());
            if (patchName != null && patchName.length() > AppUser.MAX_DISPLAY_NAME_LENGTH) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "displayName must not exceed " + AppUser.MAX_DISPLAY_NAME_LENGTH + " characters");
            }
            // Blank input (normalized to null) intentionally clears the name.
            membership.getUser().setDisplayName(patchName);
            users.save(membership.getUser());
        }
        memberships.save(membership);
        return toView(membership);
    }
}
