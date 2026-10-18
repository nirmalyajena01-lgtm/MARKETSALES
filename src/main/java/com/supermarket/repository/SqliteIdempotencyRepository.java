package com.supermarket.repository;

import com.supermarket.persistence.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

public class SqliteIdempotencyRepository implements IdempotencyRepository {
    private final DatabaseManager databaseManager;

    public SqliteIdempotencyRepository(DatabaseManager databaseManager) {
        this.databaseManager = Objects.requireNonNull(databaseManager, "DatabaseManager cannot be null");
    }

    @Override
    public Optional<Record> findByKey(String key) {
        if (key == null || key.isBlank()) return Optional.empty();
        String sql = "SELECT idempotency_key, username, payload_fingerprint, receipt_json FROM idempotency_keys WHERE idempotency_key = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, key.trim());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new Record(
                            rs.getString("idempotency_key"),
                            rs.getString("username"),
                            rs.getString("payload_fingerprint"),
                            rs.getString("receipt_json")
                    ));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to lookup idempotency key: " + key, e);
        }
        return Optional.empty();
    }

    @Override
    public void save(String key, String username, String payloadFingerprint, String receiptJson) {
        Objects.requireNonNull(key, "idempotencyKey cannot be null");
        String sql = "INSERT INTO idempotency_keys (idempotency_key, username, payload_fingerprint, receipt_json) VALUES (?, ?, ?, ?)";
        databaseManager.getWriteLock().lock();
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, key.trim());
            stmt.setString(2, username);
            stmt.setString(3, payloadFingerprint);
            stmt.setString(4, receiptJson);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save idempotency key: " + key, e);
        } finally {
            databaseManager.getWriteLock().unlock();
        }
    }

    @Override
    public void purgeOlderThanDays(int days) {
        String sql = "DELETE FROM idempotency_keys WHERE created_at < datetime('now', '-' || ? || ' days')";
        databaseManager.getWriteLock().lock();
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, Math.max(1, days));
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to purge old idempotency keys", e);
        } finally {
            databaseManager.getWriteLock().unlock();
        }
    }
}
