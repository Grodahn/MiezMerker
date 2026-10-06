package org.miezmerker.backend.service;

import java.util.List;
import java.util.UUID;
import org.miezmerker.backend.domain.AppUser;
import org.miezmerker.backend.domain.MembershipRole;
import org.miezmerker.backend.domain.MembershipStatus;
import org.miezmerker.backend.domain.OrganizationMembership;
import org.miezmerker.backend.repo.AppUserRepository;
import org.miezmerker.backend.repo.MembershipRepository;
import org.miezmerker.backend.security.TenantService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Shared member-maintenance operations (#16 + #32) for the REST API and the
 * server-rendered Admin backend (#34).
 *
 * <p>All methods enforce the tenant gate through {@link TenantService}: the
 * caller's ACTIVE ADMIN membership is the authority, never a client-supplied id.
 * Membership ids are always resolved within the active organization boundary;
 * foreign or absent ids both yield 404 without leaking existence or details.
 *
 * <p>Role/status semantics reuse #16: role is {@code ADMIN|MEMBER}, status is
 * {@code PENDING|ACTIVE|DISABLED} (PENDING is a status, never a role). There is
 * intentionally no last-ADMIN guard in the current domain: demoting or disabling
 * the final ADMIN is allowed and simply leaves the organization without an admin
 * until another ADMIN is provisioned. This mirrors the REST behaviour.
 *
 * <p>{@code displayName} belongs to {@link AppUser} globally (#32). Updating it
 * here changes the user's identity across all organizations; there are no
 * organization-specific aliases. Blank input clears to {@code NULL}; absent
 * ({@code null}) leaves the name unchanged. Length is validated after Unicode
 * trimming (max 255).
 */
@Service
public class MemberService {
    private final MembershipRepository memberships;
    private final AppUserRepository users;
    private final TenantService tenants;

    public MemberService(MembershipRepository memberships, AppUserRepository users,
            TenantService tenants) {
        this.memberships = memberships;
        this.users = users;
        this.tenants = tenants;
    }

    @Transactional(readOnly = true)
    public List<OrganizationMembership> listMembers(UUID actorId, UUID organizationId) {
        tenants.requireAdmin(actorId, organizationId);
        return memberships.findByOrganizationIdWithUser(organizationId);
    }

    /**
     * Resolves a membership strictly inside the active organization.
     * Foreign or absent ids both yield 404 (no enumeration, no leak).
     */
    @Transactional(readOnly = true)
    public OrganizationMembership requireMemberInOrganization(UUID actorId, UUID organizationId,
            UUID membershipId) {
        tenants.requireAdmin(actorId, organizationId);
        OrganizationMembership membership = memberships.findById(membershipId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "not found"));
        if (!membership.getOrganization().getId().equals(organizationId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "not found");
        }
        return membership;
    }

    /**
     * Updates role/status and, when {@code displayName != null}, the global
     * {@link AppUser#displayName}. A blank displayName clears to {@code NULL}.
     */
    @Transactional
    public OrganizationMembership updateMember(UUID actorId, UUID organizationId,
            UUID membershipId, MembershipRole role, MembershipStatus status,
            String displayName) {
        OrganizationMembership membership =
                requireMemberInOrganization(actorId, organizationId, membershipId);
        if (role != null) {
            membership.setRole(role);
        }
        // HTML role/status forms submit the current status as well. Only actual
        // transitions should replace the activation/disable audit timestamps.
        if (status != null && status != membership.getStatus()) {
            if (status == MembershipStatus.ACTIVE) {
                membership.activate();
            } else if (status == MembershipStatus.DISABLED) {
                membership.disable();
            } else {
                membership.setStatusDirect(status);
            }
        }
        if (displayName != null) {
            // Global identity: visible in every organization of this user (#32).
            // normalizeDisplayName trims Unicode whitespace; blank -> null (clear).
            String normalized = AppUser.normalizeDisplayName(displayName);
            if (normalized != null && normalized.length() > AppUser.MAX_DISPLAY_NAME_LENGTH) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "displayName must not exceed " + AppUser.MAX_DISPLAY_NAME_LENGTH
                                + " characters");
            }
            membership.getUser().setDisplayName(normalized);
            users.save(membership.getUser());
        }
        return memberships.save(membership);
    }
}
