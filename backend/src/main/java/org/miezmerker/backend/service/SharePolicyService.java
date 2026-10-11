package org.miezmerker.backend.service;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.miezmerker.backend.domain.AppUser;
import org.miezmerker.backend.domain.Organization;
import org.miezmerker.backend.domain.OrganizationShareAudit;
import org.miezmerker.backend.domain.OrganizationSharePolicy;
import org.miezmerker.backend.domain.OrganizationShareRecipient;
import org.miezmerker.backend.domain.OrganizationStatus;
import org.miezmerker.backend.domain.ShareAction;
import org.miezmerker.backend.domain.ShareAudience;
import org.miezmerker.backend.domain.ShareScope;
import org.miezmerker.backend.repo.AppUserRepository;
import org.miezmerker.backend.repo.OrganizationRepository;
import org.miezmerker.backend.repo.ShareAuditRepository;
import org.miezmerker.backend.repo.SharePolicyRepository;
import org.miezmerker.backend.repo.ShareRecipientRepository;
import org.miezmerker.backend.security.TenantService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Default-deny cross-organization sharing policies (ADR 0016, issue #96).
 *
 * <p>Every organization is hidden and private by default. Discoverability and
 * data-sharing permissions are separate concepts: a discoverable organization
 * only appears in the authenticated directory and may be selected as a
 * sharing recipient; it never authorizes reading cats, visits, observations
 * or feeding sites on its own.
 *
 * <p>Grants are outgoing and read-only, managed exclusively by an ACTIVE ADMIN
 * of the data-owning organization. There is no reciprocity (A→B does not
 * imply B→A) and no transitivity (A→B→C does not imply A→C). Scope
 * prerequisites are enforced at resolution time: VISITS requires CARE,
 * SITE_LABEL requires CARE + VISITS, PHOTO requires CARE.
 *
 * <p>Hiding the owner invalidates all outgoing grants immediately; hiding
 * a recipient removes it from every allowlist. The public invalidation
 * helpers below give the SYSADMIN organization management surface (#92)
 * the same semantics when an organization is disabled. Invalidated grants
 * never silently reactivate: the policy rows are deleted, so re-sharing
 * requires a fresh affirmative grant. Every mutation is recorded in the
 * append-only audit ledger.
 */
@Service
public class SharePolicyService {
    private final OrganizationRepository organizations;
    private final AppUserRepository users;
    private final SharePolicyRepository policies;
    private final ShareRecipientRepository recipients;
    private final ShareAuditRepository audit;
    private final TenantService tenants;
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    /** Serialize settings writes with API visibility/policy changes. Refresh
     * previously read entities after waiting for a concurrent revocation. */
    @Transactional
    public void lockForSettings(UUID actorId, UUID ownerOrgId) {
        tenants.requireAdmin(actorId, ownerOrgId);
        Organization owner = organizations.lockById(ownerOrgId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "no membership"));
        entityManager.refresh(owner);
    }

    public SharePolicyService(OrganizationRepository organizations, AppUserRepository users,
            SharePolicyRepository policies, ShareRecipientRepository recipients,
            ShareAuditRepository audit, TenantService tenants) {
        this.organizations = organizations;
        this.users = users;
        this.policies = policies;
        this.recipients = recipients;
        this.audit = audit;
        this.tenants = tenants;
    }

    @io.swagger.v3.oas.annotations.media.Schema(name = "RecipientView")
    public record RecipientView(UUID id, String slug, String displayName) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "PolicyView")
    public record PolicyView(ShareScope scope, ShareAudience audience, int revision,
            List<RecipientView> recipients) {}

    @Transactional(readOnly = true)
    public List<PolicyView> listOutgoing(UUID actorId, UUID ownerOrgId) {
        tenants.requireAdmin(actorId, ownerOrgId);
        return policies.findByOrganizationIdWithRecipients(ownerOrgId).stream()
                .sorted(Comparator.comparing(OrganizationSharePolicy::getScope))
                .map(SharePolicyService::toView)
                .toList();
    }

    private static PolicyView toView(OrganizationSharePolicy policy) {
        List<RecipientView> views = policy.getRecipients().stream()
                .map(r -> r.getOrganization())
                .filter(o -> o.isDiscoverable() && o.getStatus() == OrganizationStatus.ACTIVE)
                .sorted(Comparator.comparing(Organization::getDisplayName, String.CASE_INSENSITIVE_ORDER))
                .map(o -> new RecipientView(o.getId(), o.getSlug(), o.getDisplayName()))
                .toList();
        return new PolicyView(policy.getScope(), policy.getAudience(), policy.getRevision(), views);
    }

    /**
     * Creates or replaces the outgoing policy of one scope. Only an ACTIVE ADMIN
     * of the owner organization may manage grants; the caller's membership is
     * the authority, never the request payload.
     */
    @Transactional
    public PolicyView upsert(UUID actorId, UUID ownerOrgId, ShareScope scope,
            ShareAudience audience, List<UUID> recipientIds) {
        lockForSettings(actorId, ownerOrgId);
        tenants.requireAdmin(actorId, ownerOrgId);
        AppUser actor = users.findById(actorId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "no membership"));
        Organization owner = organizations.findById(ownerOrgId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "no membership"));
        if (audience != ShareAudience.ALLOWLIST && recipientIds != null && !recipientIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "recipients are only allowed for the ALLOWLIST audience");
        }
        if (audience == ShareAudience.ALLOWLIST
                && (recipientIds == null || recipientIds.isEmpty())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "ALLOWLIST requires at least one recipient");
        }
        if (recipientIds != null && new HashSet<>(recipientIds).size() != recipientIds.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "duplicate recipients");
        }
        Map<UUID, Organization> validated = new LinkedHashMap<>();
        if (audience == ShareAudience.ALLOWLIST) {
            for (UUID recipientId : recipientIds) {
                if (recipientId == null) {
                    throw invalidRecipient();
                }
                if (recipientId.equals(ownerOrgId)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "owner cannot be its own recipient");
                }
                Organization recipient = organizations.lockById(recipientId)
                        .orElseThrow(() -> invalidRecipient());
                entityManager.refresh(recipient);
                if (recipient.getStatus() != OrganizationStatus.ACTIVE || !recipient.isDiscoverable()) {
                    throw invalidRecipient();
                }
                validated.put(recipientId, recipient);
            }
        }

        java.util.Optional<OrganizationSharePolicy> existing =
                policies.findByOrganizationIdAndScope(ownerOrgId, scope);
        boolean isNew = existing.isEmpty();
        OrganizationSharePolicy policy;
        if (isNew) {
            policy = new OrganizationSharePolicy(owner, scope, audience);
            policies.save(policy);
            audit.save(new OrganizationShareAudit(actor, owner, null, scope,
                    ShareAction.GRANT, "policy created"));
        } else {
            policy = existing.get();
        }

        boolean changed = false;
        if (policy.getAudience() != audience) {
            boolean widened = policy.getAudience() == ShareAudience.PRIVATE
                    || (policy.getAudience() == ShareAudience.ALLOWLIST
                            && audience == ShareAudience.ALL_DISCOVERABLE);
            policy.replaceAudience(audience);
            audit.save(new OrganizationShareAudit(actor, owner, null, scope,
                    widened ? ShareAction.GRANT : ShareAction.REVOKE,
                    "audience changed to " + audience));
            changed = true;
        }

        if (audience == ShareAudience.ALLOWLIST) {
            Set<UUID> current = policy.getRecipients().stream()
                    .map(r -> r.getOrganization().getId())
                    .collect(Collectors.toSet());
            for (OrganizationShareRecipient existingRecipient : List.copyOf(policy.getRecipients())) {
                if (!validated.containsKey(existingRecipient.getOrganization().getId())) {
                    audit.save(new OrganizationShareAudit(actor, owner,
                            existingRecipient.getOrganization(), scope, ShareAction.REVOKE,
                            "recipient removed"));
                    changed = true;
                }
            }
            policy.getRecipients().removeIf(r -> !validated.containsKey(r.getOrganization().getId()));
            for (Map.Entry<UUID, Organization> entry : validated.entrySet()) {
                if (!current.contains(entry.getKey())) {
                    policy.addRecipient(entry.getValue());
                    audit.save(new OrganizationShareAudit(actor, owner, entry.getValue(), scope,
                            ShareAction.GRANT, "recipient added"));
                    changed = true;
                }
            }
        } else if (!policy.getRecipients().isEmpty()) {
            for (OrganizationShareRecipient existingRecipient : List.copyOf(policy.getRecipients())) {
                audit.save(new OrganizationShareAudit(actor, owner,
                        existingRecipient.getOrganization(), scope, ShareAction.REVOKE,
                        "recipient removed"));
            }
            policy.clearRecipients();
            changed = true;
        }
        if (!isNew && changed) {
            policy.touch();
        }
        policies.save(policy);
        return toView(policy);
    }

    private static ResponseStatusException invalidRecipient() {
        // Uniform message: a hidden, disabled or unknown target must not be
        // distinguishable from any other invalid recipient.
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid recipient");
    }

    /**
     * Revokes one outgoing policy. Takes effect on the next resolution;
     * previously delivered content cannot be recalled.
     */
    @Transactional
    public void revoke(UUID actorId, UUID ownerOrgId, ShareScope scope) {
        lockForSettings(actorId, ownerOrgId);
        tenants.requireAdmin(actorId, ownerOrgId);
        AppUser actor = users.findById(actorId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "no membership"));
        Organization owner = organizations.findById(ownerOrgId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "no membership"));
        OrganizationSharePolicy policy = policies.findByOrganizationIdAndScope(ownerOrgId, scope)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such policy"));
        List<OrganizationShareRecipient> current = List.copyOf(policy.getRecipients());
        for (OrganizationShareRecipient recipient : current) {
            audit.save(new OrganizationShareAudit(actor, owner, recipient.getOrganization(), scope,
                    ShareAction.REVOKE, "policy revoked"));
        }
        if (current.isEmpty()) {
            audit.save(new OrganizationShareAudit(actor, owner, null, scope,
                    ShareAction.REVOKE, "policy revoked"));
        }
        policies.delete(policy);
    }

    /**
     * Owner ADMIN changes discoverability. Hiding the organization immediately
     * invalidates all of its outgoing grants and removes it from every
     * allowlist as a recipient; re-discovery never restores deleted grants.
     */
    @Transactional
    public void setDiscoverable(UUID actorId, UUID organizationId, boolean discoverable) {
        lockForSettings(actorId, organizationId);
        tenants.requireAdmin(actorId, organizationId);
        AppUser actor = users.findById(actorId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "no membership"));
        Organization org = organizations.findById(organizationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "no membership"));
        if (org.isDiscoverable() == discoverable) {
            return;
        }
        org.setDiscoverable(discoverable);
        organizations.save(org);
        if (!discoverable) {
            invalidateOutgoingGrants(organizationId, actor, "owner hidden");
            removeFromAllowlists(organizationId, actor, "recipient hidden");
        }
    }

    /**
     * Deletes every outgoing grant of one owner and writes INVALIDATE audit
     * rows. Public so the SYSADMIN organization management surface (#92) can
     * reuse the same invalidation when an organization is disabled.
     */
    @Transactional
    public void invalidateOutgoingGrants(UUID ownerOrgId, AppUser actor, String reason) {
        Organization owner = organizations.findById(ownerOrgId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such organization"));
        List<OrganizationSharePolicy> outgoing = policies.findByOrganizationIdWithRecipients(ownerOrgId);
        for (OrganizationSharePolicy policy : outgoing) {
            for (OrganizationShareRecipient recipient : policy.getRecipients()) {
                audit.save(new OrganizationShareAudit(actor, owner, recipient.getOrganization(),
                        policy.getScope(), ShareAction.INVALIDATE, reason));
            }
            if (policy.getRecipients().isEmpty()) {
                audit.save(new OrganizationShareAudit(actor, owner, null, policy.getScope(),
                        ShareAction.INVALIDATE, reason));
            }
        }
        if (!outgoing.isEmpty()) {
            policies.deleteAllInBatch(outgoing);
        }
    }

    /**
     * Removes one recipient organization from every allowlist and writes
     * INVALIDATE audit rows. Public so the SYSADMIN organization management
     * surface (#92) can reuse the same invalidation when an organization is
     * hidden or disabled.
     */
    @Transactional
    public void removeFromAllowlists(UUID recipientOrgId, AppUser actor, String reason) {
        Organization recipient = organizations.findById(recipientOrgId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such organization"));
        List<OrganizationShareRecipient> entries = recipients.findByOrganizationIdWithPolicy(recipientOrgId);
        for (OrganizationShareRecipient entry : entries) {
            OrganizationSharePolicy policy = entry.getPolicy();
            audit.save(new OrganizationShareAudit(actor, policy.getOrganization(), recipient,
                    policy.getScope(), ShareAction.INVALIDATE, reason));
        }
        if (!entries.isEmpty()) {
            recipients.deleteAllInBatch(entries);
        }
    }

    /**
     * Resolves the scopes one organization may actually read from another.
     * Default-deny and query-time: the owner must be discoverable and ACTIVE,
     * the recipient must be discoverable and ACTIVE, and the audience rules
     * must match. A hidden or disabled organization is indistinguishable from
     * one without grants: the result is simply empty, never an error.
     */
    @Transactional(readOnly = true)
    public Set<ShareScope> resolveEffectiveScopes(UUID recipientOrgId, UUID ownerOrgId) {
        if (recipientOrgId.equals(ownerOrgId)) {
            return Set.of();
        }
        Organization owner = organizations.findById(ownerOrgId).orElse(null);
        if (owner == null || !owner.isDiscoverable() || owner.getStatus() != OrganizationStatus.ACTIVE) {
            return Set.of();
        }
        Organization recipient = organizations.findById(recipientOrgId).orElse(null);
        if (recipient == null || !recipient.isDiscoverable()
                || recipient.getStatus() != OrganizationStatus.ACTIVE) {
            return Set.of();
        }
        Set<ShareScope> granted = EnumSet.noneOf(ShareScope.class);
        for (OrganizationSharePolicy policy : policies.findByOrganizationIdWithRecipients(ownerOrgId)) {
            switch (policy.getAudience()) {
                case PRIVATE -> {
                }
                case ALL_DISCOVERABLE -> granted.add(policy.getScope());
                case ALLOWLIST -> {
                    boolean listed = policy.getRecipients().stream()
                            .anyMatch(r -> r.getOrganization().getId().equals(recipientOrgId));
                    if (listed) {
                        granted.add(policy.getScope());
                    }
                }
            }
        }
        return effectiveClosure(granted);
    }

    @Transactional(readOnly = true)
    public boolean hasEffectiveScope(UUID recipientOrgId, UUID ownerOrgId, ShareScope scope) {
        return resolveEffectiveScopes(recipientOrgId, ownerOrgId).contains(scope);
    }

    /**
     * Scope prerequisites (ADR 0016): VISITS requires CARE, SITE_LABEL requires
     * CARE + VISITS, PHOTO requires CARE. A stored policy without its
     * prerequisite is never effective.
     */
    private static Set<ShareScope> effectiveClosure(Set<ShareScope> granted) {
        Set<ShareScope> effective = EnumSet.noneOf(ShareScope.class);
        if (granted.contains(ShareScope.CARE)) {
            effective.add(ShareScope.CARE);
        }
        if (granted.contains(ShareScope.VISITS) && effective.contains(ShareScope.CARE)) {
            effective.add(ShareScope.VISITS);
        }
        if (granted.contains(ShareScope.SITE_LABEL) && effective.contains(ShareScope.CARE)
                && effective.contains(ShareScope.VISITS)) {
            effective.add(ShareScope.SITE_LABEL);
        }
        if (granted.contains(ShareScope.PHOTO) && effective.contains(ShareScope.CARE)) {
            effective.add(ShareScope.PHOTO);
        }
        return effective;
    }
}
