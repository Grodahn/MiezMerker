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

    @Test
    void bootstrapCreatesFirstOrgAndAdminOnce() {
        memberships.deleteAll();
        users.deleteAll();
        organizations.deleteAll();

        var runner = new BootstrapRunner(users, organizations, memberships, passwords,
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
    void bootstrapRequiresNoSecretsInRepoAndStrongPassword() {
        memberships.deleteAll();
        users.deleteAll();
        organizations.deleteAll();
        var weak = new BootstrapRunner(users, organizations, memberships, passwords,
                "weak@example.org", "short", "versuch", "V", null);
        assertThrows(IllegalStateException.class, () -> weak.run(null));
        assertTrue(users.findByEmail("weak@example.org").isEmpty());
    }
}
