package com.supermarket.persistence;

import com.supermarket.domain.AnalyticsSummary;
import com.supermarket.domain.Item;
import com.supermarket.domain.PriceHistory;
import com.supermarket.domain.ReceiptLineItem;
import com.supermarket.domain.RestockAudit;
import com.supermarket.domain.SalesStatistic;
import com.supermarket.domain.SalesTransaction;
import com.supermarket.repository.AnalyticsRepository;
import com.supermarket.repository.ItemRepository;
import com.supermarket.repository.PriceHistoryRepository;
import com.supermarket.repository.RestockAuditRepository;
import com.supermarket.repository.SalesTransactionRepository;
import com.supermarket.repository.SqliteAnalyticsRepository;
import com.supermarket.repository.SqliteItemRepository;
import com.supermarket.repository.SqlitePriceHistoryRepository;
import com.supermarket.repository.SqliteRestockAuditRepository;
import com.supermarket.repository.SqliteSalesTransactionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EdgeCaseAndConstraintTest {
    private DatabaseManager db;
    private ItemRepository itemRepo;
    private SalesTransactionRepository salesRepo;
    private RestockAuditRepository restockRepo;
    private PriceHistoryRepository priceRepo;
    private AnalyticsRepository analyticsRepo;

    @BeforeEach
    void setUp() {
        db = DatabaseManager.createInMemory();
        MigrationRunner.migrate(db);

        itemRepo = new SqliteItemRepository(db);
        salesRepo = new SqliteSalesTransactionRepository(db);
        restockRepo = new SqliteRestockAuditRepository(db);
        priceRepo = new SqlitePriceHistoryRepository(db);
        analyticsRepo = new SqliteAnalyticsRepository(db);
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    @Test
    @DisplayName("Should verify case-insensitive item code matching via NOCASE collation")
    void testItemCodeNocaseCollation() {
        // PROD-001 is seeded in uppercase. Searching lowercase "prod-001" must match.
        Item itemUpper = itemRepo.findByCode("PROD-001").orElseThrow();
        Item itemLower = itemRepo.findByCode("prod-001").orElseThrow();
        Item itemMixed = itemRepo.findByCode("PrOd-001").orElseThrow();

        assertThat(itemLower.code()).isEqualTo(itemUpper.code());
        assertThat(itemMixed.code()).isEqualTo(itemUpper.code());
    }

    @Test
    @DisplayName("Should reject duplicate item codes with unique constraint violation")
    void testDuplicateItemCodeRejected() {
        // Directly inserting duplicate via raw SQL must fail on UNIQUE
        assertThatThrownBy(() -> {
            db.executeInTransaction(conn -> {
                try (var ps = conn.prepareStatement("INSERT INTO items (code, name, cost_price, current_selling_price, current_stock) VALUES ('PROD-001', 'Dup', 1, 2, 5)")) {
                    ps.executeUpdate();
                }
            });
        }).isInstanceOf(SQLException.class);
    }

    @Test
    @DisplayName("Should validate stock increment, decrement and price updates with invalid arguments")
    void testInvalidArgumentValidation() throws SQLException {
        try (Connection conn = db.getConnection()) {
            assertThatThrownBy(() -> itemRepo.decrementStock(conn, "PROD-001", -1))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> itemRepo.decrementStock(conn, "PROD-001", 0))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> itemRepo.incrementStock(conn, "PROD-001", -5))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> itemRepo.incrementStock(conn, "PROD-001", 0))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> itemRepo.updateSellingPrice(conn, "PROD-001", new BigDecimal("-0.01")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("Should enforce foreign key cascading delete on sales_transaction_items")
    void testForeignKeyCascadingDelete() throws SQLException {
        SalesTransaction tx = new SalesTransaction(
                null,
                "CASCADE-TX-001",
                new BigDecimal("5.00"),
                new BigDecimal("5.00"),
                BigDecimal.ZERO,
                "Clerk",
                LocalDateTime.now()
        );

        ReceiptLineItem line = new ReceiptLineItem(
                null,
                null,
                "PROD-001",
                "Item 1",
                2,
                new BigDecimal("1.00"),
                new BigDecimal("2.50"),
                new BigDecimal("5.00")
        );

        db.executeInTransaction(conn -> {
            salesRepo.createTransaction(conn, tx, List.of(line));
        });

        SalesTransaction saved = salesRepo.findBySerialNumber("CASCADE-TX-001").orElseThrow();
        List<ReceiptLineItem> lines = salesRepo.findItemsByTransactionId(saved.id());
        assertThat(lines).hasSize(1);

        // Delete master sales_transactions record
        db.executeInTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM sales_transactions WHERE id = ?")) {
                ps.setLong(1, saved.id());
                ps.executeUpdate();
            }
        });

        // Verify line items are cascaded and deleted automatically by SQLite FK
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM sales_transaction_items WHERE transaction_id = ?")) {
            ps.setLong(1, saved.id());
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isZero();
            }
        }
    }

    @Test
    @DisplayName("Should maintain chronological order for multiple restock audit logs")
    void testMultipleRestockAuditLogsChronological() throws SQLException {
        LocalDateTime t1 = LocalDateTime.of(2026, 10, 1, 9, 0, 0);
        LocalDateTime t2 = LocalDateTime.of(2026, 10, 1, 14, 0, 0);

        RestockAudit log1 = new RestockAudit(null, "BEV-001", 20, 80, 100, "Morning delivery", "Staff_A", t1);
        RestockAudit log2 = new RestockAudit(null, "BEV-001", 30, 100, 130, "Afternoon delivery", "Staff_B", t2);

        db.executeInTransaction(conn -> {
            restockRepo.logRestock(conn, log1);
            restockRepo.logRestock(conn, log2);
        });

        List<RestockAudit> history = restockRepo.findByItemCode("BEV-001");
        assertThat(history).hasSize(2);
        // Most recent first
        assertThat(history.get(0).quantityAdded()).isEqualTo(30);
        assertThat(history.get(0).performedBy()).isEqualTo("Staff_B");
        assertThat(history.get(1).quantityAdded()).isEqualTo(20);
        assertThat(history.get(1).performedBy()).isEqualTo("Staff_A");
    }

    @Test
    @DisplayName("Should maintain chronological order for multiple price changes")
    void testMultiplePriceChangesChronological() throws SQLException {
        LocalDateTime t1 = LocalDateTime.of(2026, 10, 1, 8, 0, 0);
        LocalDateTime t2 = LocalDateTime.of(2026, 10, 2, 8, 0, 0);

        PriceHistory p1 = new PriceHistory(null, "BEV-005", new BigDecimal("2.19"), new BigDecimal("2.49"), "Manager_X", t1);
        PriceHistory p2 = new PriceHistory(null, "BEV-005", new BigDecimal("2.49"), new BigDecimal("2.79"), "Manager_Y", t2);

        db.executeInTransaction(conn -> {
            priceRepo.logPriceChange(conn, p1);
            priceRepo.logPriceChange(conn, p2);
        });

        List<PriceHistory> history = priceRepo.findByItemCode("BEV-005");
        assertThat(history).hasSize(2);
        // Most recent first
        assertThat(history.get(0).newPrice()).isEqualByComparingTo("2.79");
        assertThat(history.get(1).newPrice()).isEqualByComparingTo("2.49");
    }

    @Test
    @DisplayName("Should accurately filter analytics by date range and exclude outside records")
    void testAnalyticsDateRangeFiltering() throws SQLException {
        LocalDateTime day1 = LocalDateTime.of(2026, 9, 28, 12, 0, 0);
        LocalDateTime day2 = LocalDateTime.of(2026, 10, 2, 12, 0, 0);

        SalesTransaction tx1 = new SalesTransaction(null, "TX-DAY-1", new BigDecimal("10.00"), new BigDecimal("10.00"), BigDecimal.ZERO, "C1", day1);
        ReceiptLineItem line1 = new ReceiptLineItem(null, null, "CANN-001", "Tomato Soup", 5, new BigDecimal("0.95"), new BigDecimal("2.00"), new BigDecimal("10.00"));

        SalesTransaction tx2 = new SalesTransaction(null, "TX-DAY-2", new BigDecimal("20.00"), new BigDecimal("20.00"), BigDecimal.ZERO, "C2", day2);
        ReceiptLineItem line2 = new ReceiptLineItem(null, null, "CANN-002", "Baked Beans", 10, new BigDecimal("0.80"), new BigDecimal("2.00"), new BigDecimal("20.00"));

        db.executeInTransaction(conn -> {
            salesRepo.createTransaction(conn, tx1, List.of(line1));
            salesRepo.createTransaction(conn, tx2, List.of(line2));
        });

        // Query only day 2 (October 2)
        LocalDateTime startOct2 = LocalDateTime.of(2026, 10, 2, 0, 0, 0);
        LocalDateTime endOct2 = LocalDateTime.of(2026, 10, 2, 23, 59, 59);

        List<SalesStatistic> stats = analyticsRepo.getItemStatistics(startOct2, endOct2);
        assertThat(stats).hasSize(1);
        assertThat(stats.get(0).itemCode()).isEqualTo("CANN-002");
        assertThat(stats.get(0).totalQuantitySold()).isEqualTo(10);
        assertThat(stats.get(0).totalRevenue()).isEqualByComparingTo("20.00");

        AnalyticsSummary summary = analyticsRepo.getSummary(startOct2, endOct2);
        assertThat(summary.totalTransactions()).isEqualTo(1);
        assertThat(summary.totalItemsSold()).isEqualTo(10);
        assertThat(summary.totalRevenue()).isEqualByComparingTo("20.00");
    }

    @Test
    @DisplayName("Should return clean empty statistics when no transactions exist in period")
    void testAnalyticsEmptyPeriod() {
        LocalDateTime startEmpty = LocalDateTime.of(2025, 1, 1, 0, 0, 0);
        LocalDateTime endEmpty = LocalDateTime.of(2025, 1, 2, 0, 0, 0);

        List<SalesStatistic> stats = analyticsRepo.getItemStatistics(startEmpty, endEmpty);
        assertThat(stats).isEmpty();

        AnalyticsSummary summary = analyticsRepo.getSummary(startEmpty, endEmpty);
        assertThat(summary.totalTransactions()).isZero();
        assertThat(summary.totalItemsSold()).isZero();
        assertThat(summary.totalRevenue()).isEqualByComparingTo("0.00");
        assertThat(summary.totalCost()).isEqualByComparingTo("0.00");
        assertThat(summary.totalNetProfit()).isEqualByComparingTo("0.00");
        assertThat(summary.getProfitMarginPercentage()).isEqualTo(0.0);
    }
}
