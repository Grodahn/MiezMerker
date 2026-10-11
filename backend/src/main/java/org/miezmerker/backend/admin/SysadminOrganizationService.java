package org.miezmerker.backend.admin;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.miezmerker.backend.domain.AppUser;
import org.miezmerker.backend.domain.MembershipRole;
import org.miezmerker.backend.domain.MembershipStatus;
import org.miezmerker.backend.domain.Organization;
import org.miezmerker.backend.domain.OrganizationMembership;
import org.miezmerker.backend.domain.OrganizationStatus;
import org.miezmerker.backend.domain.SysadminOrgAction;
import org.miezmerker.backend.domain.SysadminOrganizationAudit;
import org.miezmerker.backend.domain.UserStatus;
import org.miezmerker.backend.repo.AppUserRepository;
import org.miezmerker.backend.repo.MembershipRepository;
import org.miezmerker.backend.repo.OrganizationRepository;
import org.miezmerker.backend.repo.SharePolicyRepository;
import org.miezmerker.backend.repo.SysadminOrganizationAuditRepository;
import org.miezmerker.backend.security.AppUserDetails;
import org.miezmerker.backend.security.SystemAuthorizationService;
import org.miezmerker.backend.service.SharePolicyService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * SYSADMIN-only organization metadata and Orgaadmin-membership management
 * (#92, ADR 0016 decision 1).
 *
 * <p>Every operation requires an authenticated, ACTIVE global SYSADMIN
 * ({@link SystemAuthorizationService#requireSysadmin}), checked here in the
 * service layer in addition to the controller and the
 * {@code /admin/organizations/**} security filter gate. SYSADMIN authority is
 * strictly separate from tenant authority: these operations manage
 * organization metadata and membership records only and never grant access to
 * Cats, FeedingSites, Nodes, RawObservations or DerivedVisits, which keep
 * requiring an ACTIVE membership via {@code TenantService}.
 *
 * <p>Semantics reused from the existing domain:
 * <ul>
 *   <li>Technical identity ({@code id} and {@code slug}) is assigned once at
 *       creation and never changes; renaming touches only {@code displayName}
 *       and {@code publicContact} and never reassigns Nodes, deployments or
 *       observations.</li>
 *   <li>New organizations are hidden ({@code discoverable=false}) and start
 *       without sharing grants.</li>
 *   <li>Deactivation sets {@code DISABLED} and immediately invalidates all
 *       outgoing sharing grants and allowlist entries via
 *       {@link SharePolicyService}; reactivation restores nothing.</li>
 *   <li>Deletion requests use safe retention: the organization is deactivated
 *       and archived in place; no animal, observation, visit, device or
 *       membership rows are ever deleted. Physical erasure remains a
 *       documented operator procedure outside this surface.</li>
 *   <li>ADMIN assignment reuses the existing membership lifecycle
 *       ({@code ADMIN|MEMBER}, {@code PENDING|ACTIVE|DISABLED}), preserves
 *       unrelated memberships and rejects duplicates via the
 *       {@code (organization_id, user_id)} constraint.</li>
 * </ul>
 *
 * <p>Every mutation writes an append-only {@link SysadminOrganizationAudit}
 * row in the same transaction (actor, organization, user where applicable,
 * operation, timestamp, before/after state). Failed operations roll back
 * data and audit together. Audit rows contain only organization metadata and
 * membership transitions, never passwords, keys, tokens or animal data.
 *
 * <p>Intended recovery path: a SYSADMIN may grant itself ADMIN on an
 * organization and then use the regular tenant surface. That grant is an
 * audited {@code ADMIN_GRANTED} event like any other; there is no silent
 * self-escalation outside the ledger.
 */
@Service
public class SysadminOrganizationService {
    private final SystemAuthorizationService system;
    private final OrganizationRepository organizations;
    private final MembershipRepository memberships;
    private final AppUserRepository users;
    private final SharePolicyRepository sharePolicies;
    private final SharePolicyService shares;
    private final SysadminOrganizationAuditRepository audit;

    public SysadminOrganizationService(SystemAuthorizationService system,
            OrganizationRepository organizations, MembershipRepository memberships,
            AppUserRepository users, SharePolicyRepository sharePolicies,
            SharePolicyService shares, SysadminOrganizationAuditRepository audit) {
        this.system = system;
        this.organizations = organizations;
        this.memberships = memberships;
        this.users = users;
        this.sharePolicies = sharePolicies;
        this.shares = shares;
        this.audit = audit;
    }

    public record OrganizationView(UUID id, String slug, String displayName, String publicContact,
            OrganizationStatus status, boolean discoverable, Instant createdAt) {}

    public record MemberView(UUID membershipId, UUID userId, String email, String displayName,
            MembershipRole role, MembershipStatus status) {}

    public record AuditView(UUID id, String actorEmail, String targetEmail, SysadminOrgAction action,
            String beforeState, String afterState, Instant createdAt) {}

    static OrganizationView toView(Organization org) {
        return new OrganizationView(org.getId(), org.getSlug(), org.getDisplayName(),
                org.getPublicContact(), org.getStatus(), org.isDiscoverable(), org.getCreatedAt());
    }

    private static MemberView toMemberView(OrganizationMembership membership) {
        return new MemberView(membership.getId(), membership.getUser().getId(),
                membership.getUser().getEmail(), membership.getUser().getDisplayName(),
                membership.getRole(), membership.getStatus());
    }

    private static AuditView toAuditView(SysadminOrganizationAudit entry) {
        return new AuditView(entry.getId(),
                entry.getActor() == null ? null : entry.getActor().getEmail(),
                entry.getTarget() == null ? null : entry.getTarget().getEmail(),
                entry.getAction(), entry.getBeforeState(), entry.getAfterState(),
                entry.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public List<OrganizationView> listOrganizations(AppUserDetails principal) {
        system.requireSysadmin(principal);
        return organizations.findAll().stream()
                .sorted(Comparator.comparing(Organization::getDisplayName,
                        String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(Organization::getSlug)
                        .thenComparing(Organization::getId))
                .map(SysadminOrganizationService::toView)
                .toList();
    }

    @Transactional(readOnly = true)
    public OrganizationView getOrganization(AppUserDetails principal, UUID organizationId) {
        system.requireSysadmin(principal);
        return toView(requireOrganization(organizationId));
    }

    /**
     * Orgaadmin memberships of one organization. Only ADMIN rows are exposed:
     * assignment works by exact e-mail lookup and revocation targets ADMINs,
     * so MEMBER/PENDING/DISABLED contact details are not listed here (data
     * minimization; a compromised SYSADMIN session exposes less).
     */
    @Transactional(readOnly = true)
    public List<MemberView> listAdmins(AppUserDetails principal, UUID organizationId) {
        system.requireSysadmin(principal);
        // Unknown ids yield the same 404 as known-but-empty ones for the detail
        // loader; membership rows themselves are organization metadata.
        requireOrganization(organizationId);
        return memberships.findByOrganizationIdWithUser(organizationId).stream()
                .filter(m -> m.getRole() == MembershipRole.ADMIN)
                .map(SysadminOrganizationService::toMemberView)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AuditView> recentAudit(AppUserDetails principal, UUID organizationId) {
        system.requireSysadmin(principal);
        requireOrganization(organizationId);
        // Bounded in the query: forensic history stays queryable per
        // organization without loading an unbounded ledger into memory.
        return audit.findTop50ByOrganizationIdOrderByCreatedAtDesc(organizationId).stream()
                .map(SysadminOrganizationService::toAuditView)
                .toList();
    }

    @Transactional(readOnly = true)
    public int outgoingPolicyCount(AppUserDetails principal, UUID organizationId) {
        system.requireSysadmin(principal);
        requireOrganization(organizationId);
        return sharePolicies.countByOrganizationId(organizationId);
    }

    @Transactional
    public OrganizationView createOrganization(AppUserDetails principal, String slug,
            String displayName, String publicContact) {
        system.requireSysadmin(principal);
        String cleanSlug = normalizeSlug(slug);
        String cleanName = requireDisplayName(displayName);
        String cleanContact = normalizeContact(publicContact);
        if (organizations.findBySlug(cleanSlug).isPresent()) {
            throw error(HttpStatus.CONFLICT, "Technischer Name (Slug) ist bereits vergeben.");
        }
        AppUser actor = requireActor(principal);
        Organization org = new Organization(cleanSlug, cleanName, cleanContact);
        // Defaults from the domain model: ACTIVE, discoverable=false (hidden),
        // no sharing grants. The stable UUID + slug never change afterwards.
        try {
            organizations.save(org);
            organizations.flush();
        } catch (DataIntegrityViolationException e) {
            throw error(HttpStatus.CONFLICT, "Technischer Name (Slug) ist bereits vergeben.");
        }
        audit.save(new SysadminOrganizationAudit(actor, org, null, SysadminOrgAction.CREATE, "",
                "slug=" + cleanSlug + ", name=" + abbreviated(cleanName)
                        + ", status=ACTIVE, discoverable=false"));
        return toView(org);
    }

    @Transactional
    public OrganizationView renameOrganization(AppUserDetails principal, UUID organizationId,
            String displayName, String publicContact) {
        system.requireSysadmin(principal);
        Organization org = lockOrganization(organizationId);
        String cleanName = requireDisplayName(displayName);
        // Blank contact clears to NULL; the slug and id stay immutable so Node
        // ownership, deployment history and identifiers are unaffected.
        String cleanContact = publicContact == null ? org.getPublicContact()
                : normalizeContact(publicContact);
        String before = describe(org);
        boolean changed = !cleanName.equals(org.getDisplayName())
                || !equalsNullable(cleanContact, org.getPublicContact());
        if (!changed) {
            return toView(org);
        }
        org.setDisplayName(cleanName);
        org.setPublicContact(cleanContact);
        organizations.save(org);
        audit.save(new SysadminOrganizationAudit(requireActor(principal), org, null,
                SysadminOrgAction.RENAME, before, describe(org)));
        return toView(org);
    }

    @Transactional
    public OrganizationView deactivateOrganization(AppUserDetails principal, UUID organizationId,
            boolean confirmed) {
        system.requireSysadmin(principal);
        if (!confirmed) {
            throw error(HttpStatus.BAD_REQUEST,
                    "Bitte die Deaktivierung ausdrücklich bestätigen.");
        }
        Organization org = lockOrganization(organizationId);
        if (org.getStatus() == OrganizationStatus.DISABLED) {
            return toView(org);
        }
        String before = describe(org);
        org.setStatus(OrganizationStatus.DISABLED);
        organizations.save(org);
        // Immediate enforcement: existing authorization checks already reject
        // DISABLED organizations, and stored grants must not linger for a
        // later reactivation.
        AppUser actor = requireActor(principal);
        shares.invalidateOutgoingGrants(org.getId(), actor, "Organisation deaktiviert");
        shares.removeFromAllowlists(org.getId(), actor, "Organisation deaktiviert");
        audit.save(new SysadminOrganizationAudit(actor, org, null, SysadminOrgAction.DEACTIVATE,
                before, describe(org) + ", Freigaben widerrufen"));
        return toView(org);
    }

    @Transactional
    public OrganizationView reactivateOrganization(AppUserDetails principal, UUID organizationId,
            boolean confirmed) {
        system.requireSysadmin(principal);
        if (!confirmed) {
            throw error(HttpStatus.BAD_REQUEST,
                    "Bitte die Reaktivierung ausdrücklich bestätigen.");
        }
        Organization org = lockOrganization(organizationId);
        if (org.getStatus() == OrganizationStatus.ACTIVE) {
            return toView(org);
        }
        String before = describe(org);
        org.setStatus(OrganizationStatus.ACTIVE);
        organizations.save(org);
        // Grants invalidated by deactivation stay deleted: reactivation
        // performs no policy writes, so no silent restoration is possible.
        audit.save(new SysadminOrganizationAudit(requireActor(principal), org, null,
                SysadminOrgAction.REACTIVATE, before,
                describe(org) + ", keine Freigaben wiederhergestellt"));
        return toView(org);
    }

    /**
     * Safe retention for deletion requests: the organization is deactivated
     * (with grant invalidation) and archived in place. No Cat, FeedingSite,
     * Node, RawObservation, DerivedVisit or membership rows are deleted.
     * Complete physical erasure with retention/export handling remains future
     * operator work and is documented on the confirmation screen instead of
     * being approximated with destructive shortcuts.
     */
    @Transactional
    public OrganizationView requestDeletion(AppUserDetails principal, UUID organizationId,
            String slugConfirmation, boolean confirmed) {
        system.requireSysadmin(principal);
        Organization org = lockOrganization(organizationId);
        if (!confirmed || slugConfirmation == null
                || !slugConfirmation.trim().equals(org.getSlug())) {
            throw error(HttpStatus.BAD_REQUEST,
                    "Bitte den Vorgang ausdrücklich bestätigen und den technischen Namen exakt eingeben.");
        }
        String before = describe(org);
        AppUser actor = requireActor(principal);
        if (org.getStatus() != OrganizationStatus.DISABLED) {
            org.setStatus(OrganizationStatus.DISABLED);
            organizations.save(org);
            shares.invalidateOutgoingGrants(org.getId(), actor, "Loeschanfrage: Organisation gesperrt");
            shares.removeFromAllowlists(org.getId(), actor, "Loeschanfrage: Organisation gesperrt");
        }
        audit.save(new SysadminOrganizationAudit(actor, org, null,
                SysadminOrgAction.DELETE_REQUESTED, before,
                describe(org) + ", aufbewahrt: Fach-, Beobachtungs-, Besuchs-, Geraete- "
                        + "und Mitgliedschaftsdaten bleiben erhalten"));
        return toView(org);
    }

    @Transactional
    public MemberView assignAdmin(AppUserDetails principal, UUID organizationId, String email) {
        system.requireSysadmin(principal);
        String cleanEmail = AppUser.normalizeEmail(email);
        if (cleanEmail == null || cleanEmail.isBlank()) {
            throw error(HttpStatus.BAD_REQUEST, "Bitte eine E-Mail-Adresse angeben.");
        }
        Organization org = lockOrganization(organizationId);
        AppUser target = users.findByEmail(cleanEmail)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND,
                        "Kein Konto mit dieser E-Mail-Adresse gefunden."));
        if (target.getStatus() != UserStatus.ACTIVE) {
            throw error(HttpStatus.CONFLICT,
                    "Das Konto ist deaktiviert. Keine Orgaadmin-Vergabe an deaktivierte Konten.");
        }
        AppUser actor = requireActor(principal);
        return memberships.findByOrganizationIdAndUserId(org.getId(), target.getId())
                .map(existing -> promoteExisting(actor, org, existing))
                .orElseGet(() -> {
                    OrganizationMembership created = new OrganizationMembership(org, target,
                            MembershipRole.ADMIN, MembershipStatus.ACTIVE);
                    try {
                        memberships.save(created);
                        memberships.flush();
                    } catch (DataIntegrityViolationException e) {
                        throw error(HttpStatus.CONFLICT,
                                "Für dieses Konto besteht bereits eine Mitgliedschaft.");
                    }
                    audit.save(new SysadminOrganizationAudit(actor, org, target,
                            SysadminOrgAction.ADMIN_GRANTED, "keine Mitgliedschaft",
                            "role=ADMIN, status=ACTIVE"));
                    return toMemberView(created);
                });
    }

    private MemberView promoteExisting(AppUser actor, Organization org,
            OrganizationMembership existing) {
        // Only this organization's membership is touched; other memberships
        // of the same account are never modified. PENDING/DISABLED
        // memberships are never silently activated here: activation stays in
        // the tenant admin workflow, and the conflict message says so.
        if (existing.getRole() == MembershipRole.ADMIN
                && existing.getStatus() == MembershipStatus.ACTIVE) {
            throw error(HttpStatus.CONFLICT, "Dieses Konto ist bereits Orgaadmin.");
        }
        if (existing.getStatus() != MembershipStatus.ACTIVE) {
            throw error(HttpStatus.CONFLICT,
                    "Die Mitgliedschaft ist " + existing.getStatus()
                            + " und wird nicht automatisch aktiviert. Bitte zuerst den "
                            + "Mitgliedschaftsstatus in der Organisationsverwaltung klären.");
        }
        String before = "role=" + existing.getRole() + ", status=" + existing.getStatus();
        existing.setRole(MembershipRole.ADMIN);
        memberships.save(existing);
        audit.save(new SysadminOrganizationAudit(actor, org, existing.getUser(),
                SysadminOrgAction.ADMIN_GRANTED, before,
                "role=" + existing.getRole() + ", status=" + existing.getStatus()));
        return toMemberView(existing);
    }

    @Transactional
    public MemberView revokeAdmin(AppUserDetails principal, UUID organizationId, UUID userId) {
        system.requireSysadmin(principal);
        Organization org = lockOrganization(organizationId);
        OrganizationMembership existing = memberships
                .findByOrganizationIdAndUserId(org.getId(), userId)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Keine Mitgliedschaft gefunden."));
        if (existing.getRole() != MembershipRole.ADMIN) {
            throw error(HttpStatus.CONFLICT, "Dieses Konto hat keinen Orgaadmin-Status.");
        }
        // Demotion preserves the membership itself: the account keeps MEMBER
        // access instead of being locked out. No last-ADMIN guard (existing
        // #16 semantics, mirrored from MemberService).
        String before = "role=" + existing.getRole() + ", status=" + existing.getStatus();
        existing.setRole(MembershipRole.MEMBER);
        memberships.save(existing);
        audit.save(new SysadminOrganizationAudit(requireActor(principal), org, existing.getUser(),
                SysadminOrgAction.ADMIN_REVOKED, before,
                "role=" + existing.getRole() + ", status=" + existing.getStatus()));
        return toMemberView(existing);
    }

    private Organization requireOrganization(UUID organizationId) {
        if (organizationId == null) {
            throw error(HttpStatus.NOT_FOUND, "Organisation nicht gefunden.");
        }
        return organizations.findById(organizationId)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Organisation nicht gefunden."));
    }

    private Organization lockOrganization(UUID organizationId) {
        if (organizationId == null) {
            throw error(HttpStatus.NOT_FOUND, "Organisation nicht gefunden.");
        }
        return organizations.lockById(organizationId)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Organisation nicht gefunden."));
    }

    private AppUser requireActor(AppUserDetails principal) {
        return users.findById(principal.getId())
                .orElseThrow(() -> error(HttpStatus.FORBIDDEN, "Systemverwaltung erforderlich."));
    }

    private static String normalizeSlug(String slug) {
        if (slug == null) {
            throw error(HttpStatus.BAD_REQUEST, "Technischer Name (Slug) fehlt.");
        }
        String clean = slug.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-");
        if (clean.isBlank()) {
            throw error(HttpStatus.BAD_REQUEST, "Technischer Name (Slug) ist ungültig.");
        }
        if (clean.length() > 64) {
            throw error(HttpStatus.BAD_REQUEST,
                    "Technischer Name (Slug) darf höchstens 64 Zeichen haben.");
        }
        return clean;
    }

    private static String requireDisplayName(String displayName) {
        if (displayName == null || displayName.trim().isEmpty()) {
            throw error(HttpStatus.BAD_REQUEST, "Bitte einen Organisationsnamen angeben.");
        }
        String clean = displayName.trim();
        if (clean.length() > 255) {
            throw error(HttpStatus.BAD_REQUEST,
                    "Organisationsname darf höchstens 255 Zeichen haben.");
        }
        return clean;
    }

    private static String normalizeContact(String publicContact) {
        if (publicContact == null || publicContact.isBlank()) {
            return null;
        }
        String clean = publicContact.trim();
        if (clean.length() > 500) {
            throw error(HttpStatus.BAD_REQUEST,
                    "Öffentlicher Kontakt darf höchstens 500 Zeichen haben.");
        }
        return clean;
    }

    private static String describe(Organization org) {
        return "slug=" + org.getSlug() + ", name=" + abbreviated(org.getDisplayName())
                + ", status=" + org.getStatus()
                + ", discoverable=" + org.isDiscoverable();
    }

    private static String abbreviated(String text) {
        String clean = text == null ? "" : text;
        String snippet = "'" + (clean.length() > 80 ? clean.substring(0, 80) + "…" : clean) + "'";
        return snippet.length() > 100 ? snippet.substring(0, 100) : snippet;
    }

    private static boolean equalsNullable(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private static ResponseStatusException error(HttpStatus status, String message) {
        return new ResponseStatusException(status, message);
    }
}
