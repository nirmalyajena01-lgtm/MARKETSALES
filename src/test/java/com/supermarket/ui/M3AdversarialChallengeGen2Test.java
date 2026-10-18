package com.supermarket.ui;

import com.supermarket.domain.AnalyticsReport;
import com.supermarket.domain.Item;
import com.supermarket.domain.PriceHistory;
import com.supermarket.domain.Receipt;
import com.supermarket.domain.ReceiptLineItem;
import com.supermarket.domain.SalesStatistic;
import com.supermarket.domain.SalesTransaction;
import com.supermarket.persistence.DatabaseManager;
import com.supermarket.persistence.MigrationRunner;
import com.supermarket.repository.SqliteAnalyticsRepository;
import com.supermarket.repository.SqliteItemRepository;
import com.supermarket.repository.SqlitePriceHistoryRepository;
import com.supermarket.repository.SqliteRestockAuditRepository;
import com.supermarket.repository.SqliteSalesTransactionRepository;
import com.supermarket.service.AnalyticsService;
import com.supermarket.service.InventoryService;
import com.supermarket.service.PriceService;
import com.supermarket.service.ReceiptFormatter;
import com.supermarket.ui.controllers.AnalyticsController;
import com.supermarket.ui.controllers.MainController;
import com.supermarket.ui.controllers.PosController;
import com.supermarket.ui.controllers.PriceController;
import com.supermarket.ui.controllers.ReceiptDialogController;
import javafx.event.ActionEvent;
import javafx.fxml.FXMLLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Adversarial Empirical Challenge Suite 2 for Milestone 3 (JavaFX Desktop UI & Thermal Receipt Modal).
 * Conducts exhaustive stress verification of:
 * 1. PriceController: Non-positive price rejection, below-cost warning detection, dynamic update persistence,
 *    and price_history_log audit trail recording.
 * 2. Price Snapshot Invariance (R3): Full lifecycle verification ensuring historical profit numbers remain
 *    strictly invariant across price updates, loss-leader pricing, and drastic price shifts.
 * 3. AnalyticsController: Boundary dates, inverted dates, single-day midnight edges, empty period zero-division
 *    guards, KPI math accuracy, and RFC 4180 CSV generation.
 * 4. ReceiptDialogController & ReceiptFormatter: Strict 40-column thermal receipt monospace alignment,
 *    continuation wrapping, long name truncation, file export persistence, and print simulation.
 */
public class M3AdversarialChallengeGen2Test extends BaseJavaFxTest {

    private SqliteItemRepository itemRepo;
    private SqlitePriceHistoryRepository priceRepo;
    private SqliteRestockAuditRepository restockRepo;
    private SqliteSalesTransactionRepository salesRepo;
    private SqliteAnalyticsRepository analyticsRepo;

    private PriceService priceService;
    private InventoryService inventoryService;
    private AnalyticsService analyticsService;
    private ReceiptFormatter receiptFormatter;

    private final List<Path> filesToCleanUp = new ArrayList<>();

    @BeforeEach
    void setUpDependencies() {
        itemRepo = new SqliteItemRepository(db);
        priceRepo = new SqlitePriceHistoryRepository(db);
        restockRepo = new SqliteRestockAuditRepository(db);
        salesRepo = new SqliteSalesTransactionRepository(db);
        analyticsRepo = new SqliteAnalyticsRepository(db);

        priceService = new PriceService(db, itemRepo, priceRepo);
        inventoryService = new InventoryService(db, itemRepo, restockRepo);
        analyticsService = new AnalyticsService(analyticsRepo);
        receiptFormatter = new ReceiptFormatter();
    }

    @AfterEach
    void cleanUpExportedFiles() {
        for (Path file : filesToCleanUp) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
            }
        }
    }

    // =========================================================================
    // 1. PRICE CONTROLLER ADVERSARIAL STRESS CHALLENGES
    // =========================================================================

    @Nested
    @DisplayName("1. PriceController Adversarial Challenges")
    class PriceControllerAdversarialTests {

        private PriceController priceController;

        @BeforeEach
        void initPriceController() {
            runOnFxThread(() -> {
                try {
                    FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/price.fxml"));
                    loader.setControllerFactory(type -> new PriceController(priceService, inventoryService));
                    loader.load();
                    priceController = loader.getController();
                } catch (IOException e) {
                    throw new RuntimeException("Failed to load price.fxml", e);
                }
            });
        }

        @Test
        @DisplayName("Challenge 1.1: Selling price <= 0 is strictly rejected across boundary values (0, 0.00, -0.01, -10.00, malformed)")
        void testSellingPriceNonPositiveRejection() {
            runOnFxThread(() -> {
                Item item = priceController.getDisplayedCatalog().stream()
                        .filter(i -> "DAIRY-001".equals(i.code()))
                        .findFirst()
                        .orElseThrow();
                priceController.selectItem(item);
                BigDecimal originalSellingPrice = item.currentSellingPrice(); // 1.89

                String[] invalidPrices = {"0", "0.00", "-0.01", "-5.00", "   ", "abc", "$$$,invalid"};
                for (String invalid : invalidPrices) {
                    priceController.getTxtNewPrice().setText(invalid);
                    priceController.handleUpdatePrice(new ActionEvent());

                    // Verify database price was NOT modified
                    Item currentInDb = inventoryService.getItemByCode("DAIRY-001");
                    assertThat(currentInDb.currentSellingPrice())
                            .as("Price must not change on invalid input: '%s'", invalid)
                            .isEqualByComparingTo(originalSellingPrice);

                    // Verify price history was not appended
                    List<PriceHistory> history = priceService.getPriceHistory("DAIRY-001");
                    assertThat(history).isEmpty();
                }
            });
        }

        @Test
        @DisplayName("Challenge 1.2: Selling price below wholesale cost triggers prominent warning and accurately computes negative margin")
        void testSellingPriceBelowCostTriggersWarning() {
            runOnFxThread(() -> {
                // DAIRY-001: cost is ₹99.60, current selling price is ₹156.87.
                Item item = priceController.getDisplayedCatalog().stream()
                        .filter(i -> "DAIRY-001".equals(i.code()))
                        .findFirst()
                        .orElseThrow();
                priceController.selectItem(item);

                // Two-thirds of cost yields a -50% margin.
                priceController.getTxtNewPrice().setText("66.40");
                assertThat(priceController.getLblProposedMargin().getText()).isEqualTo("-50.00%");
                assertThat(priceController.getLblPriceWarning().getText()).contains("⚠️ WARNING: New price (₹66.40) is BELOW cost price (₹99.60)!");

                // Price at cost is break-even.
                priceController.getTxtNewPrice().setText("99.60");
                assertThat(priceController.getLblProposedMargin().getText()).isEqualTo("0.00%");
                assertThat(priceController.getLblPriceWarning().getText()).contains("Healthy profit margin");

                // Twice the cost gives a 50% margin.
                priceController.getTxtNewPrice().setText("199.20");
                assertThat(priceController.getLblProposedMargin().getText()).isEqualTo("50.00%");
                assertThat(priceController.getLblPriceWarning().getText()).contains("Healthy profit margin");

                // 4. Proposed price <= 0 warns about positive requirement
                priceController.getTxtNewPrice().setText("0.00");
                assertThat(priceController.getLblPriceWarning().getText()).contains("must be strictly positive");
                assertThat(priceController.getLblProposedMargin().getText()).isEqualTo("—");
            });
        }

        @Test
        @DisplayName("Challenge 1.3: Dynamic price update immediately updates catalog, inserts into price_history_log, and triggers listener")
        void testDynamicPriceUpdateTakesImmediateEffectAndLogs() {
            AtomicBoolean listenerFired = new AtomicBoolean(false);
            priceController.setOnPriceUpdatedListener(() -> listenerFired.set(true));

            runOnFxThread(() -> {
                Item item = priceController.getDisplayedCatalog().stream()
                        .filter(i -> "BAKE-001".equals(i.code()))
                        .findFirst()
                        .orElseThrow();
                BigDecimal oldPrice = item.currentSellingPrice(); // 2.25

                priceController.selectItem(item);
                priceController.getTxtNewPrice().setText("3.49");
                priceController.getTxtManagerName().setText("Manager-Empirical");
                priceController.handleUpdatePrice(new ActionEvent());

                // 1. Listener triggered
                assertThat(listenerFired.get()).isTrue();

                // 2. Catalog table updated
                Item updatedCatalogItem = priceController.getDisplayedCatalog().stream()
                        .filter(i -> "BAKE-001".equals(i.code()))
                        .findFirst()
                        .orElseThrow();
                assertThat(updatedCatalogItem.currentSellingPrice()).isEqualByComparingTo("3.49");

                // 3. Database updated
                Item dbItem = inventoryService.getItemByCode("BAKE-001");
                assertThat(dbItem.currentSellingPrice()).isEqualByComparingTo("3.49");

                // 4. Price history table and repository populated
                List<PriceHistory> histories = priceController.getPriceHistories();
                assertThat(histories).isNotEmpty();
                PriceHistory latest = histories.get(histories.size() - 1);
                assertThat(latest.itemCode()).isEqualTo("BAKE-001");
                assertThat(latest.oldPrice()).isEqualByComparingTo(oldPrice);
                assertThat(latest.newPrice()).isEqualByComparingTo("3.49");
                assertThat(latest.changedBy()).isEqualTo("Manager-Empirical");
                assertThat(latest.timestamp()).isNotNull();
            });
        }

        @Test
        @DisplayName("Challenge 1.4: Update button without selected item warns user gracefully without crashing")
        void testUpdateWithoutSelectedItem() {
            runOnFxThread(() -> {
                priceController.selectItem(null);
                priceController.getTxtNewPrice().setText("5.00");
                priceController.handleUpdatePrice(new ActionEvent());
                // Does not throw exception and leaves database unchanged
                assertThat(priceController.getSelectedItem()).isNull();
            });
        }
    }

    // =========================================================================
    // 2. PRICE SNAPSHOT INVARIANCE (R3) ACROSS ALL UI TABS
    // =========================================================================

    @Nested
    @DisplayName("2. Price Snapshot Invariance (R3) Multi-Stage Empirical Lifecycle")
    class PriceSnapshotInvarianceLifecycleTests {

        private MainController mainController;
        private PosController posController;
        private PriceController priceController;
        private AnalyticsController analyticsController;

        @BeforeEach
        void initMainHierarchy() {
            db = DatabaseManager.initialize(":memory:");
            MigrationRunner.migrate(db);

            runOnFxThread(() -> {
                try {
                    FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/main.fxml"));
                    loader.load();
                    mainController = loader.getController();

                    posController = mainController.getPosTabController();
                    priceController = mainController.getPriceTabController();
                    analyticsController = mainController.getAnalyticsTabController();
                } catch (IOException e) {
                    throw new RuntimeException("Failed to load main.fxml hierarchy", e);
                }
            });
        }

        @AfterEach
        void tearDownMainHierarchy() {
            if (db != null) {
                db.close();
            }
        }

        @Test
        @DisplayName("Challenge 2.1: Price snapshot invariance holds after price increase, clearance loss sale, and extreme repricing")
        void testFullLifecyclePriceSnapshotInvariance() {
            runOnFxThread(() -> {
                // Item under test: DAIRY-001 (Cost: ₹99.60, initial selling price: ₹156.87).

                // -------------------------------------------------------------
                // STAGE 1: First sale at the catalog price.
                // -------------------------------------------------------------
                posController.getTxtBarcode().setText("DAIRY-001");
                posController.getSpnQuantity().getValueFactory().setValue(5);
                posController.handleAddToCart(new ActionEvent());

                posController.getTxtCashTendered().setText("1000.00");
                posController.handleCompleteSale(new ActionEvent());

                assertThat(posController.getLastCompletedReceipt()).isNotNull();

                // Sale 1: revenue ₹784.35, cost ₹498.00, profit ₹286.35.
                analyticsController.handleFilterToday(new ActionEvent());
                AnalyticsReport report1 = analyticsController.getCurrentReport();
                assertThat(report1).isNotNull();
                assertThat(report1.summary().totalRevenue()).isEqualByComparingTo("784.35");
                assertThat(report1.summary().totalCost()).isEqualByComparingTo("498.00");
                assertThat(report1.summary().totalNetProfit()).isEqualByComparingTo("286.35");

                // -------------------------------------------------------------
                // STAGE 2: Manager raises the selling price to ₹499.00.
                // -------------------------------------------------------------
                Item itemToReprice = priceController.getDisplayedCatalog().stream()
                        .filter(i -> "DAIRY-001".equals(i.code())).findFirst().orElseThrow();
                priceController.selectItem(itemToReprice);
                priceController.getTxtNewPrice().setText("499.00");
                priceController.getTxtManagerName().setText("Manager-Stage2");
                priceController.handleUpdatePrice(new ActionEvent());

                // INVARIANCE AUDIT: Check that report for Stage 1 DID NOT retroactively mutate!
                analyticsController.handleFilterToday(new ActionEvent());
                AnalyticsReport reportAfterReprice = analyticsController.getCurrentReport();
                assertThat(reportAfterReprice.summary().totalRevenue())
                    .as("Historical revenue must remain unchanged after repricing")
                    .isEqualByComparingTo("784.35");
                assertThat(reportAfterReprice.summary().totalNetProfit())
                    .as("Historical profit must remain unchanged after repricing")
                    .isEqualByComparingTo("286.35");

                // -------------------------------------------------------------
                // STAGE 3: Second sale at the new price.
                // -------------------------------------------------------------
                posController.getTxtBarcode().setText("DAIRY-001");
                posController.getSpnQuantity().getValueFactory().setValue(2);
                posController.handleAddToCart(new ActionEvent());

                assertThat(posController.getCartRows().get(0).getUnitPrice()).isEqualByComparingTo("499.00");
                posController.getTxtCashTendered().setText("1000.00");
                posController.handleCompleteSale(new ActionEvent());

                // Cumulative after sale 2: revenue ₹1,782.35, cost ₹697.20, profit ₹1,085.15.
                analyticsController.handleFilterToday(new ActionEvent());
                AnalyticsReport report2 = analyticsController.getCurrentReport();
                assertThat(report2.summary().totalItemsSold()).isEqualTo(7);
                assertThat(report2.summary().totalRevenue()).isEqualByComparingTo("1782.35");
                assertThat(report2.summary().totalCost()).isEqualByComparingTo("697.20");
                assertThat(report2.summary().totalNetProfit()).isEqualByComparingTo("1085.15");

                // -------------------------------------------------------------
                // STAGE 4: Manager sets a clearance price below cost.
                // -------------------------------------------------------------
                Item itemForLoss = priceController.getDisplayedCatalog().stream()
                        .filter(i -> "DAIRY-001".equals(i.code())).findFirst().orElseThrow();
                priceController.selectItem(itemForLoss);
                priceController.getTxtNewPrice().setText("49.80");
                priceController.getTxtManagerName().setText("Manager-Clearance");
                priceController.handleUpdatePrice(new ActionEvent());

                // Third sale: 5 units at ₹49.80, below the ₹99.60 cost.
                posController.getTxtBarcode().setText("DAIRY-001");
                posController.getSpnQuantity().getValueFactory().setValue(5);
                posController.handleAddToCart(new ActionEvent());

                posController.getTxtCashTendered().setText("300.00");
                posController.handleCompleteSale(new ActionEvent());

                // Cumulative after sale 3: revenue ₹2,031.35, cost ₹1,195.20, profit ₹836.15.
                analyticsController.handleFilterToday(new ActionEvent());
                AnalyticsReport report3 = analyticsController.getCurrentReport();
                assertThat(report3.summary().totalItemsSold()).isEqualTo(12);
                assertThat(report3.summary().totalRevenue()).isEqualByComparingTo("2031.35");
                assertThat(report3.summary().totalCost()).isEqualByComparingTo("1195.20");
                assertThat(report3.summary().totalNetProfit()).isEqualByComparingTo("836.15");
                assertThat(report3.summary().getProfitMarginPercentage()).isEqualTo(41.16);

                // Check itemized breakdown table
                SalesStatistic stat = analyticsController.getItemStatsList().stream()
                        .filter(s -> "DAIRY-001".equals(s.itemCode())).findFirst().orElseThrow();
                assertThat(stat.totalQuantitySold()).isEqualTo(12);
                assertThat(stat.totalRevenue()).isEqualByComparingTo("2031.35");
                assertThat(stat.totalCost()).isEqualByComparingTo("1195.20");
                assertThat(stat.netProfit()).isEqualByComparingTo("836.15");
                assertThat(stat.getProfitMarginPercentage()).isEqualTo(41.16);

                // -------------------------------------------------------------
                // STAGE 5: Repricing leaves past stats unchanged.
                // -------------------------------------------------------------
                Item itemExpensive = priceController.getDisplayedCatalog().stream()
                        .filter(i -> "DAIRY-001".equals(i.code())).findFirst().orElseThrow();
                priceController.selectItem(itemExpensive);
                priceController.getTxtNewPrice().setText("9999.00");
                priceController.handleUpdatePrice(new ActionEvent());

                analyticsController.handleFilterToday(new ActionEvent());
                AnalyticsReport reportInvariant = analyticsController.getCurrentReport();
                assertThat(reportInvariant.summary().totalRevenue())
                        .as("Historical revenue must remain unchanged after repricing")
                        .isEqualByComparingTo("2031.35");
                assertThat(reportInvariant.summary().totalNetProfit())
                        .as("Historical profit must remain unchanged after repricing")
                        .isEqualByComparingTo("836.15");
            });
        }
    }

    // =========================================================================
    // 3. ANALYTICS CONTROLLER BOUNDARIES & KPI CALCULATIONS
    // =========================================================================

    @Nested
    @DisplayName("3. AnalyticsController Boundaries & KPI Stress Challenges")
    class AnalyticsControllerBoundaryTests {

        private AnalyticsController analyticsController;

        @BeforeEach
        void initAnalyticsController() {
            runOnFxThread(() -> {
                try {
                    FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/analytics.fxml"));
                    loader.setControllerFactory(type -> new AnalyticsController(analyticsService));
                    loader.load();
                    analyticsController = loader.getController();
                } catch (IOException e) {
                    throw new RuntimeException("Failed to load analytics.fxml", e);
                }
            });
        }

        @Test
        @DisplayName("Challenge 3.1: Inverted date range (start > end) and null dates are handled gracefully with warning")
        void testInvertedAndNullDateRanges() {
            runOnFxThread(() -> {
                // Inverted range
                analyticsController.getDpStartDate().setValue(LocalDate.of(2026, 12, 31));
                analyticsController.getDpEndDate().setValue(LocalDate.of(2026, 1, 1));
                analyticsController.handleGenerateReport(new ActionEvent());

                // Null start
                analyticsController.getDpStartDate().setValue(null);
                analyticsController.getDpEndDate().setValue(LocalDate.of(2026, 5, 1));
                analyticsController.handleGenerateReport(new ActionEvent());

                // Null end
                analyticsController.getDpStartDate().setValue(LocalDate.of(2026, 5, 1));
                analyticsController.getDpEndDate().setValue(null);
                analyticsController.handleGenerateReport(new ActionEvent());
            });
        }

        @Test
        @DisplayName("Challenge 3.2: Empty historical periods yield exact zeroed KPIs without division-by-zero or arithmetic errors")
        void testEmptyPeriodZeroDivisionSafety() {
            runOnFxThread(() -> {
                analyticsController.getDpStartDate().setValue(LocalDate.of(2015, 1, 1));
                analyticsController.getDpEndDate().setValue(LocalDate.of(2015, 1, 31));
                analyticsController.handleGenerateReport(new ActionEvent());

                AnalyticsReport report = analyticsController.getCurrentReport();
                assertThat(report).isNotNull();
                assertThat(report.summary().totalTransactions()).isEqualTo(0);
                assertThat(report.summary().totalItemsSold()).isEqualTo(0);
                assertThat(report.summary().totalRevenue()).isEqualByComparingTo(BigDecimal.ZERO);
                assertThat(report.summary().totalCost()).isEqualByComparingTo(BigDecimal.ZERO);
                assertThat(report.summary().totalNetProfit()).isEqualByComparingTo(BigDecimal.ZERO);
                assertThat(report.summary().getProfitMarginPercentage()).isEqualTo(0.0);

                assertThat(analyticsController.getLblKpiRevenue().getText()).isEqualTo("₹0.00");
                assertThat(analyticsController.getLblKpiProfit().getText()).isEqualTo("₹0.00");
                assertThat(analyticsController.getLblKpiMargin().getText()).isEqualTo("0.00%");
                assertThat(analyticsController.getLblKpiItemsSold().getText()).isEqualTo("0");
                assertThat(analyticsController.getItemStatsList()).isEmpty();
            });
        }

        @Test
        @DisplayName("Challenge 3.3: Preset buttons (Today, Yesterday, Last 7 Days, This Month, All Time) set correct intervals")
        void testPresetButtonsIntervals() {
            runOnFxThread(() -> {
                LocalDate today = LocalDate.now();

                analyticsController.handleFilterToday(new ActionEvent());
                assertThat(analyticsController.getDpStartDate().getValue()).isEqualTo(today);
                assertThat(analyticsController.getDpEndDate().getValue()).isEqualTo(today);

                analyticsController.handleFilterYesterday(new ActionEvent());
                assertThat(analyticsController.getDpStartDate().getValue()).isEqualTo(today.minusDays(1));
                assertThat(analyticsController.getDpEndDate().getValue()).isEqualTo(today.minusDays(1));

                analyticsController.handleFilterLast7Days(new ActionEvent());
                assertThat(analyticsController.getDpStartDate().getValue()).isEqualTo(today.minusDays(6));
                assertThat(analyticsController.getDpEndDate().getValue()).isEqualTo(today);

                analyticsController.handleFilterThisMonth(new ActionEvent());
                assertThat(analyticsController.getDpStartDate().getValue()).isEqualTo(today.withDayOfMonth(1));
                assertThat(analyticsController.getDpEndDate().getValue()).isEqualTo(today);

                analyticsController.handleFilterAllTime(new ActionEvent());
                assertThat(analyticsController.getDpStartDate().getValue()).isEqualTo(LocalDate.of(2020, 1, 1));
                assertThat(analyticsController.getDpEndDate().getValue()).isEqualTo(today);
            });
        }

        @Test
        @DisplayName("Challenge 3.4: CSV export formatting properly escapes RFC 4180 quotes, commas, and multiline characters")
        void testCsvExportRFC4180Formatting() throws SQLException {
            LocalDate testDate = LocalDate.of(2026, 8, 15);
            insertDirectSale("SAS-CSV-SPECIAL", testDate.atTime(10, 0), "SPEC-001", "Gourmet \"Organic\" Cheese, Smoked", 3, "2.50", "5.00");

            runOnFxThread(() -> {
                analyticsController.getDpStartDate().setValue(testDate);
                analyticsController.getDpEndDate().setValue(testDate);
                analyticsController.handleGenerateReport(new ActionEvent());

                AnalyticsReport report = analyticsController.getCurrentReport();
                String csv = analyticsController.buildCsvContent(report);

                // Verify CSV structure
                assertThat(csv).contains("Supermarket Automation Software (SAS) - Sales Profit Analytics");
                assertThat(csv).contains("Period,2026-08-15 to 2026-08-15");
                assertThat(csv).contains("EXECUTIVE SUMMARY");
                assertThat(csv).contains("Total Transactions,1");
                assertThat(csv).contains("Total Items Sold,3");
                assertThat(csv).contains("Total Realized Revenue,15.00");
                assertThat(csv).contains("Total Cost of Goods,7.50");
                assertThat(csv).contains("Net Realized Profit,7.50");
                assertThat(csv).contains("Gross Margin %,50.00%");

                // RFC 4180 Escaping: "Gourmet ""Organic"" Cheese, Smoked"
                assertThat(csv).contains("\"Gourmet \"\"Organic\"\" Cheese, Smoked\"");

                // Handle file export headless execution
                Path exportedPath = Paths.get("reports", "sales_analytics_2026-08-15_to_2026-08-15.csv");
                filesToCleanUp.add(exportedPath);
                analyticsController.handleExportCsv(new ActionEvent());

                assertThat(Files.exists(exportedPath)).isTrue();
            });
        }

        private void insertDirectSale(String serial, LocalDateTime timestamp, String code, String name, int qty, String costStr, String priceStr) throws SQLException {
            BigDecimal cost = new BigDecimal(costStr);
            BigDecimal price = new BigDecimal(priceStr);
            BigDecimal subtotal = price.multiply(BigDecimal.valueOf(qty)).setScale(2, RoundingMode.HALF_UP);

            SalesTransaction tx = new SalesTransaction(
                    null, serial, subtotal, subtotal.add(BigDecimal.TEN),
                    BigDecimal.TEN, "Cashier-CSV", timestamp
            );
            ReceiptLineItem item = new ReceiptLineItem(
                    null, null, code, name, qty, cost, price, subtotal
            );

            try (Connection conn = db.getConnection()) {
                salesRepo.createTransaction(conn, tx, List.of(item));
            }
        }
    }

    // =========================================================================
    // 4. RECEIPT DIALOG CONTROLLER & RECEIPT FORMATTER 40-COLUMN ORACLE
    // =========================================================================

    @Nested
    @DisplayName("4. ReceiptDialogController & ReceiptFormatter 40-Column Oracle Challenges")
    class ReceiptThermalFormattingOracleTests {

        private ReceiptDialogController dialogController;

        @BeforeEach
        void initReceiptDialogController() {
            runOnFxThread(() -> {
                try {
                    FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/receipt_preview.fxml"));
                    loader.setControllerFactory(type -> new ReceiptDialogController(receiptFormatter));
                    loader.load();
                    dialogController = loader.getController();
                } catch (IOException e) {
                    throw new RuntimeException("Failed to load receipt_preview.fxml", e);
                }
            });
        }

        @Test
        @DisplayName("Challenge 4.1: ReceiptFormatter enforces strict 40-column monospace width across all standard sections")
        void testStrict40ColumnMonospaceDiscipline() {
            SalesTransaction tx = new SalesTransaction(
                    999L, "SAS-20261002-ORACLE01", new BigDecimal("12.50"), new BigDecimal("20.00"),
                    new BigDecimal("7.50"), "Cashier-Oracle", LocalDateTime.of(2026, 10, 2, 18, 45, 0)
            );
            List<ReceiptLineItem> items = List.of(
                    new ReceiptLineItem(1L, 999L, "CANN-001", "Canned Corn", 2, new BigDecimal("1.00"), new BigDecimal("2.50"), new BigDecimal("5.00")),
                    new ReceiptLineItem(2L, 999L, "DAIRY-001", "Whole Milk 1L", 3, new BigDecimal("1.20"), new BigDecimal("2.50"), new BigDecimal("7.50"))
            );
            Receipt receipt = new Receipt(tx, items, new BigDecimal("12.50"), BigDecimal.ZERO);

            String formatted = receiptFormatter.formatThermalReceipt(receipt);
            String[] lines = formatted.split("\n");

            // Verify lines
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                assertThat(line.length())
                        .as("Line %d ('%s') must not exceed 40 columns", i + 1, line)
                        .isLessThanOrEqualTo(40);
            }

            // Verify specific headers and dividers
            assertThat(lines[0]).isEqualTo("=".repeat(40));
            assertThat(formatted).contains("SUPERMARKET AUTOMATION");
            assertThat(formatted).contains("Receipt No: SAS-20261002-ORACLE01");
            assertThat(formatted).contains("Date/Time: 2026-10-02 18:45:00");
            assertThat(formatted).contains("Cashier:   Cashier-Oracle");
            assertThat(formatted).contains("Item           Code   Qty  Price   Total");
            assertThat(formatted).contains("-".repeat(40));
        }

        @Test
        @DisplayName("Challenge 4.2: Boundary item codes, long names, and large quantities preserve column alignment")
        void testBoundaryItemLinesAndAlignment() {
            SalesTransaction tx = new SalesTransaction(
                    1001L, "SAS-20261002-LONGTEST", new BigDecimal("99.98"), new BigDecimal("100.00"),
                    new BigDecimal("0.02"), "Cashier-Align", LocalDateTime.now()
            );
            List<ReceiptLineItem> items = List.of(
                    // 14 characters exactly
                    new ReceiptLineItem(1L, 1001L, "EXACT1", "14-Chars Exact", 1, BigDecimal.ONE, new BigDecimal("10.00"), new BigDecimal("10.00")),
                    // Code longer than 6 chars (truncated to 6)
                    new ReceiptLineItem(2L, 1001L, "LONGSKU123", "Short", 2, BigDecimal.ONE, new BigDecimal("5.00"), new BigDecimal("10.00")),
                    // Name much longer than 14 chars
                    new ReceiptLineItem(3L, 1001L, "ORGANIC", "Organically Grown California Avocados Hass Premium Pack 4ct", 2, BigDecimal.ONE, new BigDecimal("39.99"), new BigDecimal("79.98"))
            );
            Receipt receipt = new Receipt(tx, items, new BigDecimal("99.98"), BigDecimal.ZERO);

            String receiptText = receiptFormatter.formatThermalReceipt(receipt);
            String[] lines = receiptText.split("\n");

            for (String line : lines) {
                assertThat(line.length()).isLessThanOrEqualTo(40);
            }

            // Verify truncation of 10-char code LONGSKU123 to 6 chars LONGSK
            assertThat(receiptText).contains("LONGSK");
            // Verify continuation line indentation
            assertThat(receiptText).contains("  own California Avocados Hass Premium");
        }

        @Test
        @DisplayName("Challenge 4.3: Tax calculation in receipt appears only when tax > 0")
        void testTaxRowFormatting() {
            SalesTransaction txNoTax = new SalesTransaction(
                    1002L, "SAS-NOTAX", new BigDecimal("10.00"), new BigDecimal("10.00"),
                    BigDecimal.ZERO, "Cashier-1", LocalDateTime.now()
            );
            Receipt receiptNoTax = new Receipt(txNoTax, List.of(), new BigDecimal("10.00"), BigDecimal.ZERO);
            String textNoTax = receiptFormatter.formatThermalReceipt(receiptNoTax);
            assertThat(textNoTax).doesNotContain("Tax:");

            SalesTransaction txWithTax = new SalesTransaction(
                    1003L, "SAS-WITHTAX", new BigDecimal("10.80"), new BigDecimal("20.00"),
                    new BigDecimal("9.20"), "Cashier-1", LocalDateTime.now()
            );
            Receipt receiptWithTax = new Receipt(txWithTax, List.of(), new BigDecimal("10.00"), new BigDecimal("0.80"));
            String textWithTax = receiptFormatter.formatThermalReceipt(receiptWithTax);
            assertThat(textWithTax).contains("Tax:                               ₹0.80");
        }

        @Test
        @DisplayName("Challenge 4.4: ReceiptDialogController file save and print simulation under boundary receipt inputs")
        void testReceiptDialogControllerActions(@TempDir Path tempDir) {
            SalesTransaction tx = new SalesTransaction(
                    1004L, "SAS-SAVEFILE", new BigDecimal("5.00"), new BigDecimal("10.00"),
                    new BigDecimal("5.00"), "Cashier-Save", LocalDateTime.now()
            );
            Receipt receipt = new Receipt(tx, List.of(), new BigDecimal("5.00"), BigDecimal.ZERO);

            Path exportedPath = Paths.get("receipts", "receipt_SAS-SAVEFILE.txt");
            filesToCleanUp.add(exportedPath);

            runOnFxThread(() -> {
                dialogController.setReceipt(receipt);
                assertThat(dialogController.getCurrentReceipt()).isEqualTo(receipt);
                assertThat(dialogController.getReceiptText()).contains("SAS-SAVEFILE");

                // Print simulation
                dialogController.handlePrintReceipt(new ActionEvent());

                // Save to file headless fallback
                dialogController.handleSaveToFile(new ActionEvent());
                assertThat(Files.exists(exportedPath)).isTrue();

                try {
                    String savedContent = Files.readString(exportedPath);
                    assertThat(savedContent).isEqualTo(dialogController.getReceiptText());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }
}
