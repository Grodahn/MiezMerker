package org.miezmerker.backend.admin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import org.miezmerker.backend.domain.*;
import org.miezmerker.backend.repo.OrganizationRepository;
import org.miezmerker.backend.security.TenantService;
import org.miezmerker.backend.service.SharePolicyService;
import org.miezmerker.backend.service.SharePolicyService.PolicyView;
import org.miezmerker.backend.service.SharePolicyService.RecipientView;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Form adapter only: policy semantics and revocation remain in SharePolicyService. */
@Service
public class OrganizationSettingsService {
    private final TenantService tenants;
    private final OrganizationRepository organizations;
    private final SharePolicyService shares;

    public OrganizationSettingsService(TenantService tenants, OrganizationRepository organizations,
            SharePolicyService shares) {
        this.tenants = tenants;
        this.organizations = organizations;
        this.shares = shares;
    }

    public record Choice(ShareAudience audience, List<UUID> recipients) {}
    public record Settings(boolean hidden, Map<ShareScope, Choice> choices,
            List<RecipientView> recipients, String version) {}

    @Transactional(readOnly = true)
    public Settings read(UUID actor, UUID owner) {
        Organization org = tenants.requireAdmin(actor, owner).getOrganization();
        List<PolicyView> policies = shares.listOutgoing(actor, owner);
        Map<ShareScope, Choice> choices = new EnumMap<>(ShareScope.class);
        for (ShareScope scope : ShareScope.values()) {
            choices.put(scope, new Choice(ShareAudience.PRIVATE, List.of()));
        }
        for (PolicyView policy : policies) {
            choices.put(policy.scope(), new Choice(policy.audience(),
                    policy.recipients().stream().map(RecipientView::id).toList()));
        }
        List<RecipientView> recipients = organizations
                .findByDiscoverableTrueAndStatus(OrganizationStatus.ACTIVE).stream()
                .filter(o -> !o.getId().equals(owner))
                .sorted(Comparator.comparing(Organization::getDisplayName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(Organization::getId))
                .map(o -> new RecipientView(o.getId(), o.getSlug(), o.getDisplayName())).toList();
        // Hash instead of foreign IDs in a hidden input. Include eligibility so stale
        // forms cannot affirmatively restore a recipient invalidated since rendering.
        return new Settings(!org.isDiscoverable(), choices, recipients,
                hash(owner + ":" + org.isDiscoverable() + ":" + policies + ":" + recipients));
    }

    @Transactional
    public void save(UUID actor, UUID owner, String version, boolean hidden,
            Map<ShareScope, Choice> requested, boolean confirmed, boolean revokePhoto) {
        shares.lockForSettings(actor, owner);
        Settings current = read(actor, owner);
        if (!current.version().equals(version)) {
            throw error(HttpStatus.CONFLICT,
                    "Einstellungen oder verfügbare Empfänger haben sich geändert. Bitte erneut prüfen.");
        }
        if (hidden) {
            shares.setDiscoverable(actor, owner, false);
            // Also clear policies explicitly stored while hidden via the policy API.
            for (PolicyView policy : shares.listOutgoing(actor, owner)) {
                shares.revoke(actor, owner, policy.scope());
            }
            return;
        }
        Set<UUID> eligible = new HashSet<>(current.recipients().stream().map(RecipientView::id).toList());
        boolean expansion = current.hidden();
        for (ShareScope scope : List.of(ShareScope.CARE, ShareScope.VISITS, ShareScope.SITE_LABEL)) {
            Choice choice = requested.get(scope);
            if (choice == null || choice.audience() == null
                    || (choice.audience() == ShareAudience.ALLOWLIST
                        && (choice.recipients().isEmpty() || !eligible.containsAll(choice.recipients())
                            || new HashSet<>(choice.recipients()).size() != choice.recipients().size()))) {
                throw error(HttpStatus.BAD_REQUEST, "Bitte eine gültige Freigabe und verfügbare Empfänger wählen.");
            }
            expansion |= expands(choice, current.choices().get(scope));
        }
        if (!covered(requested.get(ShareScope.VISITS), requested.get(ShareScope.CARE))
                || !covered(requested.get(ShareScope.SITE_LABEL), requested.get(ShareScope.VISITS))) {
            throw error(HttpStatus.BAD_REQUEST,
                    "Besuche benötigen Betreuung für dieselben Empfänger; Futterstellennamen benötigen zusätzlich Besuche.");
        }
        if (expansion && !confirmed) {
            throw error(HttpStatus.BAD_REQUEST, "Bitte die Erweiterung der Sichtbarkeit oder Freigaben ausdrücklich bestätigen.");
        }
        shares.setDiscoverable(actor, owner, true);
        for (ShareScope scope : List.of(ShareScope.CARE, ShareScope.VISITS, ShareScope.SITE_LABEL)) {
            Choice choice = requested.get(scope);
            if (choice.audience() == ShareAudience.PRIVATE) {
                if (shares.listOutgoing(actor, owner).stream().anyMatch(p -> p.scope() == scope)) {
                    shares.revoke(actor, owner, scope);
                }
            } else {
                shares.upsert(actor, owner, scope, choice.audience(),
                        choice.audience() == ShareAudience.ALLOWLIST ? choice.recipients() : List.of());
            }
        }
        // Never enable PHOTO until #95 supplies the separate media authorization.
        // Removing CARE must not leave a dormant PHOTO grant that later reactivates.
        if (revokePhoto || current.hidden()
                || expands(requested.get(ShareScope.CARE), current.choices().get(ShareScope.CARE))
                || !covered(current.choices().get(ShareScope.PHOTO), current.choices().get(ShareScope.CARE))
                || !covered(current.choices().get(ShareScope.PHOTO), requested.get(ShareScope.CARE))) {
            if (shares.listOutgoing(actor, owner).stream().anyMatch(p -> p.scope() == ShareScope.PHOTO)) {
                shares.revoke(actor, owner, ShareScope.PHOTO);
            }
        }
    }

    private static boolean expands(Choice next, Choice old) {
        if (next.audience() == ShareAudience.PRIVATE || old.audience() == ShareAudience.ALL_DISCOVERABLE) return false;
        return old.audience() == ShareAudience.PRIVATE || next.audience() == ShareAudience.ALL_DISCOVERABLE
                || !old.recipients().containsAll(next.recipients());
    }

    private static boolean covered(Choice child, Choice parent) {
        if (child.audience() == ShareAudience.PRIVATE || parent.audience() == ShareAudience.ALL_DISCOVERABLE) return true;
        return child.audience() == ShareAudience.ALLOWLIST && parent.audience() == ShareAudience.ALLOWLIST
                && parent.recipients().containsAll(child.recipients());
    }

    private static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ResponseStatusException error(HttpStatus status, String message) {
        return new ResponseStatusException(status, message);
    }
}
