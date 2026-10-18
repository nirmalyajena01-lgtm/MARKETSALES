package com.supermarket.persistence;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Embedded database migration runner.
 * Automatically initializes the schema and applies versioned SQL migrations.
 */
public final class MigrationRunner {
    private static final Logger LOGGER = Logger.getLogger(MigrationRunner.class.getName());

    private MigrationRunner() {
    }

    public static void migrate() {
        migrate(DatabaseManager.getInstance());
    }

    public static void migrate(DatabaseManager db) {
        db.getWriteLock().lock();
        try (Connection conn = db.getConnection()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("""
                        CREATE TABLE IF NOT EXISTS schema_version (
                            version INTEGER PRIMARY KEY,
                            description TEXT NOT NULL,
                            applied_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
                        );
                    """);
                }

                int currentVersion = getCurrentVersion(conn);
                LOGGER.info(() -> "Current database schema version: " + currentVersion);

                if (currentVersion < 1) {
                    LOGGER.info("Applying migration V1: Initial Schema");
                    executeSqlScript(conn, "/db/migrations/V1__init_schema.sql");
                    recordVersion(conn, 1, "V1__init_schema.sql");
                }

                if (currentVersion < 2) {
                    LOGGER.info("Applying migration V2: Catalog Seed Data");
                    executeSqlScript(conn, "/db/migrations/V2__seed_initial_catalog.sql");
                    recordVersion(conn, 2, "V2__seed_initial_catalog.sql");
                }

                if (currentVersion < 3) {
                    LOGGER.info("Applying migration V3: Convert USD amounts to INR");
                    executeSqlScript(conn, "/db/migrations/V3__convert_usd_to_inr.sql");
                    recordVersion(conn, 3, "V3__convert_usd_to_inr.sql");
                }

                if (currentVersion < 4) {
                    LOGGER.info("Applying migration V4: Operator Accounts");
                    executeSqlScript(conn, "/db/migrations/V4__operator_accounts.sql");
                    recordVersion(conn, 4, "V4__operator_accounts.sql");
                }

                if (currentVersion < 5) {
                    LOGGER.info("Applying migration V5: Durable Idempotency Keys");
                    executeSqlScript(conn, "/db/migrations/V5__idempotency_keys.sql");
                    recordVersion(conn, 5, "V5__idempotency_keys.sql");
                }

                conn.commit();
                LOGGER.info("Database migrations applied successfully.");
            } catch (Exception e) {
                conn.rollback();
                LOGGER.log(Level.SEVERE, "Database migration failed, transaction rolled back", e);
                throw new IllegalStateException("Database migration failed: " + e.getMessage(), e);
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to establish connection for migration", e);
        } finally {
            db.getWriteLock().unlock();
        }
    }

    private static int getCurrentVersion(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static void recordVersion(Connection conn, int version, String description) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO schema_version (version, description) VALUES (?, ?)")) {
            ps.setInt(1, version);
            ps.setString(2, description);
            ps.executeUpdate();
        }
    }

    private static void executeSqlScript(Connection conn, String resourcePath) throws Exception {
        InputStream in = MigrationRunner.class.getResourceAsStream(resourcePath);
        if (in == null) {
            throw new IllegalStateException("Migration script not found on classpath: " + resourcePath);
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith("--") || trimmed.isEmpty()) {
                    continue;
                }
                sb.append(line).append("\n");
            }
        }

        String[] statements = sb.toString().split(";");
        try (Statement stmt = conn.createStatement()) {
            for (String rawSql : statements) {
                String sql = rawSql.trim();
                if (!sql.isEmpty()) {
                    stmt.execute(sql);
                }
            }
        }
    }
}
