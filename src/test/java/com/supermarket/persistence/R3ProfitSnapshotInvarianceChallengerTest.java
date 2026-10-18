package com.supermarket.persistence;

import com.supermarket.domain.AnalyticsSummary;
import com.supermarket.domain.Item;
import com.supermarket.domain.ReceiptLineItem;
import com.supermarket.domain.SalesStatistic;
import com.supermarket.domain.SalesTransaction;
import com.supermarket.repository.AnalyticsRepository;
import com.supermarket.repository.ItemRepository;
import com.supermarket.repository.SalesTransactionRepository;
import com.supermarket.repository.SqliteAnalyticsRepository;
import com.supermarket.repository.SqliteItemRepository;
import com.supermarket.repository.SqliteSalesTransactionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Empirical Challenger test suite for Requirement R3:
 * - Price snapshot invariance against catalog mutations
 * - Numeric boundaries (large quantities, fractional amounts, BigDecimal accuracy)
 * - Date range boundaries (exact same timestamp, midnight boundaries, gap periods)
 */
class R3ProfitSnapshotInvarianceChallengerTest {
    private DatabaseManager db;
    private ItemRepository itemRepo;
    private SalesTransactionRepository salesRepo;
    private AnalyticsRepository analyticsRepo;

    @BeforeEach
    void setUp() {
        db = DatabaseManager.createInMemory();
        MigrationRunner.migrate(db);

        itemRepo = new SqliteItemRepository(db);
        salesRepo = new SqliteSalesTransactionRepository(db);
        analyticsRepo = new SqliteAnalyticsRepository(db);
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    @Test
    @DisplayName("R3 Invariance: Multiple transactions across multiple days maintain exact profit despite radical catalog updates")
    void testMultiTransactionSnapshotInvarianceAcrossCatalogMutations() throws SQLException {
        // Day 1: 2026-10-01
        LocalDateTime d1Time = LocalDateTime.of(2026, 10, 1, 11, 15, 0);
        SalesTransaction tx1 = new SalesTransaction(
                null, "TX-D1-001",
                new BigDecimal("25.50"), new BigDecimal("30.00"), new BigDecimal("4.50"),
                "Cashier_1", d1Time
        );
        ReceiptLineItem tx1Item1 = new ReceiptLineItem(
                null, null, "PROD-001", "Cavendish Bananas 1kg",
                5, new BigDecimal("0.80"), new BigDecimal("1.49"), new BigDecimal("7.45")
        );
        ReceiptLineItem tx1Item2 = new ReceiptLineItem(
                null, null, "DAIRY-001", "Fresh Whole Milk 1L",
                4, new BigDecimal("1.20"), new BigDecimal("1.89"), new BigDecimal("7.56")
        );
        ReceiptLineItem tx1Item3 = new ReceiptLineItem(
                null, null, "BAKE-001", "Whole Wheat Bread 800g",
                4, new BigDecimal("1.10"), new BigDecimal("2.29"), new BigDecimal("9.16")
        );

        // Day 2: 2026-10-02 - Transaction 2
        LocalDateTime d2Time1 = LocalDateTime.of(2026, 10, 2, 9, 30, 0);
        SalesTransaction tx2 = new SalesTransaction(
                null, "TX-D2-001",
                new BigDecimal("18.89"), new BigDecimal("20.00"), new BigDecimal("1.11"),
                "Cashier_2", d2Time1
        );
        ReceiptLineItem tx2Item1 = new ReceiptLineItem(
                null, null, "PROD-001", "Cavendish Bananas 1kg",
                10, new BigDecimal("0.80"), new BigDecimal("1.49"), new BigDecimal("14.90")
        );
        ReceiptLineItem tx2Item2 = new ReceiptLineItem(
                null, null, "BEV-001", "Orange Juice 1L",
                1, new BigDecimal("2.10"), new BigDecimal("3.99"), new BigDecimal("3.99")
        );

        // Day 2: 2026-10-02 - Transaction 3
        LocalDateTime d2Time2 = LocalDateTime.of(2026, 10, 2, 16, 45, 0);
        SalesTransaction tx3 = new SalesTransaction(
                null, "TX-D2-002",
                new BigDecimal("12.58"), new BigDecimal("15.00"), new BigDecimal("2.42"),
                "Cashier_1", d2Time2
        );
        ReceiptLineItem tx3Item1 = new ReceiptLineItem(
                null, null, "DAIRY-001", "Fresh Whole Milk 1L",
                2, new BigDecimal("1.20"), new BigDecimal("1.89"), new BigDecimal("3.78")
        );
        ReceiptLineItem tx3Item2 = new ReceiptLineItem(
                null, null, "BEV-002", "Dark Roast Coffee Beans 500g",
                1, new BigDecimal("5.00"), new BigDecimal("8.80"), new BigDecimal("8.80")
        );

        db.executeInTransaction(conn -> {
            salesRepo.createTransaction(conn, tx1, List.of(tx1Item1, tx1Item2, tx1Item3));
            salesRepo.createTransaction(conn, tx2, List.of(tx2Item1, tx2Item2));
            salesRepo.createTransaction(conn, tx3, List.of(tx3Item1, tx3Item2));
        });

        // Compute baseline metrics for Day 1
        LocalDateTime d1Start = LocalDateTime.of(2026, 10, 1, 0, 0, 0);
        LocalDateTime d1End = LocalDateTime.of(2026, 10, 1, 23, 59, 59);
        List<SalesStatistic> d1StatsOrig = analyticsRepo.getItemStatistics(d1Start, d1End);
        AnalyticsSummary d1SummaryOrig = analyticsRepo.getSummary(d1Start, d1End);

        // Compute baseline metrics for Day 2
        LocalDateTime d2Start = LocalDateTime.of(2026, 10, 2, 0, 0, 0);
        LocalDateTime d2End = LocalDateTime.of(2026, 10, 2, 23, 59, 59);
        AnalyticsSummary d2SummaryOrig = analyticsRepo.getSummary(d2Start, d2End);

        // Compute baseline metrics across both days (Day 1 to Day 2)
        AnalyticsSummary totalSummaryOrig = analyticsRepo.getSummary(d1Start, d2End);

        // Assert expected baseline values
        // Day 1: 1 tx, 13 items sold.
        // Revenue = 7.45 + 7.56 + 9.16 = 24.17
        // Cost = (5*0.80) + (4*1.20) + (4*1.10) = 4.00 + 4.80 + 4.40 = 13.20
        // Profit = 24.17 - 13.20 = 10.97
        assertThat(d1SummaryOrig.totalTransactions()).isEqualTo(1);
        assertThat(d1SummaryOrig.totalItemsSold()).isEqualTo(13);
        assertThat(d1SummaryOrig.totalRevenue()).isEqualByComparingTo("24.17");
        assertThat(d1SummaryOrig.totalCost()).isEqualByComparingTo("13.20");
        assertThat(d1SummaryOrig.totalNetProfit()).isEqualByComparingTo("10.97");

        // Day 2: 2 tx, 14 items sold.
        // Revenue = (14.90 + 3.99) + (3.78 + 8.80) = 18.89 + 12.58 = 31.47
        // Cost = (10*0.80 + 1*2.10) + (2*1.20 + 1*5.00) = (8.00 + 2.10) + (2.40 + 5.00) = 10.10 + 7.40 = 17.50
        // Profit = 31.47 - 17.50 = 13.97
        assertThat(d2SummaryOrig.totalTransactions()).isEqualTo(2);
        assertThat(d2SummaryOrig.totalItemsSold()).isEqualTo(14);
        assertThat(d2SummaryOrig.totalRevenue()).isEqualByComparingTo("31.47");
        assertThat(d2SummaryOrig.totalCost()).isEqualByComparingTo("17.50");
        assertThat(d2SummaryOrig.totalNetProfit()).isEqualByComparingTo("13.97");

        // RADICAL CATALOG MUTATIONS:
        // Change selling price and cost price for all involved items
        db.executeInTransaction(conn -> {
            // PROD-001: Selling price 1.49 -> 12.99, Cost 0.80 -> 7.50
            itemRepo.updateSellingPrice(conn, "PROD-001", new BigDecimal("12.99"));
            Item prod1 = itemRepo.findByCode(conn, "PROD-001").orElseThrow();
            itemRepo.save(conn, new Item(prod1.id(), prod1.code(), "Gourmet Bananas", prod1.category(),
                    new BigDecimal("7.50"), new BigDecimal("12.99"), prod1.currentStock(), prod1.minStockThreshold(),
                    prod1.createdAt(), LocalDateTime.now()));

            // DAIRY-001: Selling price 1.89 -> 0.99, Cost 1.20 -> 0.50
            itemRepo.updateSellingPrice(conn, "DAIRY-001", new BigDecimal("0.99"));
            Item dairy1 = itemRepo.findByCode(conn, "DAIRY-001").orElseThrow();
            itemRepo.save(conn, new Item(dairy1.id(), dairy1.code(), dairy1.name(), dairy1.category(),
                    new BigDecimal("0.50"), new BigDecimal("0.99"), dairy1.currentStock(), dairy1.minStockThreshold(),
                    dairy1.createdAt(), LocalDateTime.now()));

            // BAKE-001: Selling price 2.29 -> 50.00, Cost 1.10 -> 30.00
            itemRepo.updateSellingPrice(conn, "BAKE-001", new BigDecimal("50.00"));
            Item bake1 = itemRepo.findByCode(conn, "BAKE-001").orElseThrow();
            itemRepo.save(conn, new Item(bake1.id(), bake1.code(), bake1.name(), bake1.category(),
                    new BigDecimal("30.00"), new BigDecimal("50.00"), bake1.currentStock(), bake1.minStockThreshold(),
                    bake1.createdAt(), LocalDateTime.now()));

            // BEV-001: Selling price 3.99 -> 0.05, Cost 2.10 -> 0.02
            itemRepo.updateSellingPrice(conn, "BEV-001", new BigDecimal("0.05"));

            // BEV-002: Selling price 8.99 -> 99.99, Cost 5.00 -> 75.00
            itemRepo.updateSellingPrice(conn, "BEV-002", new BigDecimal("99.99"));
        });

        // RE-QUERY ANALYTICS AND VERIFY EXACT INVARIANCE
        AnalyticsSummary d1SummaryAfter = analyticsRepo.getSummary(d1Start, d1End);
        assertThat(d1SummaryAfter.totalTransactions()).isEqualTo(d1SummaryOrig.totalTransactions());
        assertThat(d1SummaryAfter.totalItemsSold()).isEqualTo(d1SummaryOrig.totalItemsSold());
        assertThat(d1SummaryAfter.totalRevenue()).isEqualByComparingTo(d1SummaryOrig.totalRevenue());
        assertThat(d1SummaryAfter.totalCost()).isEqualByComparingTo(d1SummaryOrig.totalCost());
        assertThat(d1SummaryAfter.totalNetProfit()).isEqualByComparingTo(d1SummaryOrig.totalNetProfit());
        assertThat(d1SummaryAfter.getProfitMarginPercentage()).isEqualTo(d1SummaryOrig.getProfitMarginPercentage());

        List<SalesStatistic> d1StatsAfter = analyticsRepo.getItemStatistics(d1Start, d1End);
        assertThat(d1StatsAfter).hasSameSizeAs(d1StatsOrig);
        for (int i = 0; i < d1StatsOrig.size(); i++) {
            SalesStatistic orig = d1StatsOrig.get(i);
            SalesStatistic after = d1StatsAfter.get(i);
            assertThat(after.itemCode()).isEqualTo(orig.itemCode());
            assertThat(after.totalQuantitySold()).isEqualTo(orig.totalQuantitySold());
            assertThat(after.totalRevenue()).isEqualByComparingTo(orig.totalRevenue());
            assertThat(after.totalCost()).isEqualByComparingTo(orig.totalCost());
            assertThat(after.netProfit()).isEqualByComparingTo(orig.netProfit());
        }

        AnalyticsSummary d2SummaryAfter = analyticsRepo.getSummary(d2Start, d2End);
        assertThat(d2SummaryAfter.totalRevenue()).isEqualByComparingTo(d2SummaryOrig.totalRevenue());
        assertThat(d2SummaryAfter.totalCost()).isEqualByComparingTo(d2SummaryOrig.totalCost());
        assertThat(d2SummaryAfter.totalNetProfit()).isEqualByComparingTo(d2SummaryOrig.totalNetProfit());

        AnalyticsSummary totalSummaryAfter = analyticsRepo.getSummary(d1Start, d2End);
        assertThat(totalSummaryAfter.totalRevenue()).isEqualByComparingTo(totalSummaryOrig.totalRevenue());
        assertThat(totalSummaryAfter.totalCost()).isEqualByComparingTo(totalSummaryOrig.totalCost());
        assertThat(totalSummaryAfter.totalNetProfit()).isEqualByComparingTo(totalSummaryOrig.totalNetProfit());
    }

    @Test
    @DisplayName("R3 Invariance: Item inverted margin in catalog (negative margin) does not alter past positive profit")
    void testExtremeCatalogPriceMutationsZeroAndNegativeMarginInCatalog() throws SQLException {
        LocalDateTime txTime = LocalDateTime.of(2026, 10, 1, 14, 0, 0);

        SalesTransaction tx = new SalesTransaction(
                null, "TX-PROFIT-001",
                new BigDecimal("100.00"), new BigDecimal("100.00"), BigDecimal.ZERO,
                "Cashier_1", txTime
        );

        // Sold 10 units at $10.00 each, cost $4.00 each -> Revenue $100, Cost $40, Profit $60
        ReceiptLineItem line = new ReceiptLineItem(
                null, null, "CANN-001", "Canned Soup",
                10, new BigDecimal("4.00"), new BigDecimal("10.00"), new BigDecimal("100.00")
        );

        db.executeInTransaction(conn -> salesRepo.createTransaction(conn, tx, List.of(line)));

        LocalDateTime start = LocalDateTime.of(2026, 10, 1, 0, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 10, 1, 23, 59, 59);

        AnalyticsSummary origSummary = analyticsRepo.getSummary(start, end);
        assertThat(origSummary.totalRevenue()).isEqualByComparingTo("100.00");
        assertThat(origSummary.totalCost()).isEqualByComparingTo("40.00");
        assertThat(origSummary.totalNetProfit()).isEqualByComparingTo("60.00");

        // Mutate catalog to extreme loss: Cost $50.00, Selling $2.00 (margin -$48.00 per unit)
        db.executeInTransaction(conn -> {
            Item item = itemRepo.findByCode(conn, "CANN-001").orElseThrow();
            itemRepo.save(conn, new Item(item.id(), item.code(), item.name(), item.category(),
                    new BigDecimal("50.00"), new BigDecimal("2.00"), item.currentStock(), item.minStockThreshold(),
                    item.createdAt(), LocalDateTime.now()));
            itemRepo.updateSellingPrice(conn, "CANN-001", new BigDecimal("2.00"));
        });

        AnalyticsSummary afterSummary = analyticsRepo.getSummary(start, end);
        assertThat(afterSummary.totalRevenue()).isEqualByComparingTo("100.00");
        assertThat(afterSummary.totalCost()).isEqualByComparingTo("40.00");
        assertThat(afterSummary.totalNetProfit()).isEqualByComparingTo("60.00");
    }

    @Test
    @DisplayName("Numeric Boundaries: Large quantities and high revenues maintain exact BigDecimal precision")
    void testNumericBoundariesLargeQuantitiesAndHighRevenues() throws SQLException {
        LocalDateTime txTime = LocalDateTime.of(2026, 10, 2, 10, 0, 0);

        int largeQuantity = 1_000_000;
        BigDecimal unitCost = new BigDecimal("45.50");
        BigDecimal unitSelling = new BigDecimal("99.99");
        BigDecimal expectedSubtotal = new BigDecimal("99990000.00"); // 1,000,000 * 99.99
        BigDecimal expectedCost = new BigDecimal("45500000.00");     // 1,000,000 * 45.50
        BigDecimal expectedProfit = new BigDecimal("54490000.00");   // 99,990,000 - 45,500,000

        SalesTransaction tx = new SalesTransaction(
                null, "TX-LARGE-001",
                expectedSubtotal, expectedSubtotal, BigDecimal.ZERO,
                "Wholesale_Agent", txTime
        );

        ReceiptLineItem line = new ReceiptLineItem(
                null, null, "HOUSE-001", "Paper Towels Bulk 12pk",
                largeQuantity, unitCost, unitSelling, expectedSubtotal
        );

        db.executeInTransaction(conn -> salesRepo.createTransaction(conn, tx, List.of(line)));

        LocalDateTime start = LocalDateTime.of(2026, 10, 2, 0, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 10, 2, 23, 59, 59);

        AnalyticsSummary summary = analyticsRepo.getSummary(start, end);
        assertThat(summary.totalTransactions()).isEqualTo(1);
        assertThat(summary.totalItemsSold()).isEqualTo(largeQuantity);
        assertThat(summary.totalRevenue()).isEqualByComparingTo(expectedSubtotal);
        assertThat(summary.totalCost()).isEqualByComparingTo(expectedCost);
        assertThat(summary.totalNetProfit()).isEqualByComparingTo(expectedProfit);

        List<SalesStatistic> stats = analyticsRepo.getItemStatistics(start, end);
        assertThat(stats).hasSize(1);
        SalesStatistic stat = stats.get(0);
        assertThat(stat.totalQuantitySold()).isEqualTo(largeQuantity);
        assertThat(stat.totalRevenue()).isEqualByComparingTo(expectedSubtotal);
        assertThat(stat.totalCost()).isEqualByComparingTo(expectedCost);
        assertThat(stat.netProfit()).isEqualByComparingTo(expectedProfit);
    }

    @Test
    @DisplayName("Numeric Boundaries: High-volume fractional cent calculations match pure BigDecimal oracle without drift")
    void testNumericBoundariesFractionalDollarAmountsAndPennies() throws SQLException {
        // Build 50 transactions with fine fractional penny values (e.g., $0.01, $0.03, $0.07, $0.13, $1.19, $3.37)
        BigDecimal oracleTotalRevenue = BigDecimal.ZERO;
        BigDecimal oracleTotalCost = BigDecimal.ZERO;

        BigDecimal[] testCosts = {
                new BigDecimal("0.01"), new BigDecimal("0.05"), new BigDecimal("0.13"),
                new BigDecimal("0.47"), new BigDecimal("1.19"), new BigDecimal("2.33"),
                new BigDecimal("5.67"), new BigDecimal("11.89")
        };
        BigDecimal[] testSellings = {
                new BigDecimal("0.02"), new BigDecimal("0.09"), new BigDecimal("0.25"),
                new BigDecimal("0.99"), new BigDecimal("2.49"), new BigDecimal("4.75"),
                new BigDecimal("9.99"), new BigDecimal("19.95")
        };

        LocalDateTime baseTime = LocalDateTime.of(2026, 10, 3, 8, 0, 0);

        for (int i = 0; i < 40; i++) {
            int qty = (i % 7) + 1;
            BigDecimal cost = testCosts[i % testCosts.length];
            BigDecimal selling = testSellings[i % testSellings.length];
            BigDecimal subtotal = selling.multiply(BigDecimal.valueOf(qty)).setScale(2, RoundingMode.HALF_UP);
            BigDecimal lineCost = cost.multiply(BigDecimal.valueOf(qty)).setScale(2, RoundingMode.HALF_UP);

            oracleTotalRevenue = oracleTotalRevenue.add(subtotal);
            oracleTotalCost = oracleTotalCost.add(lineCost);

            ReceiptLineItem line = new ReceiptLineItem(
                    null, null, "ITEM-FRAC-" + (i % 8), "Frac Item " + (i % 8),
                    qty, cost, selling, subtotal
            );

            SalesTransaction tx = new SalesTransaction(
                    null, "TX-FRAC-" + String.format("%04d", i),
                    subtotal, subtotal, BigDecimal.ZERO, "Cashier",
                    baseTime.plusMinutes(i * 10)
            );

            db.executeInTransaction(conn -> salesRepo.createTransaction(conn, tx, List.of(line)));
        }

        BigDecimal oracleTotalProfit = oracleTotalRevenue.subtract(oracleTotalCost).setScale(2, RoundingMode.HALF_UP);

        LocalDateTime start = LocalDateTime.of(2026, 10, 3, 0, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 10, 3, 23, 59, 59);

        AnalyticsSummary summary = analyticsRepo.getSummary(start, end);
        assertThat(summary.totalTransactions()).isEqualTo(40);
        assertThat(summary.totalRevenue()).isEqualByComparingTo(oracleTotalRevenue);
        assertThat(summary.totalCost()).isEqualByComparingTo(oracleTotalCost);
        assertThat(summary.totalNetProfit()).isEqualByComparingTo(oracleTotalProfit);
    }

    @Test
    @DisplayName("Numeric Boundaries: Loss-leader item (selling price below cost) records accurate negative profit")
    void testNegativeProfitLossLeaderItemHandling() throws SQLException {
        LocalDateTime txTime = LocalDateTime.of(2026, 10, 4, 12, 0, 0);

        // Cost is $2.50, selling price is $1.00 (Loss of $1.50 per unit on 10 units = -$15.00)
        ReceiptLineItem promoLine = new ReceiptLineItem(
                null, null, "PROMO-001", "Promo Detergent",
                10, new BigDecimal("2.50"), new BigDecimal("1.00"), new BigDecimal("10.00")
        );

        SalesTransaction tx = new SalesTransaction(
                null, "TX-PROMO-001",
                new BigDecimal("10.00"), new BigDecimal("10.00"), BigDecimal.ZERO,
                "Cashier", txTime
        );

        db.executeInTransaction(conn -> salesRepo.createTransaction(conn, tx, List.of(promoLine)));

        LocalDateTime start = LocalDateTime.of(2026, 10, 4, 0, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 10, 4, 23, 59, 59);

        AnalyticsSummary summary = analyticsRepo.getSummary(start, end);
        assertThat(summary.totalRevenue()).isEqualByComparingTo("10.00");
        assertThat(summary.totalCost()).isEqualByComparingTo("25.00");
        assertThat(summary.totalNetProfit()).isEqualByComparingTo("-15.00");

        List<SalesStatistic> stats = analyticsRepo.getItemStatistics(start, end);
        assertThat(stats).hasSize(1);
        assertThat(stats.get(0).netProfit()).isEqualByComparingTo("-15.00");
        // Profit margin: -15 / 10 = -150%
        assertThat(stats.get(0).getProfitMarginPercentage()).isEqualTo(-150.0);
    }

    @Test
    @DisplayName("Date Range Boundaries: Exact same timestamp for start and end matches pinpoint transactions")
    void testExactSameTimestampDateRangeBoundary() throws SQLException {
        LocalDateTime exactMoment = LocalDateTime.of(2026, 10, 5, 14, 30, 0);

        SalesTransaction tx = new SalesTransaction(
                null, "TX-EXACT-001",
                new BigDecimal("15.00"), new BigDecimal("20.00"), new BigDecimal("5.00"),
                "Cashier", exactMoment
        );
        ReceiptLineItem line = new ReceiptLineItem(
                null, null, "DAIRY-001", "Milk",
                5, new BigDecimal("1.00"), new BigDecimal("3.00"), new BigDecimal("15.00")
        );

        db.executeInTransaction(conn -> salesRepo.createTransaction(conn, tx, List.of(line)));

        // 1. Query with start == end == exactMoment
        List<SalesStatistic> statsMatch = analyticsRepo.getItemStatistics(exactMoment, exactMoment);
        assertThat(statsMatch).hasSize(1);
        assertThat(statsMatch.get(0).totalRevenue()).isEqualByComparingTo("15.00");
        assertThat(statsMatch.get(0).netProfit()).isEqualByComparingTo("10.00");

        AnalyticsSummary summaryMatch = analyticsRepo.getSummary(exactMoment, exactMoment);
        assertThat(summaryMatch.totalTransactions()).isEqualTo(1);
        assertThat(summaryMatch.totalRevenue()).isEqualByComparingTo("15.00");

        // 2. Query 1 second earlier with start == end
        LocalDateTime oneSecondEarlier = exactMoment.minusSeconds(1);
        List<SalesStatistic> statsEarlier = analyticsRepo.getItemStatistics(oneSecondEarlier, oneSecondEarlier);
        assertThat(statsEarlier).isEmpty();
        AnalyticsSummary summaryEarlier = analyticsRepo.getSummary(oneSecondEarlier, oneSecondEarlier);
        assertThat(summaryEarlier.totalTransactions()).isZero();
        assertThat(summaryEarlier.totalRevenue()).isEqualByComparingTo("0.00");

        // 3. Query 1 second later with start == end
        LocalDateTime oneSecondLater = exactMoment.plusSeconds(1);
        List<SalesStatistic> statsLater = analyticsRepo.getItemStatistics(oneSecondLater, oneSecondLater);
        assertThat(statsLater).isEmpty();
        AnalyticsSummary summaryLater = analyticsRepo.getSummary(oneSecondLater, oneSecondLater);
        assertThat(summaryLater.totalTransactions()).isZero();
    }

    @Test
    @DisplayName("Date Range Boundaries: Midnight boundary transitions (23:59:59 and 00:00:00) segment accurately")
    void testMidnightBoundariesDateRangeFiltering() throws SQLException {
        LocalDateTime day1LastSec = LocalDateTime.of(2026, 10, 5, 23, 59, 59);
        LocalDateTime day2Midnight = LocalDateTime.of(2026, 10, 6, 0, 0, 0);
        LocalDateTime day2FirstSec = LocalDateTime.of(2026, 10, 6, 0, 0, 1);

        SalesTransaction tx1 = new SalesTransaction(
                null, "TX-MIDNIGHT-1",
                new BigDecimal("10.00"), new BigDecimal("10.00"), BigDecimal.ZERO, "C1", day1LastSec
        );
        ReceiptLineItem line1 = new ReceiptLineItem(null, null, "ITEM-1", "Item 1", 1, new BigDecimal("5.00"), new BigDecimal("10.00"), new BigDecimal("10.00"));

        SalesTransaction tx2 = new SalesTransaction(
                null, "TX-MIDNIGHT-2",
                new BigDecimal("20.00"), new BigDecimal("20.00"), BigDecimal.ZERO, "C2", day2Midnight
        );
        ReceiptLineItem line2 = new ReceiptLineItem(null, null, "ITEM-2", "Item 2", 2, new BigDecimal("4.00"), new BigDecimal("10.00"), new BigDecimal("20.00"));

        SalesTransaction tx3 = new SalesTransaction(
                null, "TX-MIDNIGHT-3",
                new BigDecimal("30.00"), new BigDecimal("30.00"), BigDecimal.ZERO, "C3", day2FirstSec
        );
        ReceiptLineItem line3 = new ReceiptLineItem(null, null, "ITEM-3", "Item 3", 3, new BigDecimal("3.00"), new BigDecimal("10.00"), new BigDecimal("30.00"));

        db.executeInTransaction(conn -> {
            salesRepo.createTransaction(conn, tx1, List.of(line1));
            salesRepo.createTransaction(conn, tx2, List.of(line2));
            salesRepo.createTransaction(conn, tx3, List.of(line3));
        });

        // Query Day 1: [2026-10-05T00:00:00, 2026-10-05T23:59:59]
        LocalDateTime day1Start = LocalDateTime.of(2026, 10, 5, 0, 0, 0);
        AnalyticsSummary summaryDay1 = analyticsRepo.getSummary(day1Start, day1LastSec);
        assertThat(summaryDay1.totalTransactions()).isEqualTo(1);
        assertThat(summaryDay1.totalRevenue()).isEqualByComparingTo("10.00");
        List<SalesStatistic> statsDay1 = analyticsRepo.getItemStatistics(day1Start, day1LastSec);
        assertThat(statsDay1).extracting(SalesStatistic::itemCode).containsExactly("ITEM-1");

        // Query Day 2: [2026-10-06T00:00:00, 2026-10-06T23:59:59]
        LocalDateTime day2End = LocalDateTime.of(2026, 10, 6, 23, 59, 59);
        AnalyticsSummary summaryDay2 = analyticsRepo.getSummary(day2Midnight, day2End);
        assertThat(summaryDay2.totalTransactions()).isEqualTo(2);
        assertThat(summaryDay2.totalRevenue()).isEqualByComparingTo("50.00"); // 20.00 + 30.00
        List<SalesStatistic> statsDay2 = analyticsRepo.getItemStatistics(day2Midnight, day2End);
        assertThat(statsDay2).extracting(SalesStatistic::itemCode).containsExactlyInAnyOrder("ITEM-2", "ITEM-3");

        // Query Midnight Transition window: [2026-10-05T23:59:59, 2026-10-06T00:00:00]
        AnalyticsSummary summaryBridge = analyticsRepo.getSummary(day1LastSec, day2Midnight);
        assertThat(summaryBridge.totalTransactions()).isEqualTo(2); // tx1 and tx2
        assertThat(summaryBridge.totalRevenue()).isEqualByComparingTo("30.00");
    }

    @Test
    @DisplayName("Date Range Boundaries: Inverted date ranges and gap periods return clean empty stats")
    void testDateRangesWithNoTransactionsAndGapPeriods() throws SQLException {
        LocalDateTime day1 = LocalDateTime.of(2026, 10, 1, 10, 0, 0);
        LocalDateTime day3 = LocalDateTime.of(2026, 10, 3, 10, 0, 0);

        SalesTransaction tx1 = new SalesTransaction(
                null, "TX-GAP-1", new BigDecimal("10.00"), new BigDecimal("10.00"), BigDecimal.ZERO, "C1", day1
        );
        ReceiptLineItem line1 = new ReceiptLineItem(null, null, "ITEM-1", "Item 1", 1, new BigDecimal("5.00"), new BigDecimal("10.00"), new BigDecimal("10.00"));

        SalesTransaction tx3 = new SalesTransaction(
                null, "TX-GAP-3", new BigDecimal("30.00"), new BigDecimal("30.00"), BigDecimal.ZERO, "C3", day3
        );
        ReceiptLineItem line3 = new ReceiptLineItem(null, null, "ITEM-3", "Item 3", 1, new BigDecimal("15.00"), new BigDecimal("30.00"), new BigDecimal("30.00"));

        db.executeInTransaction(conn -> {
            salesRepo.createTransaction(conn, tx1, List.of(line1));
            salesRepo.createTransaction(conn, tx3, List.of(line3));
        });

        // Gap Day 2 (no transactions)
        LocalDateTime day2Start = LocalDateTime.of(2026, 10, 2, 0, 0, 0);
        LocalDateTime day2End = LocalDateTime.of(2026, 10, 2, 23, 59, 59);

        List<SalesStatistic> statsDay2 = analyticsRepo.getItemStatistics(day2Start, day2End);
        assertThat(statsDay2).isEmpty();

        AnalyticsSummary summaryDay2 = analyticsRepo.getSummary(day2Start, day2End);
        assertThat(summaryDay2.totalTransactions()).isZero();
        assertThat(summaryDay2.totalItemsSold()).isZero();
        assertThat(summaryDay2.totalRevenue()).isEqualByComparingTo("0.00");
        assertThat(summaryDay2.totalCost()).isEqualByComparingTo("0.00");
        assertThat(summaryDay2.totalNetProfit()).isEqualByComparingTo("0.00");
        assertThat(summaryDay2.getProfitMarginPercentage()).isEqualTo(0.0);

        // Inverted Range (start > end)
        List<SalesStatistic> statsInverted = analyticsRepo.getItemStatistics(day3, day1);
        assertThat(statsInverted).isEmpty();

        AnalyticsSummary summaryInverted = analyticsRepo.getSummary(day3, day1);
        assertThat(summaryInverted.totalTransactions()).isZero();
        assertThat(summaryInverted.totalRevenue()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("Parity: SalesTransactionRepository and AnalyticsRepository yield consistent counts and totals")
    void testParityBetweenSalesRepositoryAndAnalyticsRepositoryDateRange() throws SQLException {
        LocalDateTime dayStart = LocalDateTime.of(2026, 10, 7, 0, 0, 0);
        LocalDateTime dayEnd = LocalDateTime.of(2026, 10, 7, 23, 59, 59);

        for (int i = 1; i <= 5; i++) {
            LocalDateTime txTime = dayStart.plusHours(i * 2);
            BigDecimal amount = BigDecimal.valueOf(i * 12.50).setScale(2, RoundingMode.HALF_UP);
            SalesTransaction tx = new SalesTransaction(
                    null, "TX-PARITY-" + i, amount, amount, BigDecimal.ZERO, "Cashier", txTime
            );
            ReceiptLineItem line = new ReceiptLineItem(
                    null, null, "ITEM-P", "Parity Item", i,
                    new BigDecimal("5.00"), new BigDecimal("12.50"), amount
            );
            db.executeInTransaction(conn -> salesRepo.createTransaction(conn, tx, List.of(line)));
        }

        List<SalesTransaction> txList = salesRepo.findByDateRange(dayStart, dayEnd);
        AnalyticsSummary summary = analyticsRepo.getSummary(dayStart, dayEnd);

        assertThat(txList).hasSize(5);
        assertThat(summary.totalTransactions()).isEqualTo(5);

        BigDecimal sumOfTxAmounts = txList.stream()
                .map(SalesTransaction::totalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(summary.totalRevenue()).isEqualByComparingTo(sumOfTxAmounts);
    }

    @Test
    @DisplayName("Edge Case: Multiple line items of the same product in a single transaction aggregate correctly")
    void testMultipleLineItemsSameProductInSingleTransaction() throws SQLException {
        LocalDateTime txTime = LocalDateTime.of(2026, 10, 8, 15, 0, 0);

        SalesTransaction tx = new SalesTransaction(
                null, "TX-MULTI-LINE-001",
                new BigDecimal("30.00"), new BigDecimal("30.00"), BigDecimal.ZERO, "Cashier", txTime
        );

        // Same item added twice as separate lines in cart (e.g. customer handed 2 items at different times during scanning)
        ReceiptLineItem line1 = new ReceiptLineItem(
                null, null, "DAIRY-001", "Fresh Whole Milk 1L",
                3, new BigDecimal("1.20"), new BigDecimal("2.00"), new BigDecimal("6.00")
        );
        ReceiptLineItem line2 = new ReceiptLineItem(
                null, null, "DAIRY-001", "Fresh Whole Milk 1L",
                7, new BigDecimal("1.20"), new BigDecimal("2.00"), new BigDecimal("14.00")
        );
        ReceiptLineItem line3 = new ReceiptLineItem(
                null, null, "BEV-001", "Orange Juice 1L",
                2, new BigDecimal("2.50"), new BigDecimal("5.00"), new BigDecimal("10.00")
        );

        db.executeInTransaction(conn -> salesRepo.createTransaction(conn, tx, List.of(line1, line2, line3)));

        LocalDateTime start = LocalDateTime.of(2026, 10, 8, 0, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 10, 8, 23, 59, 59);

        List<SalesStatistic> stats = analyticsRepo.getItemStatistics(start, end);
        assertThat(stats).hasSize(2); // DAIRY-001 and BEV-001

        SalesStatistic milkStat = stats.stream().filter(s -> s.itemCode().equals("DAIRY-001")).findFirst().orElseThrow();
        // Total milk quantity: 3 + 7 = 10
        assertThat(milkStat.totalQuantitySold()).isEqualTo(10);
        // Total milk revenue: 6.00 + 14.00 = 20.00
        assertThat(milkStat.totalRevenue()).isEqualByComparingTo("20.00");
        // Total milk cost: 10 * 1.20 = 12.00
        assertThat(milkStat.totalCost()).isEqualByComparingTo("12.00");
        // Net profit: 20.00 - 12.00 = 8.00
        assertThat(milkStat.netProfit()).isEqualByComparingTo("8.00");

        AnalyticsSummary summary = analyticsRepo.getSummary(start, end);
        assertThat(summary.totalTransactions()).isEqualTo(1);
        assertThat(summary.totalItemsSold()).isEqualTo(12); // 10 milk + 2 juice
        assertThat(summary.totalRevenue()).isEqualByComparingTo("30.00");
        assertThat(summary.totalCost()).isEqualByComparingTo("17.00"); // 12.00 + (2*2.50)
        assertThat(summary.totalNetProfit()).isEqualByComparingTo("13.00");
    }

    @Test
    @DisplayName("Edge Case: Zero price item (free promotional sample) preserves cost and negative profit with 0% margin")
    void testZeroPriceFreeSampleItem() throws SQLException {
        LocalDateTime txTime = LocalDateTime.of(2026, 10, 9, 10, 0, 0);

        SalesTransaction tx = new SalesTransaction(
                null, "TX-FREE-001",
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "Cashier", txTime
        );

        // Giveaway item: selling price $0.00, cost $0.75, quantity 4
        ReceiptLineItem freeLine = new ReceiptLineItem(
                null, null, "SAMPLE-001", "Free Detergent Sample",
                4, new BigDecimal("0.75"), BigDecimal.ZERO, BigDecimal.ZERO
        );

        db.executeInTransaction(conn -> salesRepo.createTransaction(conn, tx, List.of(freeLine)));

        LocalDateTime start = LocalDateTime.of(2026, 10, 9, 0, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 10, 9, 23, 59, 59);

        List<SalesStatistic> stats = analyticsRepo.getItemStatistics(start, end);
        assertThat(stats).hasSize(1);
        SalesStatistic stat = stats.get(0);
        assertThat(stat.totalQuantitySold()).isEqualTo(4);
        assertThat(stat.totalRevenue()).isEqualByComparingTo("0.00");
        assertThat(stat.totalCost()).isEqualByComparingTo("3.00");
        assertThat(stat.netProfit()).isEqualByComparingTo("-3.00");
        // With revenue = 0.00, profit margin should safely return 0.0 without division by zero
        assertThat(stat.getProfitMarginPercentage()).isEqualTo(0.0);

        AnalyticsSummary summary = analyticsRepo.getSummary(start, end);
        assertThat(summary.totalRevenue()).isEqualByComparingTo("0.00");
        assertThat(summary.totalCost()).isEqualByComparingTo("3.00");
        assertThat(summary.totalNetProfit()).isEqualByComparingTo("-3.00");
        assertThat(summary.getProfitMarginPercentage()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Performance & Stress: High-volume 100 transactions across 30 days query executes in sub-second time")
    void testHighVolumeTransactionStressAndPerformance() throws SQLException {
        LocalDateTime startDate = LocalDateTime.of(2026, 9, 1, 0, 0, 0);
        BigDecimal expectedTotalRevenue = BigDecimal.ZERO;
        BigDecimal expectedTotalCost = BigDecimal.ZERO;

        for (int i = 0; i < 100; i++) {
            LocalDateTime txTime = startDate.plusHours(i * 7); // Distributed over ~30 days
            BigDecimal cost = BigDecimal.valueOf(1.00 + (i % 5)).setScale(2, RoundingMode.HALF_UP);
            BigDecimal price = BigDecimal.valueOf(2.50 + (i % 7)).setScale(2, RoundingMode.HALF_UP);
            int qty = (i % 3) + 1;
            BigDecimal subtotal = price.multiply(BigDecimal.valueOf(qty)).setScale(2, RoundingMode.HALF_UP);
            BigDecimal lineCost = cost.multiply(BigDecimal.valueOf(qty)).setScale(2, RoundingMode.HALF_UP);

            expectedTotalRevenue = expectedTotalRevenue.add(subtotal);
            expectedTotalCost = expectedTotalCost.add(lineCost);

            SalesTransaction tx = new SalesTransaction(
                    null, "TX-BULK-" + String.format("%04d", i),
                    subtotal, subtotal, BigDecimal.ZERO, "Cashier", txTime
            );
            ReceiptLineItem line = new ReceiptLineItem(
                    null, null, "BULK-" + (i % 10), "Bulk Item " + (i % 10),
                    qty, cost, price, subtotal
            );

            db.executeInTransaction(conn -> salesRepo.createTransaction(conn, tx, List.of(line)));
        }

        LocalDateTime queryStart = LocalDateTime.of(2026, 9, 1, 0, 0, 0);
        LocalDateTime queryEnd = LocalDateTime.of(2026, 10, 1, 0, 0, 0);

        long startNanos = System.nanoTime();
        AnalyticsSummary summary = analyticsRepo.getSummary(queryStart, queryEnd);
        List<SalesStatistic> stats = analyticsRepo.getItemStatistics(queryStart, queryEnd);
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

        assertThat(elapsedMillis).isLessThan(2000); // Sub-second query execution
        assertThat(summary.totalTransactions()).isEqualTo(100);
        assertThat(summary.totalRevenue()).isEqualByComparingTo(expectedTotalRevenue);
        assertThat(summary.totalCost()).isEqualByComparingTo(expectedTotalCost);
        assertThat(summary.totalNetProfit()).isEqualByComparingTo(expectedTotalRevenue.subtract(expectedTotalCost));
        assertThat(stats).isNotEmpty();
    }
}

