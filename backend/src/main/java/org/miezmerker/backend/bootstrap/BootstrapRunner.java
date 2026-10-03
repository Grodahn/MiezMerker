package org.miezmerker.backend.bootstrap;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.miezmerker.backend.domain.AppUser;
import org.miezmerker.backend.domain.MembershipRole;
import org.miezmerker.backend.domain.MembershipStatus;
import org.miezmerker.backend.domain.Organization;
import org.miezmerker.backend.domain.OrganizationMembership;
import org.miezmerker.backend.domain.OrganizationStatus;
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
    private final Validator validator;

    private record BootstrapInput(@NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(max = 64) String slug,
            @NotBlank @Size(max = 255) String name,
            @Size(max = 500) String contact) {}

    private final String adminEmail;
    private final String adminPassword;
    private final String orgSlug;
    private final String orgName;
    private final String orgContact;

    public BootstrapRunner(AppUserRepository users, OrganizationRepository organizations,
            MembershipRepository memberships, PasswordEncoder passwords, Validator validator,
            @Value("${miezmerker.bootstrap.admin-email:}") String adminEmail,
            @Value("${miezmerker.bootstrap.admin-password:}") String adminPassword,
            @Value("${miezmerker.bootstrap.org-slug:versuch}") String orgSlug,
            @Value("${miezmerker.bootstrap.org-name:Versuchsorganisation}") String orgName,
            @Value("${miezmerker.bootstrap.org-contact:}") String orgContact) {
        this.users = users;
        this.organizations = organizations;
        this.memberships = memberships;
        this.passwords = passwords;
        this.validator = validator;
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
        if (adminPassword.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalStateException("BOOTSTRAP_ADMIN_PASSWORD must not exceed 72 UTF-8 bytes");
        }
        String slug = orgSlug == null || orgSlug.isBlank() ? "versuch"
                : orgSlug.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-");
        String name = orgName == null || orgName.isBlank() ? "Versuchsorganisation" : orgName.trim();
        String contact = orgContact == null || orgContact.isBlank() ? null : orgContact.trim();
        // Validate only public metadata; password validation errors must never log a secret.
        if (!validator.validate(new BootstrapInput(email, slug, name, contact)).isEmpty()) {
            throw new IllegalStateException("Invalid bootstrap email or organization metadata");
        }
        Organization org = organizations.findBySlug(slug)
                .orElseGet(() -> organizations.save(new Organization(slug, name, contact)));
        if (org.getStatus() != OrganizationStatus.ACTIVE) {
            throw new IllegalStateException("Bootstrap organization must be active");
        }
        AppUser user = users.save(new AppUser(email, passwords.encode(adminPassword)));
        OrganizationMembership membership =
                new OrganizationMembership(org, user, MembershipRole.ADMIN, MembershipStatus.ACTIVE);
        memberships.save(membership);
        log.info("Bootstrapped organization '{}' with admin '{}'.", slug, email);
    }
}
