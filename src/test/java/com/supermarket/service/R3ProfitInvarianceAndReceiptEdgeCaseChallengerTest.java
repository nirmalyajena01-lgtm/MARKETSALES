package com.supermarket.service;

import com.supermarket.domain.AnalyticsReport;
import com.supermarket.domain.Cart;
import com.supermarket.domain.Item;
import com.supermarket.domain.Receipt;
import com.supermarket.domain.ReceiptLineItem;
import com.supermarket.domain.SalesStatistic;
import com.supermarket.domain.SalesTransaction;
import com.supermarket.persistence.DatabaseManager;
import com.supermarket.persistence.MigrationRunner;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Empirical Challenger 2 Test Suite for Milestone 2:
 * 1. Requirement R3 profit snapshot invariance at the Service layer:
 *    - Multiple checkouts across varied items and categories (Produce, Dairy, Bakery, Beverages, Canned Goods, Household).
 *    - Baseline AnalyticsReport comparison down to the exact cent.
 *    - Selling price updates via PriceService and cost price updates via catalog.
 *    - Post-update AnalyticsReport comparison: exact matching of revenue, cost, net profit, margin, item quantities.
 *    - Additive integrity of post-update sales.
 * 2. ReceiptFormatter edge cases and boundary stress testing:
 *    - Long item descriptions (multi-word, wrapped, ultra-long continuous strings).
 *    - Fractional cents rounding and formatting.
 *    - Large cash amounts and total values.
 *    - Zero change (exact tender).
 *    - Single-item vs multi-item carts.
 *    - Investigation of column width boundaries (>999 price, >999 qty, >9999 total, long cashier names).
 */
class R3ProfitInvarianceAndReceiptEdgeCaseChallengerTest {

    private DatabaseManager db;
    private ItemRepository itemRepo;
    private SalesTransactionRepository salesRepo;
    private AnalyticsRepository analyticsRepo;
    private PriceHistoryRepository priceRepo;
    private RestockAuditRepository restockRepo;

    private BillingService billingService;
    private PriceService priceService;
    private InventoryService inventoryService;
    private AnalyticsService analyticsService;

    @BeforeEach
    void setUp() {
        db = DatabaseManager.createInMemory();
        MigrationRunner.migrate(db);

        itemRepo = new SqliteItemRepository(db);
        salesRepo = new SqliteSalesTransactionRepository(db);
        analyticsRepo = new SqliteAnalyticsRepository(db);
        priceRepo = new SqlitePriceHistoryRepository(db);
        restockRepo = new SqliteRestockAuditRepository(db);

        billingService = new BillingService(db, itemRepo, salesRepo);
        priceService = new PriceService(db, itemRepo, priceRepo);
        inventoryService = new InventoryService(db, itemRepo, restockRepo);
        analyticsService = new AnalyticsService(analyticsRepo);
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    // =========================================================================
    // PART 1: REQUIREMENT R3 PROFIT SNAPSHOT INVARIANCE AT SERVICE LAYER
    // =========================================================================

    @Nested
    @DisplayName("Requirement R3 Profit Snapshot Invariance at Service Layer")
    class R3ProfitSnapshotInvarianceTests {

        @Test
        @DisplayName("R3 Invariance: Multi-item, multi-category checkouts maintain 100% exact cent profit invariance after radical price & cost updates")
        void testMultiCheckoutAcrossCategoriesAndPriceInvarianceDownToExactCent() {
            LocalDate today = LocalDate.now();

            // STEP 1: Perform 4 distinct checkouts across 6 categories (Produce, Dairy, Bakery, Beverages, Canned Goods, Household)
            // Initial seed prices:
            // PROD-001: Cost 0.90, Selling 1.69
            // PROD-002: Cost 1.50, Selling 2.99
            // DAIRY-001: Cost 1.20, Selling 1.89
            // DAIRY-002: Cost 2.40, Selling 3.99
            // BAKE-001: Cost 1.10, Selling 2.25
            // BEV-001: Cost 0.60, Selling 1.29
            // BEV-002: Cost 5.00, Selling 8.99
            // CANN-001: Cost 0.95, Selling 1.89
            // HOUS-001: Cost 1.40, Selling 2.69
            // HOUS-003: Cost 5.50, Selling 9.99

            // Checkout 1: Produce + Dairy + Bakery
            Cart cart1 = billingService.createCart();
            billingService.addItemToCart(cart1, "PROD-001", 5);  // 5 * 1.69 = 8.45, cost = 5 * 0.90 = 4.50
            billingService.addItemToCart(cart1, "DAIRY-001", 3); // 3 * 1.89 = 5.67, cost = 3 * 1.20 = 3.60
            billingService.addItemToCart(cart1, "BAKE-001", 2);  // 2 * 2.25 = 4.50, cost = 2 * 1.10 = 2.20
            // Subtotal = ₹1,545.46, cost = ₹854.90, profit = ₹690.56.
            Receipt r1 = billingService.checkout(cart1, BigDecimal.valueOf(2000.00), "Cashier-Alice");
            assertThat(r1).isNotNull();

            // Checkout 2: Beverages + Canned Goods
            Cart cart2 = billingService.createCart();
            billingService.addItemToCart(cart2, "BEV-001", 10);  // 10 * 1.29 = 12.90, cost = 10 * 0.60 = 6.00
            billingService.addItemToCart(cart2, "BEV-002", 2);   // 2 * 8.99 = 17.98, cost = 2 * 5.00 = 10.00
            billingService.addItemToCart(cart2, "CANN-001", 4);  // 4 * 1.89 = 7.56, cost = 4 * 0.95 = 3.80
            // Subtotal = ₹3,190.52, cost = ₹1,643.40, profit = ₹1,547.12.
            Receipt r2 = billingService.checkout(cart2, BigDecimal.valueOf(4000.00), "Cashier-Bob");
            assertThat(r2).isNotNull();

            // Checkout 3: Household + Produce + Dairy
            Cart cart3 = billingService.createCart();
            billingService.addItemToCart(cart3, "HOUS-001", 3);  // 3 * 2.69 = 8.07, cost = 3 * 1.40 = 4.20
            billingService.addItemToCart(cart3, "PROD-002", 4);  // 4 * 2.99 = 11.96, cost = 4 * 1.50 = 6.00
            billingService.addItemToCart(cart3, "DAIRY-002", 2); // 2 * 3.99 = 7.98, cost = 2 * 2.40 = 4.80
            // Subtotal = ₹2,324.83, cost = ₹1,245.00, profit = ₹1,079.83.
            Receipt r3 = billingService.checkout(cart3, BigDecimal.valueOf(3000.00), "Cashier-Alice");
            assertThat(r3).isNotNull();

            // Checkout 4: Repeat sales on PROD-001, DAIRY-001 + HOUS-003
            Cart cart4 = billingService.createCart();
            billingService.addItemToCart(cart4, "PROD-001", 10); // 10 * 1.69 = 16.90, cost = 10 * 0.90 = 9.00
            billingService.addItemToCart(cart4, "DAIRY-001", 5); // 5 * 1.89 = 9.45, cost = 5 * 1.20 = 6.00
            billingService.addItemToCart(cart4, "HOUS-003", 1);  // 1 * 9.99 = 9.99, cost = 1 * 5.50 = 5.50
            // Subtotal = ₹3,016.22, cost = ₹1,701.50, profit = ₹1,314.72.
            Receipt r4 = billingService.checkout(cart4, BigDecimal.valueOf(4000.00), "Cashier-Charlie");
            assertThat(r4).isNotNull();

            // STEP 2: Generate baseline AnalyticsReport for today
            AnalyticsReport baselineReport = analyticsService.generateDailyReport(today);

            // Compute expected baseline values:
            // Transactions: 4
            // Items sold: (5+3+2) + (10+2+4) + (3+4+2) + (10+5+1) = 10 + 16 + 9 + 16 = 51 items
            // Revenue = ₹10,077.03, cost = ₹5,444.80, profit = ₹4,632.23.
            assertThat(baselineReport.totalTransactions()).isEqualTo(4);
            assertThat(baselineReport.totalItemsSold()).isEqualTo(51);
            assertThat(baselineReport.totalRevenue()).isEqualByComparingTo(new BigDecimal("10077.03"));
            assertThat(baselineReport.totalCost()).isEqualByComparingTo(new BigDecimal("5444.80"));
            assertThat(baselineReport.totalNetProfit()).isEqualByComparingTo(new BigDecimal("4632.23"));
            assertThat(baselineReport.profitMarginPercentage()).isEqualTo(45.97);

            // Verify per-item baseline statistics
            Map<String, SalesStatistic> baselineStatsByCode = baselineReport.itemStatistics().stream()
                    .collect(Collectors.toMap(SalesStatistic::itemCode, s -> s));

            assertThat(baselineStatsByCode).hasSize(10); // 10 distinct items sold

            // Detailed per-item verification:
            // PROD-001: quantity 15, revenue ₹2,104.05, cost ₹1,120.50, profit ₹983.55.
            SalesStatistic statProd1 = baselineStatsByCode.get("PROD-001");
            assertThat(statProd1.totalQuantitySold()).isEqualTo(15);
            assertThat(statProd1.totalRevenue()).isEqualByComparingTo(new BigDecimal("2104.05"));
            assertThat(statProd1.totalCost()).isEqualByComparingTo(new BigDecimal("1120.50"));
            assertThat(statProd1.netProfit()).isEqualByComparingTo(new BigDecimal("983.55"));

            // DAIRY-001: quantity 8, revenue ₹1,254.96, cost ₹796.80, profit ₹458.16.
            SalesStatistic statDairy1 = baselineStatsByCode.get("DAIRY-001");
            assertThat(statDairy1.totalQuantitySold()).isEqualTo(8);
            assertThat(statDairy1.totalRevenue()).isEqualByComparingTo(new BigDecimal("1254.96"));
            assertThat(statDairy1.totalCost()).isEqualByComparingTo(new BigDecimal("796.80"));
            assertThat(statDairy1.netProfit()).isEqualByComparingTo(new BigDecimal("458.16"));

            // STEP 3: RADICAL MUTATIONS VIA PriceService AND CATALOG
            // Update selling prices via PriceService for all items
            priceService.updateSellingPrice("PROD-001", new BigDecimal("9.99"), "Manager-Dave"); // was 1.69 (+491%)
            priceService.updateSellingPrice("DAIRY-001", new BigDecimal("0.50"), "Manager-Dave"); // was 1.89 (-74%)
            priceService.updateSellingPrice("BAKE-001", new BigDecimal("15.00"), "Manager-Dave"); // was 2.25 (+566%)
            priceService.updateSellingPrice("BEV-001", new BigDecimal("25.00"), "Manager-Dave");  // was 1.29 (+1837%)
            priceService.updateSellingPrice("BEV-002", new BigDecimal("99.99"), "Manager-Dave");  // was 8.99 (+1012%)
            priceService.updateSellingPrice("CANN-001", new BigDecimal("1.00"), "Manager-Dave");  // was 1.89 (-47%)
            priceService.updateSellingPrice("HOUS-001", new BigDecimal("19.99"), "Manager-Dave"); // was 2.69 (+643%)
            priceService.updateSellingPrice("PROD-002", new BigDecimal("0.75"), "Manager-Dave");  // was 2.99 (-75%)
            priceService.updateSellingPrice("DAIRY-002", new BigDecimal("50.00"), "Manager-Dave"); // was 3.99 (+1153%)
            priceService.updateSellingPrice("HOUS-003", new BigDecimal("100.00"), "Manager-Dave"); // was 9.99 (+901%)

            // Also update cost prices in the catalog
            updateCatalogCost("PROD-001", new BigDecimal("5.50")); // was 0.90
            updateCatalogCost("DAIRY-001", new BigDecimal("0.25")); // was 1.20
            updateCatalogCost("BAKE-001", new BigDecimal("8.00"));  // was 1.10
            updateCatalogCost("BEV-001", new BigDecimal("12.00"));  // was 0.60
            updateCatalogCost("BEV-002", new BigDecimal("60.00"));  // was 5.00
            updateCatalogCost("CANN-001", new BigDecimal("2.50"));  // was 0.95
            updateCatalogCost("HOUS-001", new BigDecimal("10.00")); // was 1.40
            updateCatalogCost("PROD-002", new BigDecimal("2.00"));  // was 1.50
            updateCatalogCost("DAIRY-002", new BigDecimal("30.00")); // was 2.40
            updateCatalogCost("HOUS-003", new BigDecimal("70.00")); // was 5.50

            // Confirm catalog now reflects radically altered prices
            Item prod1Now = itemRepo.findByCode("PROD-001").orElseThrow();
            assertThat(prod1Now.currentSellingPrice()).isEqualByComparingTo(new BigDecimal("9.99"));
            assertThat(prod1Now.costPrice()).isEqualByComparingTo(new BigDecimal("5.50"));

            Item bev2Now = itemRepo.findByCode("BEV-002").orElseThrow();
            assertThat(bev2Now.currentSellingPrice()).isEqualByComparingTo(new BigDecimal("99.99"));
            assertThat(bev2Now.costPrice()).isEqualByComparingTo(new BigDecimal("60.00"));

            // STEP 4: RE-QUERY POST-UPDATE ANALYTICS REPORT FOR THE SAME PERIOD
            AnalyticsReport postUpdateReport = analyticsService.generateDailyReport(today);

            // REQUIREMENT R3 INVARIANCE ASSERTIONS DOWN TO EXACT CENT:
            assertThat(postUpdateReport.totalTransactions())
                    .as("Total transactions must remain strictly 4")
                    .isEqualTo(4);

            assertThat(postUpdateReport.totalItemsSold())
                    .as("Total items sold must remain strictly 51")
                    .isEqualTo(51);

            assertThat(postUpdateReport.totalRevenue())
                    .as("Historical revenue must remain unchanged after repricing")
                    .isEqualByComparingTo(new BigDecimal("10077.03"));

            assertThat(postUpdateReport.totalCost())
                    .as("Historical cost must remain unchanged after repricing")
                    .isEqualByComparingTo(new BigDecimal("5444.80"));

            assertThat(postUpdateReport.totalNetProfit())
                    .as("Historical profit must remain unchanged after repricing")
                    .isEqualByComparingTo(new BigDecimal("4632.23"));

            assertThat(postUpdateReport.profitMarginPercentage())
                    .as("Total profit margin percentage must remain exact baseline 45.97%")
                    .isEqualTo(45.97);

            // Assert item-level invariance across every single line item
            Map<String, SalesStatistic> postStatsByCode = postUpdateReport.itemStatistics().stream()
                    .collect(Collectors.toMap(SalesStatistic::itemCode, s -> s));

            assertThat(postStatsByCode).hasSameSizeAs(baselineStatsByCode);

            for (Map.Entry<String, SalesStatistic> entry : baselineStatsByCode.entrySet()) {
                String code = entry.getKey();
                SalesStatistic baselineStat = entry.getValue();
                SalesStatistic postStat = postStatsByCode.get(code);

                assertThat(postStat)
                        .as("Item %s must be present in post-update statistics", code)
                        .isNotNull();

                assertThat(postStat.totalQuantitySold())
                        .as("Item %s quantity must remain invariant", code)
                        .isEqualTo(baselineStat.totalQuantitySold());

                assertThat(postStat.totalRevenue())
                        .as("Item %s revenue must remain invariant down to the cent: expected %s, got %s",
                                code, baselineStat.totalRevenue(), postStat.totalRevenue())
                        .isEqualByComparingTo(baselineStat.totalRevenue());

                assertThat(postStat.totalCost())
                        .as("Item %s cost must remain invariant down to the cent: expected %s, got %s",
                                code, baselineStat.totalCost(), postStat.totalCost())
                        .isEqualByComparingTo(baselineStat.totalCost());

                assertThat(postStat.netProfit())
                        .as("Item %s net profit must remain invariant down to the cent: expected %s, got %s",
                                code, baselineStat.netProfit(), postStat.netProfit())
                        .isEqualByComparingTo(baselineStat.netProfit());

                assertThat(postStat.getProfitMarginPercentage())
                        .as("Item %s margin percentage must remain invariant", code)
                        .isEqualTo(baselineStat.getProfitMarginPercentage());
            }

            // STEP 5: Perform a NEW sale after price change and verify strict additive isolation
            // The repriced BEV-002 line totals ₹199.98, with ₹120.00 cost.
            Cart newCart = billingService.createCart();
            billingService.addItemToCart(newCart, "BEV-002", 2);
            Receipt newReceipt = billingService.checkout(newCart, BigDecimal.valueOf(250.00), "Cashier-Alice");
            assertThat(newReceipt.subtotal()).isEqualByComparingTo(new BigDecimal("199.98"));

            AnalyticsReport combinedReport = analyticsService.generateDailyReport(today);
            // Combined:
            // Tx: 4 + 1 = 5
            // Items: 51 + 2 = 53
            // The new sale adds ₹199.98 revenue, ₹120.00 cost, and ₹79.98 profit.
            assertThat(combinedReport.totalTransactions()).isEqualTo(5);
            assertThat(combinedReport.totalItemsSold()).isEqualTo(53);
            assertThat(combinedReport.totalRevenue()).isEqualByComparingTo(new BigDecimal("10277.01"));
            assertThat(combinedReport.totalCost()).isEqualByComparingTo(new BigDecimal("5564.80"));
            assertThat(combinedReport.totalNetProfit()).isEqualByComparingTo(new BigDecimal("4712.21"));

            // Verify BEV-002 specifically:
            // Combined BEV-002: quantity 4, revenue ₹1,692.32, cost ₹950.00, profit ₹742.32.
            SalesStatistic bev2Combined = combinedReport.itemStatistics().stream()
                    .filter(s -> s.itemCode().equals("BEV-002"))
                    .findFirst()
                    .orElseThrow();
            assertThat(bev2Combined.totalQuantitySold()).isEqualTo(4);
            assertThat(bev2Combined.totalRevenue()).isEqualByComparingTo(new BigDecimal("1692.32"));
            assertThat(bev2Combined.totalCost()).isEqualByComparingTo(new BigDecimal("950.00"));
            assertThat(bev2Combined.netProfit()).isEqualByComparingTo(new BigDecimal("742.32"));
        }

        @Test
        @DisplayName("R3 Invariance: Mutating catalog item to negative margin (loss-leader) does not infect historical positive profit")
        void testLossLeaderCatalogMutationInvariance() {
            LocalDate today = LocalDate.now();

            // Item DAIRY-003: cost ₹232.40, selling ₹397.57.
            Cart cart = billingService.createCart();
            billingService.addItemToCart(cart, "DAIRY-003", 5);
            billingService.checkout(cart, BigDecimal.valueOf(2200.00), "Cashier-1");

            AnalyticsReport baseline = analyticsService.generateDailyReport(today);
            assertThat(baseline.totalRevenue()).isEqualByComparingTo(new BigDecimal("1987.85"));
            assertThat(baseline.totalCost()).isEqualByComparingTo(new BigDecimal("1162.00"));
            assertThat(baseline.totalNetProfit()).isEqualByComparingTo(new BigDecimal("825.85"));

            // Mutate item in catalog: selling price slashed to $1.00, cost increased to $10.00 (Loss of $9.00/unit)
            priceService.updateSellingPrice("DAIRY-003", new BigDecimal("1.00"), "Manager");
            updateCatalogCost("DAIRY-003", new BigDecimal("10.00"));

            AnalyticsReport postUpdate = analyticsService.generateDailyReport(today);
            assertThat(postUpdate.totalRevenue()).isEqualByComparingTo(new BigDecimal("1987.85"));
            assertThat(postUpdate.totalCost()).isEqualByComparingTo(new BigDecimal("1162.00"));
            assertThat(postUpdate.totalNetProfit()).isEqualByComparingTo(new BigDecimal("825.85"));
            assertThat(postUpdate.profitMarginPercentage()).isEqualTo(baseline.profitMarginPercentage());
        }

        @Test
        @DisplayName("R3 Invariance: Multiple consecutive price changes log audit trail without corrupting past snapshots")
        void testMultipleConsecutivePriceChangesAuditTrailAndInvariance() {
            LocalDate today = LocalDate.now();

            Cart cart = billingService.createCart();
            billingService.addItemToCart(cart, "BAKE-002", 4);
            billingService.checkout(cart, BigDecimal.valueOf(1000.00), "Cashier-1");

            // Consecutive manager price adjustments
            priceService.updateSellingPrice("BAKE-002", new BigDecimal("3.49"), "Manager-1");
            priceService.updateSellingPrice("BAKE-002", new BigDecimal("4.29"), "Manager-2");
            priceService.updateSellingPrice("BAKE-002", new BigDecimal("2.99"), "Manager-1");

            assertThat(priceService.getPriceHistory("BAKE-002")).hasSize(3);

            AnalyticsReport report = analyticsService.generateDailyReport(today);
            assertThat(report.totalRevenue()).isEqualByComparingTo(new BigDecimal("826.68"));
            assertThat(report.totalCost()).isEqualByComparingTo(new BigDecimal("415.00"));
            assertThat(report.totalNetProfit()).isEqualByComparingTo(new BigDecimal("411.68"));
        }

        private void updateCatalogCost(String code, BigDecimal newCost) {
            Item item = itemRepo.findByCode(code).orElseThrow();
            Item updated = new Item(
                    item.id(), item.code(), item.name(), item.category(),
                    newCost, item.currentSellingPrice(),
                    item.currentStock(), item.minStockThreshold(),
                    item.createdAt(), LocalDateTime.now()
            );
            inventoryService.saveItem(updated);
        }
    }

    // =========================================================================
    // PART 2: EMPIRICAL CHALLENGE OF ReceiptFormatter EDGE CASES
    // =========================================================================

    @Nested
    @DisplayName("ReceiptFormatter Edge Cases and 40-Column Boundary Stress Tests")
    class ReceiptFormatterEdgeCaseTests {

        private final ReceiptFormatter formatter = new ReceiptFormatter();

        @Test
        @DisplayName("ReceiptFormatter: Single-item and multi-item standard receipts stay strictly <= 40 columns")
        void testStandardReceiptsAdhereTo40Columns() {
            // Test 1-item cart
            SalesTransaction tx1 = new SalesTransaction(
                    1L, "SAS-20261002-00001", new BigDecimal("3.49"), new BigDecimal("5.00"),
                    new BigDecimal("1.51"), "Clerk-1", LocalDateTime.now()
            );
            ReceiptLineItem item1 = new ReceiptLineItem(
                    1L, 1L, "PROD-01", "Bananas 1kg", 1,
                    new BigDecimal("1.50"), new BigDecimal("3.49"), new BigDecimal("3.49")
            );
            Receipt r1 = new Receipt(tx1, List.of(item1), new BigDecimal("3.49"), BigDecimal.ZERO);
            assertAllLinesWithinWidth(formatter.formatThermalReceipt(r1), 40);

            // Test multi-item cart (10 distinct items)
            List<ReceiptLineItem> multiItems = new ArrayList<>();
            BigDecimal runningTotal = BigDecimal.ZERO;
            for (int i = 1; i <= 10; i++) {
                BigDecimal price = BigDecimal.valueOf(i * 1.50).setScale(2, RoundingMode.HALF_UP);
                multiItems.add(new ReceiptLineItem(
                        (long) i, 2L, "CODE-" + i, "Product " + i, i,
                        BigDecimal.valueOf(i), price, price.multiply(BigDecimal.valueOf(i))
                ));
                runningTotal = runningTotal.add(price.multiply(BigDecimal.valueOf(i)));
            }
            SalesTransaction tx2 = new SalesTransaction(
                    2L, "SAS-20261002-00002", runningTotal, runningTotal.add(BigDecimal.TEN),
                    BigDecimal.TEN, "Clerk-2", LocalDateTime.now()
            );
            Receipt r2 = new Receipt(tx2, multiItems, runningTotal, BigDecimal.ZERO);
            assertAllLinesWithinWidth(formatter.formatThermalReceipt(r2), 40);
        }

        @Test
        @DisplayName("ReceiptFormatter: Long item descriptions (multi-word and wrapped) wrap cleanly and stay <= 40 columns")
        void testLongItemDescriptionsWrapCleanly() {
            SalesTransaction tx = new SalesTransaction(
                    1L, "SAS-20261002-00010", new BigDecimal("25.99"), new BigDecimal("30.00"),
                    new BigDecimal("4.01"), "Clerk-1", LocalDateTime.now()
            );

            // Descriptions of various lengths: 20 chars, 45 chars, 80 chars, and 120 chars
            ReceiptLineItem itemA = new ReceiptLineItem(
                    1L, 1L, "ITEM-A", "Fresh Organic Strawberries 500g", 1,
                    BigDecimal.ONE, new BigDecimal("4.99"), new BigDecimal("4.99")
            );
            ReceiptLineItem itemB = new ReceiptLineItem(
                    2L, 1L, "ITEM-B", "Artisan Slow-Fermented Sourdough Loaf with Sesame Seeds", 1,
                    BigDecimal.ONE, new BigDecimal("6.50"), new BigDecimal("6.50")
            );
            ReceiptLineItem itemC = new ReceiptLineItem(
                    3L, 1L, "ITEM-C", "Cold Pressed Extra Virgin Single Estate Mediterranean Olive Oil 750ml Bottle Limited Edition", 1,
                    BigDecimal.ONE, new BigDecimal("14.50"), new BigDecimal("14.50")
            );

            Receipt receipt = new Receipt(tx, List.of(itemA, itemB, itemC), new BigDecimal("25.99"), BigDecimal.ZERO);
            String output = formatter.formatThermalReceipt(receipt);

            assertAllLinesWithinWidth(output, 40);

            // Verify continuation lines are indented with two spaces
            assertThat(output).contains("  Strawberries 500g");
            assertThat(output).contains("  ermented Sourdough Loaf with Sesame");
        }

        @Test
        @DisplayName("ReceiptFormatter: Long continuous item description without any spaces stays <= 40 columns")
        void testContinuousStringWithoutSpacesWrapping() {
            SalesTransaction tx = new SalesTransaction(
                    1L, "SAS-20261002-00020", new BigDecimal("9.99"), new BigDecimal("10.00"),
                    new BigDecimal("0.01"), "Clerk-1", LocalDateTime.now()
            );

            // 70 consecutive alphanumeric characters with no spaces
            String noSpaceName = "SUPERLONGITEMDESCRIPTIONWITHOUTANYSPACESINTHEMIDDLEORANYWHERETOCHECKWRAPPING70";
            ReceiptLineItem item = new ReceiptLineItem(
                    1L, 1L, "CODE99", noSpaceName, 1,
                    BigDecimal.ONE, new BigDecimal("9.99"), new BigDecimal("9.99")
            );

            Receipt receipt = new Receipt(tx, List.of(item), new BigDecimal("9.99"), BigDecimal.ZERO);
            String output = formatter.formatThermalReceipt(receipt);

            assertAllLinesWithinWidth(output, 40);
        }

        @Test
        @DisplayName("ReceiptFormatter: Fractional cents in prices, totals, tax, and tendered are formatted cleanly")
        void testFractionalCentsFormatting() {
            SalesTransaction tx = new SalesTransaction(
                    1L, "SAS-20261002-00030", new BigDecimal("11.554"), new BigDecimal("20.001"),
                    new BigDecimal("8.447"), "Clerk-1", LocalDateTime.now()
            );

            ReceiptLineItem item = new ReceiptLineItem(
                    1L, 1L, "FRAC01", "Gasoline 3.45gal", 1,
                    new BigDecimal("2.505"), new BigDecimal("3.333"), new BigDecimal("11.554")
            );

            Receipt receipt = new Receipt(tx, List.of(item), new BigDecimal("10.999"), new BigDecimal("0.555"));
            String output = formatter.formatThermalReceipt(receipt);

            assertAllLinesWithinWidth(output, 40);
            // Verify numbers are rounded to 2 decimal places with the rupee symbol.
            assertThat(output).contains("₹11.55");
            assertThat(output).contains("₹20.00");
            assertThat(output).contains("₹8.45");
            assertThat(output).contains("₹0.56"); // tax
        }

        @Test
        @DisplayName("ReceiptFormatter: Zero change due (exact cash tendered) formats accurately <= 40 columns")
        void testZeroChangeExactPayment() {
            SalesTransaction tx = new SalesTransaction(
                    1L, "SAS-20261002-00040", new BigDecimal("42.50"), new BigDecimal("42.50"),
                    BigDecimal.ZERO, "Clerk-1", LocalDateTime.now()
            );

            ReceiptLineItem item = new ReceiptLineItem(
                    1L, 1L, "EXACT1", "Exact Pay Item", 1,
                    BigDecimal.TEN, new BigDecimal("42.50"), new BigDecimal("42.50")
            );

            Receipt receipt = new Receipt(tx, List.of(item), new BigDecimal("42.50"), BigDecimal.ZERO);
            String output = formatter.formatThermalReceipt(receipt);

            assertAllLinesWithinWidth(output, 40);
            assertThat(output).contains("Cash Tendered:                    ₹42.50");
            assertThat(output).contains("Change Due:                        ₹0.00");
        }

        @Test
        @DisplayName("ReceiptFormatter: Large cash amounts (e.g. $10,000, $100,000, $1,000,000) stay <= 40 columns")
        void testLargeCashAmountsBoundary() {
            // Bill total $5,000.00, Cash tendered $10,000.00, Change $5,000.00
            SalesTransaction tx1 = new SalesTransaction(
                    1L, "SAS-20261002-00050", new BigDecimal("5000.00"), new BigDecimal("10000.00"),
                    new BigDecimal("5000.00"), "Clerk-1", LocalDateTime.now()
            );
            ReceiptLineItem item1 = new ReceiptLineItem(
                    1L, 1L, "BULK01", "Commercial Flour 50kg", 10,
                    new BigDecimal("300.00"), new BigDecimal("500.00"), new BigDecimal("5000.00")
            );
            Receipt r1 = new Receipt(tx1, List.of(item1), new BigDecimal("5000.00"), BigDecimal.ZERO);
            assertAllLinesWithinWidth(formatter.formatThermalReceipt(r1), 40);

            // Extreme cash tendered: $1,000,000.00
            SalesTransaction tx2 = new SalesTransaction(
                    2L, "SAS-20261002-00051", new BigDecimal("150.00"), new BigDecimal("1000000.00"),
                    new BigDecimal("999850.00"), "Clerk-1", LocalDateTime.now()
            );
            ReceiptLineItem item2 = new ReceiptLineItem(
                    2L, 2L, "GIFT01", "Store Gift Card", 1,
                    new BigDecimal("150.00"), new BigDecimal("150.00"), new BigDecimal("150.00")
            );
            Receipt r2 = new Receipt(tx2, List.of(item2), new BigDecimal("150.00"), BigDecimal.ZERO);
            assertAllLinesWithinWidth(formatter.formatThermalReceipt(r2), 40);
        }

        @Test
        @DisplayName("ReceiptFormatter Boundary: Upper boundaries of 3-digit qty, 3-digit unit price, and 4-digit subtotal stay exactly <= 40 columns")
        void testLineItemFormattingNearAndBeyondWidthBoundaries() {
            // In ReceiptFormatter.java line 77:
            // String.format("%-14s %-6s %3d %6.2f %7.2f", nameFirstLine, code, qty, unitPrice, total)
            // Field widths: 14 + 1 + 6 + 1 + 3 + 1 + 6 + 1 + 7 = 40 chars.
            // When unitPrice < 1000.00 (6 chars like '999.99'), total < 10000.00 (7 chars like '9999.99'), qty <= 999:
            ReceiptLineItem boundaryItem = new ReceiptLineItem(
                    1L, 1L, "MAX001", "Max Price Item", 999,
                    new BigDecimal("500.00"), new BigDecimal("999.99"), new BigDecimal("9999.99")
            );
            SalesTransaction tx = new SalesTransaction(
                    1L, "SAS-20261002-00099", new BigDecimal("9999.99"), new BigDecimal("10000.00"),
                    new BigDecimal("0.01"), "Clerk-1", LocalDateTime.now()
            );
            Receipt r = new Receipt(tx, List.of(boundaryItem), new BigDecimal("9999.99"), BigDecimal.ZERO);
            String output = formatter.formatThermalReceipt(r);

            // Under the maximum 3-digit qty, 3-digit unit price, and 4-digit total, line length is exactly 40 chars
            assertAllLinesWithinWidth(output, 40);
        }

        @Test
        @DisplayName("Empirical Challenge: Investigate behavior when item price exceeds $999.99, quantity exceeds 999, or total exceeds $9,999.99")
        void testEmpiricalEdgeCasesBeyondColumnWidths() {
            // Case 1: Item priced at $1,200.00 (e.g., luxury gift hamper or appliance)
            ReceiptLineItem expensiveItem = new ReceiptLineItem(
                    1L, 1L, "LUX001", "Luxury Hamper", 1,
                    new BigDecimal("800.00"), new BigDecimal("1200.00"), new BigDecimal("1200.00")
            );
            SalesTransaction tx = new SalesTransaction(
                    1L, "SAS-20261002-00100", new BigDecimal("1200.00"), new BigDecimal("1500.00"),
                    new BigDecimal("300.00"), "Clerk-1", LocalDateTime.now()
            );
            Receipt r = new Receipt(tx, List.of(expensiveItem), new BigDecimal("1200.00"), BigDecimal.ZERO);
            String output = formatter.formatThermalReceipt(r);

            String[] lines = output.split("\n");
            // Find line item row
            String itemRow = null;
            for (String line : lines) {
                if (line.contains("LUX001")) {
                    itemRow = line;
                    break;
                }
            }
            assertThat(itemRow).isNotNull();

            // Note: Since %6.2f requires 7 chars for 1200.00, the line length becomes 41 chars:
            // "Luxury Hamper  LUX001   1 1200.00 1200.00" -> length = 41
            // This is an empirical boundary finding for items >= $1,000.00.
            int itemRowLength = itemRow.length();
            assertThat(itemRowLength).isEqualTo(41);
        }

        private void assertAllLinesWithinWidth(String receiptText, int maxWidth) {
            assertThat(receiptText).isNotBlank();
            String[] lines = receiptText.split("\n");
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                assertThat(line.length())
                        .as("Line %d exceeds %d characters (length=%d): '%s'", i + 1, maxWidth, line.length(), line)
                        .isLessThanOrEqualTo(maxWidth);
            }
        }
    }
}
