package com.supermarket.repository;

import com.supermarket.domain.Operator;
import com.supermarket.persistence.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class SqliteOperatorRepository implements OperatorRepository {
    private final DatabaseManager db;

    public SqliteOperatorRepository(DatabaseManager db) {
        this.db = Objects.requireNonNull(db, "DatabaseManager cannot be null");
    }

    @Override
    public Optional<Operator> findByUsername(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        String sql = "SELECT id, username, password_hash, name, role, desk, phone, masked_phone, initials FROM operators WHERE username = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username.trim().toLowerCase());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error querying operator by username: " + username, e);
        }
        return Optional.empty();
    }

    @Override
    public void save(Operator operator) {
        Objects.requireNonNull(operator, "Operator cannot be null");
        String sql = """
            INSERT INTO operators (username, password_hash, name, role, desk, phone, masked_phone, initials)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(username) DO UPDATE SET
                password_hash = excluded.password_hash,
                name = excluded.name,
                role = excluded.role,
                desk = excluded.desk,
                phone = excluded.phone,
                masked_phone = excluded.masked_phone,
                initials = excluded.initials
        """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, operator.username().trim().toLowerCase());
            ps.setString(2, operator.passwordHash());
            ps.setString(3, operator.name());
            ps.setString(4, operator.role());
            ps.setString(5, operator.desk());
            ps.setString(6, operator.phone());
            ps.setString(7, operator.maskedPhone());
            ps.setString(8, operator.initials());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Error saving operator: " + operator.username(), e);
        }
    }

    @Override
    public void updatePasswordHash(long id, String newHash) {
        String sql = "UPDATE operators SET password_hash = ? WHERE id = ?";
        db.getWriteLock().lock();
        try (Connection conn = db.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, newHash);
            stmt.setLong(2, id);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Error updating password hash for operator id: " + id, e);
        } finally {
            db.getWriteLock().unlock();
        }
    }

    @Override
    public List<Operator> findAll() {
        String sql = "SELECT id, username, password_hash, name, role, desk, phone, masked_phone, initials FROM operators ORDER BY name";
        List<Operator> list = new ArrayList<>();
        try (Connection conn = db.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                list.add(mapRow(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error querying all operators", e);
        }
        return list;
    }

    @Override
    public long count() {
        String sql = "SELECT COUNT(*) FROM operators";
        try (Connection conn = db.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            throw new RuntimeException("Error counting operators", e);
        }
    }

    private static Operator mapRow(ResultSet rs) throws SQLException {
        return new Operator(
                rs.getLong("id"),
                rs.getString("username"),
                rs.getString("password_hash"),
                rs.getString("name"),
                rs.getString("role"),
                rs.getString("desk"),
                rs.getString("phone"),
                rs.getString("masked_phone"),
                rs.getString("initials")
        );
    }
}

