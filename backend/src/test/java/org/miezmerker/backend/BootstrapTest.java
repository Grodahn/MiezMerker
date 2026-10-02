package org.miezmerker.backend;

import org.junit.jupiter.api.Test;
import org.miezmerker.backend.bootstrap.BootstrapRunner;
import org.miezmerker.backend.repo.AppUserRepository;
import org.miezmerker.backend.repo.MembershipRepository;
import org.miezmerker.backend.repo.OrganizationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class BootstrapTest {
    @Autowired AppUserRepository users;
    @Autowired OrganizationRepository organizations;
    @Autowired MembershipRepository memberships;
    @Autowired PasswordEncoder passwords;
    @Autowired jakarta.validation.Validator validator;

    @Test
    void bootstrapCreatesFirstOrgAndAdminOnce() {
        memberships.deleteAll();
        users.deleteAll();
        organizations.deleteAll();

        var runner = new BootstrapRunner(users, organizations, memberships, passwords, validator,
                "bootstrap-admin@example.org", "supersecret-bootstrap-1", "versuch",
                "Versuchsorganisation", null);
        runner.run(null);
        assertTrue(users.findByEmail("bootstrap-admin@example.org").isPresent());
        assertTrue(organizations.findBySlug("versuch").isPresent());
        var stored = users.findByEmail("bootstrap-admin@example.org").orElseThrow();
        assertTrue(passwords.matches("supersecret-bootstrap-1", stored.getPasswordHash()));
        assertNotEquals("supersecret-bootstrap-1", stored.getPasswordHash());

        long userCount = users.count();
        // Second run is a no-op: no duplicate admin, no overwrite.
        runner.run(null);
        assertEquals(userCount, users.count());
    }

    @Test
    void bootstrapRejectsPasswordsOverBcryptsByteLimitBeforeWriting() {
        memberships.deleteAll();
        users.deleteAll();
        organizations.deleteAll();
        var runner = new BootstrapRunner(users, organizations, memberships, passwords, validator,
                "too-long@example.org", "ä".repeat(37), "versuch", "V", null);
        assertThrows(IllegalStateException.class, () -> runner.run(null));
        assertEquals(0, users.count());
        assertEquals(0, organizations.count());
    }

    @Test
    void emailNormalizationIsIndependentOfTheServersLocale() {
        var previous = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
            assertEquals("initial@example.org", org.miezmerker.backend.domain.AppUser.normalizeEmail(" INITIAL@EXAMPLE.ORG "));
        } finally {
            java.util.Locale.setDefault(previous);
        }
    }

    @Test
    void bootstrapRequiresNoSecretsInRepoAndStrongPassword() {
        memberships.deleteAll();
        users.deleteAll();
        organizations.deleteAll();
        var weak = new BootstrapRunner(users, organizations, memberships, passwords, validator,
                "weak@example.org", "short", "versuch", "V", null);
        assertThrows(IllegalStateException.class, () -> weak.run(null));
        assertTrue(users.findByEmail("weak@example.org").isEmpty());
    }

    @Test
    void bootstrapRejectsAnEmailThatCannotBeUsedByTheLoginApi() {
        memberships.deleteAll();
        users.deleteAll();
        organizations.deleteAll();
        var runner = new BootstrapRunner(users, organizations, memberships, passwords, validator,
                "invalid-address", "supersecret-bootstrap-1", "versuch", "V", null);
        assertThrows(IllegalStateException.class, () -> runner.run(null));
        assertEquals(0, users.count());
        assertEquals(0, organizations.count());
    }

    @Test
    void invalidBootstrapMetadataIsRejectedBeforeWritingAnyEntities() {
        memberships.deleteAll();
        users.deleteAll();
        organizations.deleteAll();
        for (String[] metadata : new String[][] {
                { "a".repeat(65), "Name", null },
                { "valid", "a".repeat(256), null },
                { "valid", "Name", "a".repeat(501) }
        }) {
            var runner = new BootstrapRunner(users, organizations, memberships, passwords, validator,
                    "valid@example.org", "supersecret-bootstrap-1", metadata[0], metadata[1], metadata[2]);
            assertThrows(IllegalStateException.class, () -> runner.run(null));
            assertEquals(0, users.count());
            assertEquals(0, organizations.count());
            assertEquals(0, memberships.count());
        }
    }

    @Test
    void bootstrapCannotAttachTheFirstAdminToADisabledOrganization() {
        memberships.deleteAll();
        users.deleteAll();
        organizations.deleteAll();
        var organization = new org.miezmerker.backend.domain.Organization("disabled", "Disabled", null);
        organization.setStatus(org.miezmerker.backend.domain.OrganizationStatus.DISABLED);
        organizations.save(organization);
        var runner = new BootstrapRunner(users, organizations, memberships, passwords, validator,
                "valid@example.org", "supersecret-bootstrap-1", "disabled", "Disabled", null);
        assertThrows(IllegalStateException.class, () -> runner.run(null));
        assertEquals(0, users.count());
        assertEquals(0, memberships.count());
        assertEquals(1, organizations.count());
    }
}
