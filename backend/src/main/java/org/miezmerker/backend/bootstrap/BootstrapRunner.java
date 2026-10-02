package org.miezmerker.backend.bootstrap;

import org.miezmerker.backend.domain.AppUser;
import org.miezmerker.backend.domain.MembershipRole;
import org.miezmerker.backend.domain.MembershipStatus;
import org.miezmerker.backend.domain.Organization;
import org.miezmerker.backend.domain.OrganizationMembership;
import org.miezmerker.backend.repo.AppUserRepository;
import org.miezmerker.backend.repo.MembershipRepository;
import org.miezmerker.backend.repo.OrganizationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Test-phase bootstrap for #16. No public self-registration; the very first organization and
 * admin are created from environment variables on empty databases only.
 *
 * <p>Required env (no secrets in git):
 * {@code BOOTSTRAP_ADMIN_EMAIL}, {@code BOOTSTRAP_ADMIN_PASSWORD} (min 12 chars),
 * optional {@code BOOTSTRAP_ORG_SLUG} (default {@code versuch}), {@code BOOTSTRAP_ORG_NAME},
 * {@code BOOTSTRAP_ORG_CONTACT}.
 *
 * <p>If users already exist, bootstrap does nothing (idempotent, no overwrite).
 */
@Component
public class BootstrapRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(BootstrapRunner.class);

    private final AppUserRepository users;
    private final OrganizationRepository organizations;
    private final MembershipRepository memberships;
    private final PasswordEncoder passwords;

    private final String adminEmail;
    private final String adminPassword;
    private final String orgSlug;
    private final String orgName;
    private final String orgContact;

    public BootstrapRunner(AppUserRepository users, OrganizationRepository organizations,
            MembershipRepository memberships, PasswordEncoder passwords,
            @Value("${miezmerker.bootstrap.admin-email:}") String adminEmail,
            @Value("${miezmerker.bootstrap.admin-password:}") String adminPassword,
            @Value("${miezmerker.bootstrap.org-slug:versuch}") String orgSlug,
            @Value("${miezmerker.bootstrap.org-name:Versuchsorganisation}") String orgName,
            @Value("${miezmerker.bootstrap.org-contact:}") String orgContact) {
        this.users = users;
        this.organizations = organizations;
        this.memberships = memberships;
        this.passwords = passwords;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
        this.orgSlug = orgSlug;
        this.orgName = orgName;
        this.orgContact = orgContact;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (users.count() > 0) {
            return;
        }
        if (adminEmail == null || adminEmail.isBlank() || adminPassword == null || adminPassword.isBlank()) {
            log.warn("No users exist and no bootstrap admin configured (BOOTSTRAP_ADMIN_EMAIL/_PASSWORD). "
                    + "Login is unavailable until bootstrap runs. See docs/bootstrap.md.");
            return;
        }
        String email = AppUser.normalizeEmail(adminEmail);
        if (adminPassword.length() < 12) {
            throw new IllegalStateException("BOOTSTRAP_ADMIN_PASSWORD must have at least 12 characters");
        }
        String slug = orgSlug == null || orgSlug.isBlank() ? "versuch"
                : orgSlug.trim().toLowerCase().replaceAll("[^a-z0-9-]", "-");
        Organization org = organizations.findBySlug(slug)
                .orElseGet(() -> organizations.save(new Organization(slug,
                        orgName == null || orgName.isBlank() ? "Versuchsorganisation" : orgName,
                        orgContact == null || orgContact.isBlank() ? null : orgContact)));
        AppUser user = users.save(new AppUser(email, passwords.encode(adminPassword)));
        OrganizationMembership membership =
                new OrganizationMembership(org, user, MembershipRole.ADMIN, MembershipStatus.ACTIVE);
        memberships.save(membership);
        log.info("Bootstrapped organization '{}' with admin '{}'.", slug, email);
    }
}
