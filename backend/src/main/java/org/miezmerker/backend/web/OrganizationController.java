package org.miezmerker.backend.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.miezmerker.backend.domain.MembershipRole;
import org.miezmerker.backend.domain.MembershipStatus;
import org.miezmerker.backend.domain.Organization;
import org.miezmerker.backend.domain.OrganizationMembership;
import org.miezmerker.backend.repo.MembershipRepository;
import org.miezmerker.backend.repo.OrganizationRepository;
import org.miezmerker.backend.security.AppUserDetails;
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

@RestController
@RequestMapping("/api/v1")
public class OrganizationController {
    private final OrganizationRepository organizations;
    private final MembershipRepository memberships;
    private final org.miezmerker.backend.repo.AppUserRepository users;

    public OrganizationController(OrganizationRepository organizations,
            MembershipRepository memberships,
            org.miezmerker.backend.repo.AppUserRepository users) {
        this.organizations = organizations;
        this.memberships = memberships;
        this.users = users;
    }

    @Schema(name = "OrganizationView")
    public record OrganizationView(UUID id, String slug, String displayName,
            String publicContact, String status) {}

    @Schema(name = "CreateOrganizationRequest")
    public record CreateOrganizationRequest(
            @NotBlank @Size(max = 64) String slug,
            @NotBlank @Size(max = 255) String displayName,
            @Size(max = 500) String publicContact) {}

    private static OrganizationView toView(Organization org) {
        return new OrganizationView(org.getId(), org.getSlug(), org.getDisplayName(),
                org.getPublicContact(), org.getStatus().name());
    }

    @GetMapping(value = "/organizations", produces = "application/json")
    @Operation(operationId = "listMyOrganizations",
            summary = "Organizations of the authenticated user (via ACTIVE or any membership)")
    @Transactional(readOnly = true)
    public List<OrganizationView> listMine(@AuthenticationPrincipal AppUserDetails principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return memberships.findByUserIdWithRefs(principal.getId()).stream()
                .map(m -> toView(m.getOrganization()))
                .toList();
    }

    @PostMapping(value = "/organizations", consumes = "application/json",
            produces = "application/json")
    @Operation(operationId = "createOrganization",
            summary = "Create an organization; caller becomes ACTIVE ADMIN (test-phase provisioning)")
    @Transactional
    public OrganizationView create(@Valid @RequestBody CreateOrganizationRequest request,
            @AuthenticationPrincipal AppUserDetails principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        String slug = request.slug().trim().toLowerCase().replaceAll("[^a-z0-9-]", "-");
        if (slug.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid slug");
        }
        if (organizations.findBySlug(slug).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "slug taken");
        }
        Organization org = organizations.save(new Organization(slug, request.displayName().trim(),
                request.publicContact() == null || request.publicContact().isBlank()
                        ? null : request.publicContact().trim()));
        var user = users.findById(principal.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        OrganizationMembership membership = new OrganizationMembership(org,
                user, MembershipRole.ADMIN, MembershipStatus.ACTIVE);
        memberships.save(membership);
        return toView(org);
    }

    @GetMapping(value = "/organizations/{organizationId}", produces = "application/json")
    @Operation(operationId = "getOrganization",
            summary = "Organization details; requires ACTIVE membership (tenant isolation)")
    @Transactional(readOnly = true)
    public OrganizationView get(@PathVariable UUID organizationId,
            @AuthenticationPrincipal AppUserDetails principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        OrganizationMembership membership = memberships
                .findByOrganizationIdAndUserId(organizationId, principal.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
        if (membership.getStatus() != MembershipStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "membership not active");
        }
        return toView(membership.getOrganization());
    }
}
