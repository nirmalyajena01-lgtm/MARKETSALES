package com.supermarket.repository;

import com.supermarket.domain.RestockAudit;
import com.supermarket.persistence.DatabaseManager;

import com.supermarket.persistence.TimestampParser;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class SqliteRestockAuditRepository implements RestockAuditRepository {
    private final DatabaseManager db;

    public SqliteRestockAuditRepository(DatabaseManager db) {
        this.db = Objects.requireNonNull(db, "DatabaseManager cannot be null");
    }

    @Override
    public void logRestock(Connection conn, RestockAudit audit) {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(audit, "RestockAudit cannot be null");

        String sql = "INSERT INTO restock_audit_log (item_code, quantity_added, previous_stock, new_stock, batch_notes, performed_by, timestamp) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, audit.itemCode());
            ps.setInt(2, audit.quantityAdded());
            ps.setInt(3, audit.previousStock());
            ps.setInt(4, audit.newStock());
            ps.setString(5, audit.batchNotes());
            ps.setString(6, audit.performedBy());
            ps.setString(7, audit.timestamp().format(TimestampParser.ISO_FMT));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Error inserting restock audit log for item: " + audit.itemCode(), e);
        }
    }

    @Override
    public void logRestock(RestockAudit audit) {
        db.getWriteLock().lock();
        try (Connection conn = db.getConnection()) {
            logRestock(conn, audit);
        } catch (SQLException e) {
            throw new RuntimeException("Error inserting restock audit log for item: " + audit.itemCode(), e);
        } finally {
            db.getWriteLock().unlock();
        }
    }

    @Override
    public List<RestockAudit> findAll() {
        try (Connection conn = db.getConnection()) {
            return findAll(conn);
        } catch (SQLException e) {
            throw new RuntimeException("Error finding all restock logs", e);
        }
    }

    @Override
    public List<RestockAudit> findAll(Connection conn) {
        String sql = "SELECT id, item_code, quantity_added, previous_stock, new_stock, batch_notes, performed_by, timestamp " +
                "FROM restock_audit_log ORDER BY timestamp DESC";
        List<RestockAudit> list = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                list.add(mapRow(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error finding all restock logs", e);
        }
        return list;
    }

    @Override
    public List<RestockAudit> findByItemCode(String code) {
        try (Connection conn = db.getConnection()) {
            return findByItemCode(conn, code);
        } catch (SQLException e) {
            throw new RuntimeException("Error finding restock logs for code: " + code, e);
        }
    }

    @Override
    public List<RestockAudit> findByItemCode(Connection conn, String code) {
        String sql = "SELECT id, item_code, quantity_added, previous_stock, new_stock, batch_notes, performed_by, timestamp " +
                "FROM restock_audit_log WHERE item_code = ? ORDER BY timestamp DESC";
        List<RestockAudit> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error finding restock logs for code: " + code, e);
        }
        return list;
    }

    private RestockAudit mapRow(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        String itemCode = rs.getString("item_code");
        int quantityAdded = rs.getInt("quantity_added");
        int previousStock = rs.getInt("previous_stock");
        int newStock = rs.getInt("new_stock");
        String batchNotes = rs.getString("batch_notes");
        String performedBy = rs.getString("performed_by");
        LocalDateTime timestamp = TimestampParser.parse(rs.getString("timestamp"));

        return new RestockAudit(id, itemCode, quantityAdded, previousStock, newStock, batchNotes, performedBy, timestamp);
    }
}
