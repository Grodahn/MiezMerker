package org.miezmerker.backend;

import org.junit.jupiter.api.Tag;

import java.sql.Connection;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Tag("schema")
class DatabaseSchemaTest {
    @Autowired DataSource dataSource;

    @Test
    void newConnectionsResolveTheSameMigratedTablesAfterTheReservedSchemaExists() throws Exception {
        // Hold connections concurrently to exercise distinct physical pool connections.
        // PostgreSQL's default "$user",public search path changes after V1 creates
        // the schema named miezmerker; H2 alone cannot reproduce that first-boot bug.
        try (Connection first = dataSource.getConnection();
                Connection second = dataSource.getConnection();
                Connection third = dataSource.getConnection()) {
            for (Connection connection : List.of(first, second, third)) {
                assertEquals("public", connection.getSchema().toLowerCase(java.util.Locale.ROOT));
                try (var statement = connection.createStatement()) {
                    // JPA and the native claim lock query must see the Flyway tables.
                    try (var devices = statement.executeQuery("select count(*) from app_devices")) {
                        assertTrue(devices.next());
                    }
                    try (var lock = statement.executeQuery("select id from node_claim_lock")) {
                        assertTrue(lock.next());
                        assertEquals(1, lock.getInt(1));
                        assertFalse(lock.next());
                    }
                    try (var history = statement.executeQuery(
                            "select count(*) from \"flyway_schema_history\" where \"success\" = true")) {
                        assertTrue(history.next());
                        assertTrue(history.getInt(1) > 0);
                    }
                }
            }
        }
    }
}
