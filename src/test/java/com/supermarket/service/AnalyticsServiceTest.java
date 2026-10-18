package com.supermarket.service;

import com.supermarket.domain.AnalyticsReport;
import com.supermarket.domain.ReceiptLineItem;
import com.supermarket.domain.SalesStatistic;
import com.supermarket.domain.SalesTransaction;
import com.supermarket.exception.ValidationException;
import com.supermarket.persistence.DatabaseManager;
import com.supermarket.persistence.MigrationRunner;
import com.supermarket.repository.AnalyticsRepository;
import com.supermarket.repository.SalesTransactionRepository;
import com.supermarket.repository.SqliteAnalyticsRepository;
import com.supermarket.repository.SqliteSalesTransactionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalyticsServiceTest {
    private DatabaseManager db;
    private SalesTransactionRepository salesRepo;
    private AnalyticsRepository analyticsRepo;
    private AnalyticsService analyticsService;

    @BeforeEach
    void setUp() {
        db = DatabaseManager.createInMemory();
        MigrationRunner.migrate(db);

        salesRepo = new SqliteSalesTransactionRepository(db);
        analyticsRepo = new SqliteAnalyticsRepository(db);
        analyticsService = new AnalyticsService(analyticsRepo);
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    private void seedTransaction(String serial, LocalDateTime timestamp, String itemCode, String itemName, int qty, double cost, double price) throws Exception {
        BigDecimal costBd = BigDecimal.valueOf(cost).setScale(2, RoundingMode.HALF_UP);
        BigDecimal priceBd = BigDecimal.valueOf(price).setScale(2, RoundingMode.HALF_UP);
        BigDecimal total = priceBd.multiply(BigDecimal.valueOf(qty)).setScale(2, RoundingMode.HALF_UP);

        SalesTransaction tx = new SalesTransaction(
                null,
                serial,
                total,
                total,
                BigDecimal.ZERO,
                "Cashier-1",
                timestamp
        );

        ReceiptLineItem lineItem = new ReceiptLineItem(
                null,
                null,
                itemCode,
                itemName,
                qty,
                costBd,
                priceBd,
                total
        );

        try (Connection conn = db.getConnection()) {
            salesRepo.createTransaction(conn, tx, List.of(lineItem));
        }
    }

    @Test
    @DisplayName("Single-day filtering strictly isolates sales to the selected 24-hour day")
    void testSingleDayFiltering() throws Exception {
        // Day 1: 2026-10-01 (Revenue: $20.00, Cost: $10.00, Profit: $10.00)
        seedTransaction("TX-DAY1", LocalDateTime.of(2026, 10, 1, 10, 0, 0), "PROD-001", "Bananas", 10, 1.00, 2.00);

        // Day 2: 2026-10-02 (Revenue: $50.00, Cost: $30.00, Profit: $20.00)
        seedTransaction("TX-DAY2", LocalDateTime.of(2026, 10, 2, 14, 30, 0), "DAIRY-001", "Milk", 25, 1.20, 2.00);

        // Day 3: 2026-10-03 (Revenue: $100.00, Cost: $60.00, Profit: $40.00)
        seedTransaction("TX-DAY3", LocalDateTime.of(2026, 10, 3, 16, 0, 0), "BEV-002", "Coffee", 10, 6.00, 10.00);

        // Query ONLY Day 2
        LocalDate day2 = LocalDate.of(2026, 10, 2);
        AnalyticsReport report = analyticsService.generateDailyReport(day2);

        assertThat(report.isSingleDay()).isTrue();
        assertThat(report.totalTransactions()).isEqualTo(1);
        assertThat(report.totalItemsSold()).isEqualTo(25);
        assertThat(report.totalRevenue()).isEqualByComparingTo(BigDecimal.valueOf(50.00));
        assertThat(report.totalCost()).isEqualByComparingTo(BigDecimal.valueOf(30.00));
        assertThat(report.totalNetProfit()).isEqualByComparingTo(BigDecimal.valueOf(20.00));

        // Margin: 20.00 / 50.00 = 40.00%
        assertThat(report.profitMarginPercentage()).isEqualTo(40.00);

        // Exactly 1 item in breakdown (Milk)
        assertThat(report.itemStatistics()).hasSize(1);
        SalesStatistic stat = report.itemStatistics().get(0);
        assertThat(stat.itemCode()).isEqualTo("DAIRY-001");
        assertThat(stat.totalQuantitySold()).isEqualTo(25);
        assertThat(stat.totalRevenue()).isEqualByComparingTo(BigDecimal.valueOf(50.00));
        assertThat(stat.netProfit()).isEqualByComparingTo(BigDecimal.valueOf(20.00));
    }

    @Test
    @DisplayName("Arbitrary date range aggregates across days inclusively")
    void testArbitraryDateRangeFiltering() throws Exception {
        // Day 1: 2026-10-01
        seedTransaction("TX-DAY1", LocalDateTime.of(2026, 10, 1, 10, 0, 0), "PROD-001", "Bananas", 10, 1.00, 2.00);
        // Day 2: 2026-10-02
        seedTransaction("TX-DAY2", LocalDateTime.of(2026, 10, 2, 14, 30, 0), "DAIRY-001", "Milk", 25, 1.20, 2.00);
        // Day 3: 2026-10-03
        seedTransaction("TX-DAY3", LocalDateTime.of(2026, 10, 3, 16, 0, 0), "BEV-002", "Coffee", 10, 6.00, 10.00);

        // Query Day 1 through Day 2 (inclusive) -> excludes Day 3
        LocalDate start = LocalDate.of(2026, 10, 1);
        LocalDate end = LocalDate.of(2026, 10, 2);

        AnalyticsReport report = analyticsService.generateReport(start, end);
        assertThat(report.isSingleDay()).isFalse();
        assertThat(report.totalTransactions()).isEqualTo(2);
        assertThat(report.totalItemsSold()).isEqualTo(35); // 10 + 25
        assertThat(report.totalRevenue()).isEqualByComparingTo(BigDecimal.valueOf(70.00)); // 20 + 50
        assertThat(report.totalCost()).isEqualByComparingTo(BigDecimal.valueOf(40.00));    // 10 + 30
        assertThat(report.totalNetProfit()).isEqualByComparingTo(BigDecimal.valueOf(30.00)); // 10 + 20

        assertThat(report.itemStatistics()).hasSize(2);
    }

    @Test
    @DisplayName("Empty period handling returns clean zero metrics with no exceptions")
    void testEmptyPeriodHandling() {
        LocalDate start = LocalDate.of(2027, 1, 1);
        LocalDate end = LocalDate.of(2027, 1, 31);

        AnalyticsReport report = analyticsService.generateReport(start, end);

        assertThat(report.isEmpty()).isTrue();
        assertThat(report.totalTransactions()).isEqualTo(0);
        assertThat(report.totalItemsSold()).isEqualTo(0);
        assertThat(report.totalRevenue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(report.totalCost()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(report.totalNetProfit()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(report.profitMarginPercentage()).isEqualTo(0.0);
        assertThat(report.itemStatistics()).isEmpty();
    }

    @Test
    @DisplayName("Invalid date intervals or null arguments throw ValidationException")
    void testDateRangeValidation() {
        LocalDate start = LocalDate.of(2026, 10, 10);
        LocalDate end = LocalDate.of(2026, 10, 5); // start is after end

        assertThatThrownBy(() -> analyticsService.generateReport(start, end))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("cannot be after");

        assertThatThrownBy(() -> analyticsService.generateReport(null, end))
                .isInstanceOf(ValidationException.class);

        assertThatThrownBy(() -> analyticsService.generateReport(start, null))
                .isInstanceOf(ValidationException.class);

        assertThatThrownBy(() -> analyticsService.generateDailyReport(null))
                .isInstanceOf(ValidationException.class);
    }
}
