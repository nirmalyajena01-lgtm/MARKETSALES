package com.supermarket.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseManagerTest {
    private DatabaseManager db;

    @BeforeEach
    void setUp() {
        db = DatabaseManager.createInMemory();
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    @Test
    @DisplayName("Should initialize in-memory database with foreign keys enabled")
    void testInMemoryDatabaseInitialization() throws SQLException {
        assertThat(db.isInMemory()).isTrue();
        try (Connection conn = db.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA foreign_keys;")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("Should run migrations and populate catalog with INR prices")
    void testMigrationExecution() throws SQLException {
        MigrationRunner.migrate(db);

        try (Connection conn = db.getConnection();
             Statement stmt = conn.createStatement()) {
            // Verify schema_version
            try (ResultSet rs = stmt.executeQuery("SELECT MAX(version) FROM schema_version")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(5);
            }

            // Verify items catalog seeded
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM items")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isGreaterThanOrEqualTo(20);
            }
            try (ResultSet rs = stmt.executeQuery("SELECT current_selling_price FROM items WHERE code = 'PROD-001'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBigDecimal(1)).isEqualByComparingTo("140.27");
            }
        }
    }

    @Test
    @DisplayName("Should be idempotent when migration is executed multiple times")
    void testMigrationIdempotence() throws SQLException {
        MigrationRunner.migrate(db);
        int countFirst;
        try (Connection conn = db.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM items")) {
            rs.next();
            countFirst = rs.getInt(1);
        }

        // Run migration again
        MigrationRunner.migrate(db);

        try (Connection conn = db.getConnection();
             Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery("SELECT MAX(version) FROM schema_version")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(5);
            }
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM items")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(countFirst);
            }
        }
    }

    @Test
    @DisplayName("Should rollback transaction when work throws exception")
    void testTransactionRollbackOnException() throws SQLException {
        MigrationRunner.migrate(db);

        assertThatThrownBy(() -> {
            db.executeInTransaction(conn -> {
                try (Statement stmt = conn.createStatement()) {
                    stmt.executeUpdate("INSERT INTO items (code, name, cost_price, current_selling_price, current_stock) VALUES ('TEST-ROLLBACK', 'Rollback Item', 1.0, 2.0, 10)");
                }
                throw new RuntimeException("Simulated failure inside transaction");
            });
        }).isInstanceOf(RuntimeException.class).hasMessageContaining("Simulated failure");

        // Verify that TEST-ROLLBACK was NOT committed
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM items WHERE code = 'TEST-ROLLBACK'");
             ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isZero();
        }
    }

    @Test
    @DisplayName("Should commit transaction and return result on success")
    void testTransactionCommitOnSuccess() throws SQLException {
        MigrationRunner.migrate(db);

        String insertedCode = db.executeInTransactionWithResult(conn -> {
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("INSERT INTO items (code, name, cost_price, current_selling_price, current_stock) VALUES ('TEST-COMMIT', 'Commit Item', 1.5, 3.0, 15)");
            }
            return "TEST-COMMIT";
        });

        assertThat(insertedCode).isEqualTo("TEST-COMMIT");

        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT current_stock FROM items WHERE code = 'TEST-COMMIT'");
             ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt("current_stock")).isEqualTo(15);
        }
    }

    @Test
    @DisplayName("Should enforce CHECK constraint rejecting negative inventory at DB level")
    void testNegativeStockCheckConstraint() {
        MigrationRunner.migrate(db);

        assertThatThrownBy(() -> {
            db.executeInTransaction(conn -> {
                try (Statement stmt = conn.createStatement()) {
                    stmt.executeUpdate("INSERT INTO items (code, name, cost_price, current_selling_price, current_stock) VALUES ('BAD-STOCK', 'Bad Stock', 1.0, 2.0, -5)");
                }
            });
        }).isInstanceOf(SQLException.class);
    }
}
