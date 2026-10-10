package org.miezmerker.backend;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@Tag("schema")
class SystemRoleMigrationTest {
    @Test
    void upgradePreservesLegacyMembershipsAndPromotesNobody() throws Exception {
        String url = "jdbc:h2:mem:sysadmin-migration-" + UUID.randomUUID() + ";MODE=PostgreSQL";
        try (var connection = DriverManager.getConnection(url, "sa", "")) {
            Flyway.configure().dataSource(url, "sa", "").target("10").load().migrate();
            UUID userId = UUID.randomUUID();
            try (var statement = connection.prepareStatement(
                    "INSERT INTO app_users(id, email, password_hash) VALUES (?, 'legacy@example.org', 'unchanged-hash')")) {
                statement.setObject(1, userId);
                statement.executeUpdate();
            }
            for (String role : new String[] {"ADMIN", "MEMBER"}) {
                for (String status : new String[] {"ACTIVE", "PENDING", "DISABLED"}) {
                    UUID orgId = UUID.randomUUID();
                    try (var statement = connection.prepareStatement(
                            "INSERT INTO organizations(id, slug, display_name) VALUES (?, ?, 'Legacy')")) {
                        statement.setObject(1, orgId);
                        statement.setString(2, role.toLowerCase() + "-" + status.toLowerCase());
                        statement.executeUpdate();
                    }
                    try (var statement = connection.prepareStatement("""
                            INSERT INTO organization_memberships(id, organization_id, user_id, role, status)
                            VALUES (?, ?, ?, ?, ?)
                            """)) {
                        statement.setObject(1, UUID.randomUUID());
                        statement.setObject(2, orgId);
                        statement.setObject(3, userId);
                        statement.setString(4, role);
                        statement.setString(5, status);
                        statement.executeUpdate();
                    }
                }
            }
            var flyway = Flyway.configure().dataSource(url, "sa", "").load();
            flyway.migrate();
            flyway.validate();
            // Repeat normal schema startup; no grants or ledger entries appear.
            flyway.migrate();
            try (var statement = connection.createStatement()) {
                for (String table : new String[] {"app_user_system_roles", "app_user_system_role_audit"}) {
                    try (var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                        assertTrue(rows.next());
                        assertEquals(0, rows.getInt(1));
                    }
                }
                try (var rows = statement.executeQuery("SELECT role, status, COUNT(*) FROM organization_memberships GROUP BY role, status")) {
                    int combinations = 0;
                    while (rows.next()) {
                        assertTrue(java.util.Set.of("ADMIN", "MEMBER").contains(rows.getString(1)));
                        assertTrue(java.util.Set.of("ACTIVE", "PENDING", "DISABLED").contains(rows.getString(2)));
                        assertEquals(1, rows.getInt(3));
                        combinations++;
                    }
                    assertEquals(6, combinations);
                }
                try (var rows = statement.executeQuery("SELECT password_hash FROM app_users")) {
                    assertTrue(rows.next());
                    assertEquals("unchanged-hash", rows.getString(1));
                    assertFalse(rows.next());
                }
            }
        }
    }
}
