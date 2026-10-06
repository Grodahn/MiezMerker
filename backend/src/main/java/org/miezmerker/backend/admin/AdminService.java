package org.miezmerker.backend.admin;

import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.UUID;
import org.miezmerker.backend.domain.MembershipRole;
import org.miezmerker.backend.domain.MembershipStatus;
import org.miezmerker.backend.domain.OrganizationMembership;
import org.miezmerker.backend.domain.OrganizationStatus;
import org.miezmerker.backend.domain.UserStatus;
import org.miezmerker.backend.repo.AppUserRepository;
import org.miezmerker.backend.repo.MembershipRepository;
import org.miezmerker.backend.security.AppUserDetails;
import org.miezmerker.backend.security.TenantService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Server-side admin context for #33.
 *
 * <p>Holds the active organization in the HTTP session (never trusts a
 * client-supplied organization id as authority) and centralises the
 * ADMIN-only gate: only users with at least one ACTIVE ADMIN membership
 * in an ACTIVE organization may use {@code /admin/**}.
 */
@Service
public class AdminService {
    public static final String SESSION_ORG_KEY = "ADMIN_ORG_ID";

    private final AppUserRepository users;
    private final MembershipRepository memberships;
    private final TenantService tenants;

    public AdminService(AppUserRepository users, MembershipRepository memberships,
            TenantService tenants) {
        this.users = users;
        this.memberships = memberships;
        this.tenants = tenants;
    }

    public record AdminOrg(UUID id, String slug, String displayName) {
        public UUID getId() { return id; }
        public String getSlug() { return slug; }
        public String getDisplayName() { return displayName; }
    }

    /** All ACTIVE ADMIN memberships of the user in ACTIVE organizations. */
    @Transactional(readOnly = true)
    public List<AdminOrg> adminOrgs(UUID userId) {
        return memberships.findByUserIdWithRefs(userId).stream()
                .filter(m -> m.getRole() == MembershipRole.ADMIN)
                .filter(m -> m.getStatus() == MembershipStatus.ACTIVE)
                .filter(m -> m.getOrganization().getStatus() == OrganizationStatus.ACTIVE)
                .filter(m -> m.getUser().getStatus() == UserStatus.ACTIVE)
                .map(m -> new AdminOrg(m.getOrganization().getId(),
                        m.getOrganization().getSlug(),
                        m.getOrganization().getDisplayName()))
                .toList();
    }

    @Transactional(readOnly = true)
    public boolean isAdmin(UUID userId) {
        return !adminOrgs(userId).isEmpty();
    }

    /** Active organization from the server-side session, validated on every use. */
    @Transactional(readOnly = true)
    public AdminOrg requireActiveOrg(AppUserDetails principal, HttpSession session) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "not authenticated");
        }
        UUID orgId = sessionOrgId(session);
        // Authority is the ACTIVE ADMIN membership, never the session value alone.
        OrganizationMembership membership = tenants.requireAdmin(principal.getId(), orgId);
        var org = membership.getOrganization();
        // Copy to a DTO inside the transaction: views must never touch lazy proxies
        // (open-in-view is disabled).
        return new AdminOrg(org.getId(), org.getSlug(), org.getDisplayName());
    }

    @Transactional(readOnly = true)
    public AdminOrg currentOrgOrNull(AppUserDetails principal, HttpSession session) {
        try {
            return requireActiveOrg(principal, session);
        } catch (ResponseStatusException e) {
            return null;
        }
    }

    /** Validated organization switch; rejects foreign or non-ADMIN organizations. */
    @Transactional(readOnly = true)
    public AdminOrg selectOrg(UUID userId, UUID organizationId, HttpSession session) {
        OrganizationMembership membership = tenants.requireAdmin(userId, organizationId);
        var org = membership.getOrganization();
        AdminOrg dto = new AdminOrg(org.getId(), org.getSlug(), org.getDisplayName());
        session.setAttribute(SESSION_ORG_KEY, dto.id());
        return dto;
    }

    private UUID sessionOrgId(HttpSession session) {
        Object raw = session.getAttribute(SESSION_ORG_KEY);
        if (raw instanceof UUID uuid) {
            return uuid;
        }
        if (raw instanceof String s) {
            try {
                return UUID.fromString(s);
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "no active organization");
            }
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "no active organization");
    }

    @Transactional(readOnly = true)
    public String displayLabelFor(UUID userId) {
        return users.findById(userId)
                .map(u -> u.getDisplayName() != null && !u.getDisplayName().isBlank()
                        ? u.getDisplayName() : u.getEmail())
                .orElse("?");
    }

    @Transactional(readOnly = true)
    public String emailFor(UUID userId) {
        return users.findById(userId).map(u -> u.getEmail()).orElse("?");
    }
}
