package com.supermarket.repository;

import com.supermarket.domain.ReceiptLineItem;
import com.supermarket.domain.SalesTransaction;
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
import java.util.Optional;

public class SqliteSalesTransactionRepository implements SalesTransactionRepository {
    private final DatabaseManager db;

    public SqliteSalesTransactionRepository(DatabaseManager db) {
        this.db = Objects.requireNonNull(db, "DatabaseManager cannot be null");
    }

    @Override
    public void createTransaction(Connection conn, SalesTransaction tx, List<ReceiptLineItem> items) {
        Objects.requireNonNull(conn, "Connection cannot be null");
        Objects.requireNonNull(tx, "SalesTransaction cannot be null");
        Objects.requireNonNull(items, "Items list cannot be null");

        String insertTxSql = "INSERT INTO sales_transactions (serial_number, total_amount, amount_tendered, change_amount, cashier_name, transaction_timestamp) " +
                "VALUES (?, ?, ?, ?, ?, ?)";

        long transactionId;
        try (PreparedStatement ps = conn.prepareStatement(insertTxSql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, tx.serialNumber());
            ps.setDouble(2, tx.totalAmount().doubleValue());
            ps.setDouble(3, tx.amountTendered().doubleValue());
            ps.setDouble(4, tx.changeAmount().doubleValue());
            ps.setString(5, tx.cashierName());
            ps.setString(6, tx.timestamp().format(TimestampParser.ISO_FMT));
            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    transactionId = rs.getLong(1);
                } else {
                    throw new SQLException("Failed to retrieve generated key for sales transaction: " + tx.serialNumber());
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error persisting sales transaction: " + tx.serialNumber(), e);
        }

        String insertItemSql = "INSERT INTO sales_transaction_items (transaction_id, item_code, item_name, quantity, unit_cost_price_snapshot, unit_selling_price_snapshot, subtotal) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(insertItemSql)) {
            for (ReceiptLineItem item : items) {
                ps.setLong(1, transactionId);
                ps.setString(2, item.itemCode());
                ps.setString(3, item.itemName());
                ps.setInt(4, item.quantity());
                ps.setDouble(5, item.unitCostPriceSnapshot().doubleValue());
                ps.setDouble(6, item.unitSellingPriceSnapshot().doubleValue());
                ps.setDouble(7, item.subtotal().doubleValue());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException("Error persisting line items for transaction id: " + transactionId, e);
        }
    }

    @Override
    public Optional<SalesTransaction> findBySerialNumber(String serialNumber) {
        try (Connection conn = db.getConnection()) {
            return findBySerialNumber(conn, serialNumber);
        } catch (SQLException e) {
            throw new RuntimeException("Error finding sales transaction by serial: " + serialNumber, e);
        }
    }

    @Override
    public Optional<SalesTransaction> findBySerialNumber(Connection conn, String serialNumber) {
        String sql = "SELECT id, serial_number, total_amount, amount_tendered, change_amount, cashier_name, transaction_timestamp " +
                "FROM sales_transactions WHERE serial_number = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, serialNumber);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapTransaction(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error finding sales transaction by serial: " + serialNumber, e);
        }
        return Optional.empty();
    }

    @Override
    public Optional<SalesTransaction> findById(long id) {
        try (Connection conn = db.getConnection()) {
            return findById(conn, id);
        } catch (SQLException e) {
            throw new RuntimeException("Error finding sales transaction by id: " + id, e);
        }
    }

    @Override
    public Optional<SalesTransaction> findById(Connection conn, long id) {
        String sql = "SELECT id, serial_number, total_amount, amount_tendered, change_amount, cashier_name, transaction_timestamp " +
                "FROM sales_transactions WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapTransaction(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error finding sales transaction by id: " + id, e);
        }
        return Optional.empty();
    }

    @Override
    public List<SalesTransaction> findByDateRange(LocalDateTime start, LocalDateTime end) {
        try (Connection conn = db.getConnection()) {
            return findByDateRange(conn, start, end);
        } catch (SQLException e) {
            throw new RuntimeException("Error finding sales transactions by date range", e);
        }
    }

    @Override
    public List<SalesTransaction> findByDateRange(Connection conn, LocalDateTime start, LocalDateTime end) {
        String sql = "SELECT id, serial_number, total_amount, amount_tendered, change_amount, cashier_name, transaction_timestamp " +
                "FROM sales_transactions WHERE transaction_timestamp >= ? AND transaction_timestamp <= ? " +
                "ORDER BY transaction_timestamp ASC";
        List<SalesTransaction> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, start.format(TimestampParser.ISO_FMT));
            ps.setString(2, end.format(TimestampParser.ISO_FMT));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapTransaction(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error finding sales transactions by date range", e);
        }
        return list;
    }

    @Override
    public List<ReceiptLineItem> findItemsByTransactionId(long transactionId) {
        try (Connection conn = db.getConnection()) {
            return findItemsByTransactionId(conn, transactionId);
        } catch (SQLException e) {
            throw new RuntimeException("Error finding items for transaction: " + transactionId, e);
        }
    }

    @Override
    public List<ReceiptLineItem> findItemsByTransactionId(Connection conn, long transactionId) {
        String sql = "SELECT id, transaction_id, item_code, item_name, quantity, unit_cost_price_snapshot, unit_selling_price_snapshot, subtotal " +
                "FROM sales_transaction_items WHERE transaction_id = ? ORDER BY id ASC";
        List<ReceiptLineItem> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, transactionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapLineItem(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error finding items for transaction: " + transactionId, e);
        }
        return list;
    }

    private SalesTransaction mapTransaction(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        String serialNumber = rs.getString("serial_number");
        BigDecimal totalAmount = BigDecimal.valueOf(rs.getDouble("total_amount")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal amountTendered = BigDecimal.valueOf(rs.getDouble("amount_tendered")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal changeAmount = BigDecimal.valueOf(rs.getDouble("change_amount")).setScale(2, RoundingMode.HALF_UP);
        String cashierName = rs.getString("cashier_name");
        LocalDateTime timestamp = TimestampParser.parse(rs.getString("transaction_timestamp"));

        return new SalesTransaction(id, serialNumber, totalAmount, amountTendered, changeAmount, cashierName, timestamp);
    }

    private ReceiptLineItem mapLineItem(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        long transactionId = rs.getLong("transaction_id");
        String itemCode = rs.getString("item_code");
        String itemName = rs.getString("item_name");
        int quantity = rs.getInt("quantity");
        BigDecimal costSnapshot = BigDecimal.valueOf(rs.getDouble("unit_cost_price_snapshot")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal sellingSnapshot = BigDecimal.valueOf(rs.getDouble("unit_selling_price_snapshot")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal subtotal = BigDecimal.valueOf(rs.getDouble("subtotal")).setScale(2, RoundingMode.HALF_UP);

        return new ReceiptLineItem(id, transactionId, itemCode, itemName, quantity, costSnapshot, sellingSnapshot, subtotal);
    }
}
