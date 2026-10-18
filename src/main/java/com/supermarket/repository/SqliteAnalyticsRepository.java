package com.supermarket.repository;

import com.supermarket.domain.AnalyticsSummary;
import com.supermarket.domain.SalesStatistic;
import com.supermarket.persistence.DatabaseManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * SQLite implementation of {@link AnalyticsRepository}.
 * Uses immutable transaction-time price snapshots to ensure 100% historical profit invariance.
 */
public class SqliteAnalyticsRepository implements AnalyticsRepository {
    private static final DateTimeFormatter ISO_FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private final DatabaseManager db;

    public SqliteAnalyticsRepository(DatabaseManager db) {
        this.db = Objects.requireNonNull(db, "DatabaseManager cannot be null");
    }

    @Override
    public List<SalesStatistic> getItemStatistics(LocalDateTime start, LocalDateTime end) {
        try (Connection conn = db.getConnection()) {
            return getItemStatistics(conn, start, end);
        } catch (SQLException e) {
            throw new RuntimeException("Error computing item sales statistics", e);
        }
    }

    @Override
    public List<SalesStatistic> getItemStatistics(Connection conn, LocalDateTime start, LocalDateTime end) {
        String sql = """
            SELECT 
                sti.item_code,
                sti.item_name,
                SUM(sti.quantity) AS total_quantity_sold,
                ROUND(SUM(sti.subtotal), 2) AS total_revenue,
                ROUND(SUM(sti.quantity * sti.unit_cost_price_snapshot), 2) AS total_cost,
                ROUND(SUM(sti.subtotal - (sti.quantity * sti.unit_cost_price_snapshot)), 2) AS net_profit
            FROM sales_transaction_items sti
            JOIN sales_transactions st ON sti.transaction_id = st.id
            WHERE st.transaction_timestamp >= ? AND st.transaction_timestamp <= ?
            GROUP BY sti.item_code, sti.item_name
            ORDER BY total_revenue DESC
        """;

        List<SalesStatistic> stats = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, start.format(ISO_FMT));
            ps.setString(2, end.format(ISO_FMT));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String code = rs.getString("item_code");
                    String name = rs.getString("item_name");
                    int quantity = rs.getInt("total_quantity_sold");
                    BigDecimal revenue = BigDecimal.valueOf(rs.getDouble("total_revenue")).setScale(2, RoundingMode.HALF_UP);
                    BigDecimal cost = BigDecimal.valueOf(rs.getDouble("total_cost")).setScale(2, RoundingMode.HALF_UP);
                    BigDecimal profit = BigDecimal.valueOf(rs.getDouble("net_profit")).setScale(2, RoundingMode.HALF_UP);

                    stats.add(new SalesStatistic(code, name, quantity, revenue, cost, profit));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error computing item sales statistics", e);
        }
        return stats;
    }

    @Override
    public AnalyticsSummary getSummary(LocalDateTime start, LocalDateTime end) {
        try (Connection conn = db.getConnection()) {
            return getSummary(conn, start, end);
        } catch (SQLException e) {
            throw new RuntimeException("Error computing analytics summary", e);
        }
    }

    @Override
    public AnalyticsSummary getSummary(Connection conn, LocalDateTime start, LocalDateTime end) {
        String sql = """
            SELECT 
                COUNT(DISTINCT st.id) AS total_transactions,
                COALESCE(SUM(sti.quantity), 0) AS total_items_sold,
                ROUND(COALESCE(SUM(sti.subtotal), 0.0), 2) AS total_revenue,
                ROUND(COALESCE(SUM(sti.quantity * sti.unit_cost_price_snapshot), 0.0), 2) AS total_cost,
                ROUND(COALESCE(SUM(sti.subtotal - (sti.quantity * sti.unit_cost_price_snapshot)), 0.0), 2) AS total_net_profit
            FROM sales_transactions st
            LEFT JOIN sales_transaction_items sti ON st.id = sti.transaction_id
            WHERE st.transaction_timestamp >= ? AND st.transaction_timestamp <= ?
        """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, start.format(ISO_FMT));
            ps.setString(2, end.format(ISO_FMT));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    int txCount = rs.getInt("total_transactions");
                    int itemsSold = rs.getInt("total_items_sold");
                    BigDecimal revenue = BigDecimal.valueOf(rs.getDouble("total_revenue")).setScale(2, RoundingMode.HALF_UP);
                    BigDecimal cost = BigDecimal.valueOf(rs.getDouble("total_cost")).setScale(2, RoundingMode.HALF_UP);
                    BigDecimal profit = BigDecimal.valueOf(rs.getDouble("total_net_profit")).setScale(2, RoundingMode.HALF_UP);

                    return new AnalyticsSummary(txCount, itemsSold, revenue, cost, profit);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error computing analytics summary", e);
        }
        return new AnalyticsSummary(0, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
