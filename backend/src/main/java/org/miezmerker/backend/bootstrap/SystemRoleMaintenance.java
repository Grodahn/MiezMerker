package org.miezmerker.backend.bootstrap;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

/** Explicit server-operator CLI, invoked before Spring starts. No HTTP surface or startup grant. */
public final class SystemRoleMaintenance {
    private SystemRoleMaintenance() {}

    public static void run(String[] args) throws SQLException {
        if (args.length != 6) {
            throw new IllegalArgumentException(
                    "Usage: sysadmin GRANT|REVOKE operation-uuid user-uuid operator reason");
        }
        String url = requiredEnv("DB_URL");
        if (!url.startsWith("jdbc:postgresql:")) {
            throw new IllegalArgumentException("Maintenance requires a PostgreSQL DB_URL");
        }
        try (Connection connection = DriverManager.getConnection(url,
                requiredEnv("DB_USER"), requiredEnv("DB_PASSWORD"))) {
            try (var statement = connection.createStatement()) {
                statement.execute("SET search_path TO public");
            }
            boolean changed = apply(connection, UUID.fromString(args[2]), UUID.fromString(args[3]),
                    args[1], args[4], args[5]);
            System.out.println("SYSADMIN operation " + args[2] + " completed; changed=" + changed);
        }
    }

    private static String requiredEnv(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " must be explicitly supplied for maintenance");
        }
        return value;
    }

    /** Owns one transaction on a dedicated connection. Used by the CLI and integration tests. */
    public static boolean apply(Connection connection, UUID operationId, UUID userId,
            String action, String operator, String reason) throws SQLException {
        if (operationId == null || userId == null || !("GRANT".equals(action) || "REVOKE".equals(action))
                || operator == null || operator.isBlank() || operator.length() > 255
                || reason == null || reason.isBlank() || reason.length() > 500) {
            throw new IllegalArgumentException("Invalid maintenance operation or audit metadata");
        }
        if (!connection.getAutoCommit()) {
            throw new IllegalArgumentException("Maintenance requires a dedicated auto-commit connection");
        }
        connection.setAutoCommit(false);
        boolean transactionResolved = false;
        try {
            // Serializes operations for the same account, including the first grant.
            String status;
            try (var statement = connection.prepareStatement("SELECT status FROM app_users WHERE id = ? FOR UPDATE")) {
                statement.setObject(1, userId);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) throw new IllegalArgumentException("Account must already exist");
                    status = rows.getString(1);
                }
            }
            try (var statement = connection.prepareStatement("""
                    SELECT user_id, action, operator, reason FROM app_user_system_role_audit WHERE operation_id = ?
                    """)) {
                statement.setObject(1, operationId);
                try (var rows = statement.executeQuery()) {
                    if (rows.next()) {
                        if (!userId.equals(rows.getObject(1, UUID.class)) || !action.equals(rows.getString(2))
                                || !operator.equals(rows.getString(3)) || !reason.equals(rows.getString(4))) {
                            throw new IllegalArgumentException("Operation ID already used for another command");
                        }
                        connection.commit();
                        transactionResolved = true;
                        return false; // A consumed grant must never recreate a revoked privilege.
                    }
                }
            }
            boolean grant = "GRANT".equals(action);
            if (grant && !"ACTIVE".equals(status)) {
                throw new IllegalArgumentException("Only an ACTIVE account can receive SYSADMIN");
            }
            Boolean enabled = null;
            try (var statement = connection.prepareStatement(
                    "SELECT enabled FROM app_user_system_roles WHERE user_id = ? AND role = 'SYSADMIN'")) {
                statement.setObject(1, userId);
                try (var rows = statement.executeQuery()) {
                    if (rows.next()) enabled = rows.getBoolean(1);
                }
            }
            boolean changed = grant != Boolean.TRUE.equals(enabled);
            if (enabled == null) {
                try (var statement = connection.prepareStatement(
                        "INSERT INTO app_user_system_roles(user_id, role, enabled) VALUES (?, 'SYSADMIN', ?)")) {
                    statement.setObject(1, userId);
                    statement.setBoolean(2, grant);
                    statement.executeUpdate();
                }
            } else if (changed) {
                try (var statement = connection.prepareStatement(
                        "UPDATE app_user_system_roles SET enabled = ? WHERE user_id = ? AND role = 'SYSADMIN'")) {
                    statement.setBoolean(1, grant);
                    statement.setObject(2, userId);
                    statement.executeUpdate();
                }
            }
            try (var statement = connection.prepareStatement("""
                    INSERT INTO app_user_system_role_audit(operation_id, user_id, role, action, operator, reason, changed)
                    VALUES (?, ?, 'SYSADMIN', ?, ?, ?, ?)
                    """)) {
                statement.setObject(1, operationId);
                statement.setObject(2, userId);
                statement.setString(3, action);
                statement.setString(4, operator);
                statement.setString(5, reason);
                statement.setBoolean(6, changed);
                statement.executeUpdate();
            }
            connection.commit();
            transactionResolved = true;
            return changed;
        } catch (SQLException | RuntimeException | Error exception) {
            try {
                connection.rollback();
                transactionResolved = true;
            } catch (SQLException rollbackFailure) {
                exception.addSuppressed(rollbackFailure);
            }
            throw exception;
        } finally {
            // JDBC commits an open transaction when auto-commit is restored.
            // If rollback failed, leave this dedicated connection for its owner to close.
            if (transactionResolved) {
                connection.setAutoCommit(true);
            }
        }
    }
}
