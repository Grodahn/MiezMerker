package org.miezmerker.backend;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.miezmerker.backend.bootstrap.SystemRoleMaintenance;
import static org.junit.jupiter.api.Assertions.*;

/** Exercise actual JDBC commit behavior when maintenance audit writes fail. */
@Tag("auth")
class SystemRoleMaintenanceTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void auditWriteFailureRollsBackGrantIncludingJvmErrors(boolean fatal) throws Exception {
        try (var fixture = fixture()) {
            Throwable writeFailure = fatal ? new AssertionError("audit write interrupted")
                    : new SQLException("audit write failed");
            var faults = new Faults(fixture.connection(), writeFailure, null);
            Throwable thrown = assertThrows(Throwable.class, () -> SystemRoleMaintenance.apply(
                    faults.connection(), UUID.randomUUID(), fixture.userId(), "GRANT", "operator", "test"));
            assertSame(writeFailure, thrown);
            assertEquals(1, faults.rollbackAttempts);
            assertTrue(fixture.connection().getAutoCommit());
            assertEquals(0, count(fixture.connection(), "app_user_system_roles"));
            assertEquals(0, count(fixture.connection(), "app_user_system_role_audit"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedRollbackNeverRestoresAutoCommitOrPublishesPartialGrant(boolean fatal) throws Exception {
        try (var fixture = fixture()) {
            Throwable writeFailure = fatal ? new AssertionError("audit write interrupted")
                    : new SQLException("audit write failed");
            SQLException rollbackFailure = new SQLException("rollback failed");
            var faults = new Faults(fixture.connection(), writeFailure, rollbackFailure);
            Throwable thrown = assertThrows(Throwable.class, () -> SystemRoleMaintenance.apply(
                    faults.connection(), UUID.randomUUID(), fixture.userId(), "GRANT", "operator", "test"));
            assertSame(writeFailure, thrown);
            assertArrayEquals(new Throwable[] {rollbackFailure}, thrown.getSuppressed());
            assertEquals(1, faults.rollbackAttempts);
            assertFalse(faults.autoCommitRestored, "restoring auto-commit would commit the pending role write");
            assertFalse(fixture.connection().getAutoCommit());
            // Another database connection must never see the incomplete grant.
            try (var reader = DriverManager.getConnection(fixture.url(), "sa", "")) {
                assertEquals(0, count(reader, "app_user_system_roles"));
                assertEquals(0, count(reader, "app_user_system_role_audit"));
            }
            fixture.connection().rollback();
            assertEquals(0, count(fixture.connection(), "app_user_system_roles"));
        }
    }

    private record Fixture(Connection connection, UUID userId, String url) implements AutoCloseable {
        @Override public void close() throws SQLException { connection.close(); }
    }

    private static Fixture fixture() throws SQLException {
        String url = "jdbc:h2:mem:maintenance-failure-" + UUID.randomUUID() + ";MODE=PostgreSQL";
        Connection connection = DriverManager.getConnection(url, "sa", "");
        try {
            Flyway.configure().dataSource(url, "sa", "").load().migrate();
            UUID userId = UUID.randomUUID();
            try (var statement = connection.prepareStatement(
                    "INSERT INTO app_users(id, email, password_hash) VALUES (?, 'operator@example.org', 'test-hash')")) {
                statement.setObject(1, userId);
                statement.executeUpdate();
            }
            return new Fixture(connection, userId, url);
        } catch (SQLException | RuntimeException | Error exception) {
            connection.close();
            throw exception;
        }
    }

    private static int count(Connection connection, String table) throws SQLException {
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    /** Delegate to a real JDBC connection, injecting failures only after the role write. */
    private static class Faults {
        private final Connection delegate;
        private final Throwable writeFailure;
        private final SQLException rollbackFailure;
        int rollbackAttempts;
        boolean autoCommitRestored;

        Faults(Connection delegate, Throwable writeFailure, SQLException rollbackFailure) {
            this.delegate = delegate;
            this.writeFailure = writeFailure;
            this.rollbackFailure = rollbackFailure;
        }

        Connection connection() {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                        if (method.getName().equals("rollback")) {
                            rollbackAttempts++;
                            if (rollbackFailure != null) throw rollbackFailure;
                        }
                        if (method.getName().equals("setAutoCommit") && Boolean.TRUE.equals(args[0])) {
                            autoCommitRestored = true;
                        }
                        Object result = invoke(delegate, method, args);
                        if (method.getName().equals("prepareStatement")
                                && ((String) args[0]).contains("INSERT INTO app_user_system_role_audit")) {
                            return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                                    new Class<?>[] {PreparedStatement.class}, (statementProxy, statementMethod, statementArgs) -> {
                                        if (statementMethod.getName().equals("executeUpdate")) throw writeFailure;
                                        return invoke(result, statementMethod, statementArgs);
                                    });
                        }
                        return result;
                    });
        }
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
        }
    }
}
