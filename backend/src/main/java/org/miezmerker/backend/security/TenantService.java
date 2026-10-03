package org.miezmerker.backend.security;

import java.util.UUID;
import org.miezmerker.backend.domain.MembershipRole;
import org.miezmerker.backend.domain.MembershipStatus;
import org.miezmerker.backend.domain.OrganizationMembership;
import org.miezmerker.backend.repo.MembershipRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Server-side tenant gate (#16). Every organization-scoped operation must go through here.
 * A client-supplied organization id is never authority on its own; the authenticated user's
 * ACTIVE membership is the authority.
 */
@Service
public class TenantService {
    private final MembershipRepository memberships;

    public TenantService(MembershipRepository memberships) {
        this.memberships = memberships;
    }

    public OrganizationMembership requireActive(UUID userId, UUID organizationId) {
        OrganizationMembership membership = memberships
                .findByOrganizationIdAndUserId(organizationId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "no membership"));
        if (membership.getStatus() != MembershipStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "membership not active");
        }
        if (membership.getOrganization().getStatus() != org.miezmerker.backend.domain.OrganizationStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "organization disabled");
        }
        if (membership.getUser().getStatus() != org.miezmerker.backend.domain.UserStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "account disabled");
        }
        return membership;
    }

    public OrganizationMembership requireAdmin(UUID userId, UUID organizationId) {
        OrganizationMembership membership = requireActive(userId, organizationId);
        if (membership.getRole() != MembershipRole.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin required");
        }
        return membership;
    }

    public static UUID currentUserId(AppUserDetails principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "not authenticated");
        }
        return principal.getId();
    }
}
