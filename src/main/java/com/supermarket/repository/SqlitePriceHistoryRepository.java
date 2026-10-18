package com.supermarket.repository;

import com.supermarket.domain.PriceHistory;
import com.supermarket.persistence.DatabaseManager;

import com.supermarket.persistence.TimestampParser;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class SqlitePriceHistoryRepository implements PriceHistoryRepository {
    private final DatabaseManager db;

    public SqlitePriceHistoryRepository(DatabaseManager db) {
        this.db = Objects.requireNonNull(db, "DatabaseManager cannot be null");
    }

    @Override
    public void logPriceChange(Connection conn, PriceHistory history) {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(history, "PriceHistory cannot be null");

        String sql = "INSERT INTO price_history_log (item_code, old_price, new_price, changed_by, timestamp) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, history.itemCode());
            ps.setDouble(2, history.oldPrice().doubleValue());
            ps.setDouble(3, history.newPrice().doubleValue());
            ps.setString(4, history.changedBy());
            ps.setString(5, history.timestamp().format(TimestampParser.ISO_FMT));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Error logging price change for item: " + history.itemCode(), e);
        }
    }

    @Override
    public void logPriceChange(PriceHistory history) {
        db.getWriteLock().lock();
        try (Connection conn = db.getConnection()) {
            logPriceChange(conn, history);
        } catch (SQLException e) {
            throw new RuntimeException("Error logging price change for item: " + history.itemCode(), e);
        } finally {
            db.getWriteLock().unlock();
        }
    }

    @Override
    public List<PriceHistory> findByItemCode(String code) {
        try (Connection conn = db.getConnection()) {
            return findByItemCode(conn, code);
        } catch (SQLException e) {
            throw new RuntimeException("Error retrieving price history for item: " + code, e);
        }
    }

    @Override
    public List<PriceHistory> findByItemCode(Connection conn, String code) {
        String sql = "SELECT id, item_code, old_price, new_price, changed_by, timestamp FROM price_history_log " +
                "WHERE item_code = ? ORDER BY timestamp DESC, id DESC";
        List<PriceHistory> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error retrieving price history for item: " + code, e);
        }
        return list;
    }

    @Override
    public List<PriceHistory> findAll() {
        try (Connection conn = db.getConnection()) {
            return findAll(conn);
        } catch (SQLException e) {
            throw new RuntimeException("Error retrieving all price histories", e);
        }
    }

    @Override
    public List<PriceHistory> findAll(Connection conn) {
        String sql = "SELECT id, item_code, old_price, new_price, changed_by, timestamp FROM price_history_log ORDER BY timestamp DESC, id DESC";
        List<PriceHistory> list = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                list.add(mapRow(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error retrieving all price histories", e);
        }
        return list;
    }

    private PriceHistory mapRow(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        String itemCode = rs.getString("item_code");
        BigDecimal oldPrice = BigDecimal.valueOf(rs.getDouble("old_price")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal newPrice = BigDecimal.valueOf(rs.getDouble("new_price")).setScale(2, RoundingMode.HALF_UP);
        String changedBy = rs.getString("changed_by");
        LocalDateTime timestamp = TimestampParser.parse(rs.getString("timestamp"));

        return new PriceHistory(id, itemCode, oldPrice, newPrice, changedBy, timestamp);
    }
}
