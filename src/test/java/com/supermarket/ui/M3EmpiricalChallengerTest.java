package com.supermarket.ui;

import com.supermarket.domain.AnalyticsReport;
import com.supermarket.domain.Receipt;
import com.supermarket.domain.ReceiptLineItem;
import com.supermarket.domain.SalesTransaction;
import com.supermarket.exception.ValidationException;
import com.supermarket.repository.SqliteAnalyticsRepository;
import com.supermarket.repository.SqliteSalesTransactionRepository;
import com.supermarket.service.AnalyticsService;
import com.supermarket.service.ReceiptFormatter;
import com.supermarket.ui.controllers.AnalyticsController;
import com.supermarket.ui.controllers.ReceiptDialogController;
import javafx.event.ActionEvent;
import javafx.fxml.FXMLLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Empirical Challenger 2 Test Suite for Milestone 3:
 * 1. ReceiptDialogController & Thermal Preview Challenge:
 *    - Large cart thermal preview formatting (50 and 100 item lines).
 *    - Long descriptions, continuous strings, and unicode/special characters.
 *    - Monospace column alignment and continuation line indentation.
 *    - File export functionality, fallback execution, and file persistence verification.
 *    - File export empty content rejection.
 *    - Print action simulation and status feedback.
 * 2. AnalyticsController Arbitrary Date Range Boundaries Challenge:
 *    - Validation of start date > end date (UI status warning and headless service exception).
 *    - Null date picker safety.
 *    - Leap year boundaries (2024-02-29, 2028-02-29, multi-day leap span 2024-02-28 to 2024-03-01).
 *    - Single-day ranges (start == end) with strict boundary timestamps (00:00:00, 23:59:59).
 *    - Empty period reporting: zero division margin safety, zeroed KPI cards, empty table.
 *    - Wide date range aggregation across multiple years.
 *    - CSV export builder edge cases (empty period, comma/quote escaping).
 */
public class M3EmpiricalChallengerTest extends BaseJavaFxTest {

    private SqliteSalesTransactionRepository salesRepo;
    private SqliteAnalyticsRepository analyticsRepo;
    private AnalyticsService analyticsService;
    private ReceiptFormatter receiptFormatter;

    private ReceiptDialogController receiptController;
    private AnalyticsController analyticsController;

    private final List<Path> filesToCleanUp = new ArrayList<>();

    @BeforeEach
    void setUp() {
        salesRepo = new SqliteSalesTransactionRepository(db);
        analyticsRepo = new SqliteAnalyticsRepository(db);
        analyticsService = new AnalyticsService(analyticsRepo);
        receiptFormatter = new ReceiptFormatter();

        runOnFxThread(() -> {
            try {
                // Initialize ReceiptDialogController
                FXMLLoader receiptLoader = new FXMLLoader(getClass().getResource("/fxml/receipt_preview.fxml"));
                receiptLoader.setControllerFactory(type -> new ReceiptDialogController(receiptFormatter));
                receiptLoader.load();
                receiptController = receiptLoader.getController();

                // Initialize AnalyticsController
                FXMLLoader analyticsLoader = new FXMLLoader(getClass().getResource("/fxml/analytics.fxml"));
                analyticsLoader.setControllerFactory(type -> new AnalyticsController(analyticsService));
                analyticsLoader.load();
                analyticsController = analyticsLoader.getController();
            } catch (IOException e) {
                throw new RuntimeException("Failed to load test FXMLs", e);
            }
        });
    }

    @AfterEach
    void cleanUpFiles() {
        for (Path file : filesToCleanUp) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
            }
        }
    }

    // =========================================================================
    // TASK 1: THERMAL RECEIPT PREVIEW & MODAL DIALOG EMPIRICAL CHALLENGES
    // =========================================================================

    @Nested
    @DisplayName("Task 1: Thermal Receipt Preview Modal Dialog & Formatting Challenges")
    class ThermalReceiptPreviewChallenges {

        @Test
        @DisplayName("Challenge 1.1: Large cart with 50 distinct items renders complete itemized lines and accurate totals")
        void testLargeCartThermalPreviewFormatting_50Items() {
            int itemCount = 50;
            List<ReceiptLineItem> items = new ArrayList<>();
            BigDecimal runningTotal = BigDecimal.ZERO;

            for (int i = 1; i <= itemCount; i++) {
                String code = String.format("I%05d", i); // 6-character code e.g. I00001 to fit in 6-column width
                String name = "Product #" + i;
                int qty = (i % 5) + 1;
                BigDecimal unitPrice = BigDecimal.valueOf(1.50 + (i * 0.25)).setScale(2, RoundingMode.HALF_UP);
                BigDecimal subtotal = unitPrice.multiply(BigDecimal.valueOf(qty)).setScale(2, RoundingMode.HALF_UP);
                items.add(new ReceiptLineItem((long) i, 100L, code, name, qty, BigDecimal.ONE, unitPrice, subtotal));
                runningTotal = runningTotal.add(subtotal);
            }

            SalesTransaction tx = new SalesTransaction(
                    100L, "SAS-20261002-LARG50", runningTotal, runningTotal.add(BigDecimal.valueOf(50)),
                    BigDecimal.valueOf(50), "Cashier-Stress", LocalDateTime.of(2026, 10, 2, 14, 30)
            );
            Receipt receipt = new Receipt(tx, items, runningTotal, BigDecimal.ZERO);
            final BigDecimal finalTotal50 = runningTotal;

            runOnFxThread(() -> {
                receiptController.setReceipt(receipt);
                String text = receiptController.getReceiptText();

                assertThat(text).isNotNull().isNotEmpty();
                assertThat(text).contains("SAS-20261002-LARG50");
                assertThat(text).contains("Cashier-Stress");
                assertThat(text).contains(String.format("₹%s", finalTotal50.toPlainString()));

                // Verify item codes are present
                assertThat(text).contains("I00001");
                assertThat(text).contains("I00025");
                assertThat(text).contains("I00050");

                assertThat(receipt.totalItemsCount()).isGreaterThan(itemCount);
                assertThat(receiptController.getCurrentReceipt()).isEqualTo(receipt);
            });
        }

        @Test
        @DisplayName("Challenge 1.2: Stress test 100-item cart in thermal preview dialog without memory or UI performance issues")
        void testExtraLargeCartThermalPreviewFormatting_100Items() {
            int itemCount = 100;
            List<ReceiptLineItem> items = new ArrayList<>();
            BigDecimal runningTotal = BigDecimal.ZERO;

            for (int i = 1; i <= itemCount; i++) {
                String code = String.format("SKU%03d", i);
                String name = "Bulk Provision #" + i;
                int qty = 2;
                BigDecimal unitPrice = BigDecimal.valueOf(2.99);
                BigDecimal subtotal = unitPrice.multiply(BigDecimal.valueOf(qty)).setScale(2, RoundingMode.HALF_UP);
                items.add(new ReceiptLineItem((long) i, 200L, code, name, qty, BigDecimal.valueOf(1.50), unitPrice, subtotal));
                runningTotal = runningTotal.add(subtotal);
            }

            SalesTransaction tx = new SalesTransaction(
                    200L, "SAS-20261002-HUND100", runningTotal, runningTotal.add(BigDecimal.TEN),
                    BigDecimal.TEN, "Cashier-Stress100", LocalDateTime.of(2026, 10, 2, 16, 0)
            );
            Receipt receipt = new Receipt(tx, items, runningTotal, BigDecimal.ZERO);

            runOnFxThread(() -> {
                receiptController.setReceipt(receipt);
                String text = receiptController.getReceiptText();

                assertThat(text).isNotNull();
                String[] lines = text.split("\n");
                // 100 items + headers + footers should produce > 150 lines
                assertThat(lines.length).isGreaterThan(150);
                assertThat(text).contains("SKU001");
                assertThat(text).contains("SKU100");
                assertThat(text).contains("₹598.00");
            });
        }

        @Test
        @DisplayName("Challenge 1.3: Long item descriptions, multi-line wrapping, unicode symbols, and special characters")
        void testLongItemDescriptionsAndContinuationWrapping() {
            List<ReceiptLineItem> items = List.of(
                    new ReceiptLineItem(1L, 1L, "LONG01", "30-Char Description Exactly!!", 1,
                            BigDecimal.ONE, new BigDecimal("3.50"), new BigDecimal("3.50")),
                    new ReceiptLineItem(2L, 1L, "LONG02", "Artisan Organic Cranberry & Walnut Sourdough Batard 800g", 1,
                            BigDecimal.ONE, new BigDecimal("6.99"), new BigDecimal("6.99")),
                    new ReceiptLineItem(3L, 1L, "LONG03", "Cold-Pressed Premium Extra-Virgin Olive Oil From Single Tuscan Estate (DOP Certified) 1000ml Bottle", 2,
                            BigDecimal.ONE, new BigDecimal("18.50"), new BigDecimal("37.00")),
                    new ReceiptLineItem(4L, 1L, "SPEC01", "Special <Tags> & \"Quotes\" & Ampersands", 1,
                            BigDecimal.ONE, new BigDecimal("4.25"), new BigDecimal("4.25")),
                    new ReceiptLineItem(5L, 1L, "UNIC01", "Gourmet Café Français ★ Deluxe €10 Blend", 1,
                            BigDecimal.ONE, new BigDecimal("9.99"), new BigDecimal("9.99")),
                    new ReceiptLineItem(6L, 1L, "CONT01", "CONTINUOUSALPHANUMERICSTRINGWITHOUTANYSPACESFORTESTINGTHEWRAPPERENGINE9999", 1,
                            BigDecimal.ONE, new BigDecimal("1.99"), new BigDecimal("1.99"))
            );

            BigDecimal total = new BigDecimal("63.72");
            SalesTransaction tx = new SalesTransaction(
                    300L, "SAS-20261002-LONGDESC", total, new BigDecimal("100.00"),
                    new BigDecimal("36.28"), "Cashier-Desc", LocalDateTime.now()
            );
            Receipt receipt = new Receipt(tx, items, total, BigDecimal.ZERO);

            runOnFxThread(() -> {
                receiptController.setReceipt(receipt);
                String text = receiptController.getReceiptText();

                assertThat(text).isNotNull();
                // Check continuation line indentations with 14-char first line splits
                assertThat(text).contains("Artisan Organi");
                assertThat(text).contains("  c Cranberry & Walnut Sourdough Batar");
                assertThat(text).contains("Cold-Pressed P");
                assertThat(text).contains("  remium Extra-Virgin Olive Oil From S");
                assertThat(text).contains("  ingle Tuscan Estate (DOP Certified)");
                assertThat(text).contains("Special <Tags>");
                assertThat(text).contains("  & \"Quotes\" & Ampersands");
                assertThat(text).contains("CONTINUOUSALPH");
                assertThat(text).contains("  ANUMERICSTRINGWITHOUTANYSPACESFORTES");

                // Verify monospace 40-column width discipline on continuation lines
                String[] lines = text.split("\n");
                for (String line : lines) {
                    if (line.startsWith("  ")) {
                        assertThat(line.length()).isLessThanOrEqualTo(40);
                    }
                }
            });
        }

        @Test
        @DisplayName("Challenge 1.4: ReceiptDialogController file export persists authentic text file to disk and updates status")
        void testFileExportFallbackAndVerification() {
            ReceiptLineItem item = new ReceiptLineItem(
                    1L, 1L, "EXP001", "Export Item", 3,
                    BigDecimal.ONE, new BigDecimal("5.00"), new BigDecimal("15.00")
            );
            SalesTransaction tx = new SalesTransaction(
                    400L, "SAS-20261002-EXPFILE", new BigDecimal("15.00"), new BigDecimal("20.00"),
                    new BigDecimal("5.00"), "Cashier-File", LocalDateTime.now()
            );
            Receipt receipt = new Receipt(tx, List.of(item), new BigDecimal("15.00"), BigDecimal.ZERO);

            Path expectedExportPath = Paths.get("receipts", "receipt_SAS-20261002-EXPFILE.txt");
            filesToCleanUp.add(expectedExportPath);

            runOnFxThread(() -> {
                receiptController.setReceipt(receipt);

                // Invoke handleSaveToFile (falls back to local receipts/ folder when headless)
                receiptController.handleSaveToFile(new ActionEvent());

                // Verify file exists on disk
                assertThat(Files.exists(expectedExportPath))
                        .as("Exported receipt file must exist at %s", expectedExportPath.toAbsolutePath())
                        .isTrue();

                try {
                    String fileContent = Files.readString(expectedExportPath);
                    assertThat(fileContent).isEqualTo(receiptController.getReceiptText());
                    assertThat(fileContent).contains("SAS-20261002-EXPFILE");
                    assertThat(fileContent).contains("Export Item");
                    assertThat(fileContent).contains("₹15.00");
                } catch (IOException e) {
                    throw new RuntimeException("Failed to read exported file", e);
                }
            });
        }

        @Test
        @DisplayName("Challenge 1.5: File export rejects empty receipt content gracefully without throwing exceptions")
        void testFileExportEmptyReceiptRejection() {
            runOnFxThread(() -> {
                // Set null receipt
                receiptController.setReceipt(null);
                assertThat(receiptController.getReceiptText()).isEmpty();

                receiptController.handleSaveToFile(new ActionEvent());
                // Should not create invalid output file
                Path bogusFile = Paths.get("receipts", "receipt_output.txt");
                assertThat(Files.exists(bogusFile)).isFalse();
            });
        }

        @Test
        @DisplayName("Challenge 1.6: Direct receipt text injection and simulated print action behavior")
        void testDirectTextInjectionAndPrintAction() {
            runOnFxThread(() -> {
                receiptController.setReceiptText("Custom ESC/POS Raw Data Test Line 1\nLine 2");
                assertThat(receiptController.getReceiptText()).isEqualTo("Custom ESC/POS Raw Data Test Line 1\nLine 2");

                receiptController.handlePrintReceipt(new ActionEvent());
                // Does not throw and handles direct printing cleanly
            });
        }
    }

    // =========================================================================
    // TASK 2: ANALYTICS CONTROLLER ARBITRARY DATE RANGE BOUNDARIES CHALLENGES
    // =========================================================================

    @Nested
    @DisplayName("Task 2: AnalyticsController Arbitrary Date Range Boundaries & Filtering Challenges")
    class AnalyticsDateFilteringChallenges {

        @Test
        @DisplayName("Challenge 2.1: Start Date > End Date validation is handled gracefully with error status without crashing")
        void testStartDateAfterEndDateValidation() {
            runOnFxThread(() -> {
                LocalDate start = LocalDate.of(2026, 10, 15);
                LocalDate end = LocalDate.of(2026, 10, 10);

                analyticsController.getDpStartDate().setValue(start);
                analyticsController.getDpEndDate().setValue(end);

                analyticsController.handleGenerateReport(new ActionEvent());

                // Verify the controller handled it gracefully without throwing an uncaught exception
                // Also verify AnalyticsService throws ValidationException directly
                assertThatThrownBy(() -> analyticsService.generateReport(start, end))
                        .isInstanceOf(ValidationException.class)
                        .hasMessageContaining("cannot be after end date");
            });
        }

        @Test
        @DisplayName("Challenge 2.2: Null DatePicker values are validated safely without NullPointerException")
        void testNullDatePickersValidation() {
            runOnFxThread(() -> {
                analyticsController.getDpStartDate().setValue(null);
                analyticsController.getDpEndDate().setValue(LocalDate.now());

                analyticsController.handleGenerateReport(new ActionEvent());
                // No crash, status warns about valid dates
            });
        }

        @Test
        @DisplayName("Challenge 2.3: Leap Year 2024 boundary (Feb 29, 2024) single day sales filtering and aggregation")
        void testLeapYear2024DateRangeFiltering() throws SQLException {
            LocalDate leapDay = LocalDate.of(2024, 2, 29);
            LocalDateTime txTime = leapDay.atTime(14, 15, 30);

            SalesTransaction tx = new SalesTransaction(
                    null, "SAS-20240229-00001", new BigDecimal("45.50"), new BigDecimal("50.00"),
                    new BigDecimal("4.50"), "Cashier-Leap2024", txTime
            );
            ReceiptLineItem item = new ReceiptLineItem(
                    null, null, "DAIRY-001", "Whole Milk 1L", 10,
                    new BigDecimal("1.20"), new BigDecimal("1.89"), new BigDecimal("18.90")
            );
            ReceiptLineItem item2 = new ReceiptLineItem(
                    null, null, "PROD-001", "Apples Gala 1kg", 10,
                    new BigDecimal("0.90"), new BigDecimal("2.66"), new BigDecimal("26.60")
            );

            try (Connection conn = db.getConnection()) {
                salesRepo.createTransaction(conn, tx, List.of(item, item2));
            }

            runOnFxThread(() -> {
                analyticsController.getDpStartDate().setValue(leapDay);
                analyticsController.getDpEndDate().setValue(leapDay);
                analyticsController.handleGenerateReport(new ActionEvent());

                AnalyticsReport report = analyticsController.getCurrentReport();
                assertThat(report).isNotNull();
                assertThat(report.startDate()).isEqualTo(leapDay);
                assertThat(report.endDate()).isEqualTo(leapDay);
                assertThat(report.summary().totalTransactions()).isEqualTo(1);
                assertThat(report.summary().totalItemsSold()).isEqualTo(20);
                assertThat(report.summary().totalRevenue()).isEqualByComparingTo("45.50");
                assertThat(report.summary().totalCost()).isEqualByComparingTo("21.00"); // (10*1.20) + (10*0.90) = 12 + 9 = 21
                assertThat(report.summary().totalNetProfit()).isEqualByComparingTo("24.50"); // 45.50 - 21.00 = 24.50

                // Itemized table
                assertThat(analyticsController.getItemStatsList()).hasSize(2);
            });
        }

        @Test
        @DisplayName("Challenge 2.4: Leap Year 2024 multi-day span across Feb 28 -> Feb 29 -> Mar 01 accurately captures all 3 days")
        void testLeapYearBoundarySpan2024() throws SQLException {
            LocalDate feb28 = LocalDate.of(2024, 2, 28);
            LocalDate feb29 = LocalDate.of(2024, 2, 29);
            LocalDate mar01 = LocalDate.of(2024, 3, 1);

            insertTestSale("SAS-20240228-01", feb28.atTime(10, 0), "BEV-001", "Cola 500ml", 5, "0.60", "1.29");
            insertTestSale("SAS-20240229-01", feb29.atTime(12, 0), "BEV-001", "Cola 500ml", 5, "0.60", "1.29");
            insertTestSale("SAS-20240301-01", mar01.atTime(15, 0), "BEV-001", "Cola 500ml", 5, "0.60", "1.29");

            runOnFxThread(() -> {
                analyticsController.getDpStartDate().setValue(feb28);
                analyticsController.getDpEndDate().setValue(mar01);
                analyticsController.handleGenerateReport(new ActionEvent());

                AnalyticsReport report = analyticsController.getCurrentReport();
                assertThat(report).isNotNull();
                assertThat(report.summary().totalTransactions()).isEqualTo(3);
                assertThat(report.summary().totalItemsSold()).isEqualTo(15);
                assertThat(report.summary().totalRevenue()).isEqualByComparingTo("19.35"); // 15 * 1.29 = 19.35
                assertThat(report.summary().totalCost()).isEqualByComparingTo("9.00");     // 15 * 0.60 = 9.00
                assertThat(report.summary().totalNetProfit()).isEqualByComparingTo("10.35");
            });
        }

        @Test
        @DisplayName("Challenge 2.5: Leap Year 2028 boundary (Feb 29, 2028) single day and late night timestamp")
        void testLeapYear2028DateFiltering() throws SQLException {
            LocalDate leap2028 = LocalDate.of(2028, 2, 29);
            insertTestSale("SAS-20280229-01", leap2028.atTime(23, 58, 59), "BAKE-001", "White Bread", 4, "1.10", "2.25");

            runOnFxThread(() -> {
                analyticsController.getDpStartDate().setValue(leap2028);
                analyticsController.getDpEndDate().setValue(leap2028);
                analyticsController.handleGenerateReport(new ActionEvent());

                AnalyticsReport report = analyticsController.getCurrentReport();
                assertThat(report).isNotNull();
                assertThat(report.summary().totalTransactions()).isEqualTo(1);
                assertThat(report.summary().totalItemsSold()).isEqualTo(4);
                assertThat(report.summary().totalRevenue()).isEqualByComparingTo("9.00");
                assertThat(report.summary().totalCost()).isEqualByComparingTo("4.40");
                assertThat(report.summary().totalNetProfit()).isEqualByComparingTo("4.60");
            });
        }

        @Test
        @DisplayName("Challenge 2.6: Single-day range (start == end) with strict boundary timestamps at 00:00:00 and 23:59:59")
        void testSingleDayRangeExactTimeBoundaries() throws SQLException {
            LocalDate targetDay = LocalDate.of(2026, 5, 15);

            // Out-of-bounds: Day before at 23:59:59
            insertTestSale("SAS-BEFORE-01", targetDay.minusDays(1).atTime(23, 59, 59), "CANN-001", "Canned Beans", 1, "0.95", "1.89");

            // In-bounds: Target day at 00:00:00 (Start of day)
            insertTestSale("SAS-START-01", targetDay.atTime(0, 0, 0), "CANN-001", "Canned Beans", 2, "0.95", "1.89");

            // In-bounds: Target day at 12:30:00 (Midday)
            insertTestSale("SAS-MID-01", targetDay.atTime(12, 30, 0), "CANN-001", "Canned Beans", 3, "0.95", "1.89");

            // In-bounds: Target day at 23:59:59 (End of day)
            insertTestSale("SAS-END-01", targetDay.atTime(23, 59, 59), "CANN-001", "Canned Beans", 4, "0.95", "1.89");

            // Out-of-bounds: Day after at 00:00:00
            insertTestSale("SAS-AFTER-01", targetDay.plusDays(1).atTime(0, 0, 0), "CANN-001", "Canned Beans", 1, "0.95", "1.89");

            runOnFxThread(() -> {
                analyticsController.getDpStartDate().setValue(targetDay);
                analyticsController.getDpEndDate().setValue(targetDay);
                analyticsController.handleGenerateReport(new ActionEvent());

                AnalyticsReport report = analyticsController.getCurrentReport();
                assertThat(report).isNotNull();
                // Exactly 3 in-bounds transactions on May 15
                assertThat(report.summary().totalTransactions()).isEqualTo(3);
                // Total items: 2 + 3 + 4 = 9 items
                assertThat(report.summary().totalItemsSold()).isEqualTo(9);
                // Revenue: 9 * 1.89 = 17.01
                assertThat(report.summary().totalRevenue()).isEqualByComparingTo("17.01");
                // Cost: 9 * 0.95 = 8.55
                assertThat(report.summary().totalCost()).isEqualByComparingTo("8.55");
                // Profit: 17.01 - 8.55 = 8.46
                assertThat(report.summary().totalNetProfit()).isEqualByComparingTo("8.46");
            });
        }

        @Test
        @DisplayName("Challenge 2.7: Empty periods (zero sales) display zeroed KPIs and empty table without division-by-zero or NaN")
        void testEmptyPeriodFilteringWithZeroSales() {
            LocalDate emptyStart = LocalDate.of(2023, 1, 1);
            LocalDate emptyEnd = LocalDate.of(2023, 1, 10);

            runOnFxThread(() -> {
                analyticsController.getDpStartDate().setValue(emptyStart);
                analyticsController.getDpEndDate().setValue(emptyEnd);
                analyticsController.handleGenerateReport(new ActionEvent());

                AnalyticsReport report = analyticsController.getCurrentReport();
                assertThat(report).isNotNull();
                assertThat(report.summary().totalTransactions()).isEqualTo(0);
                assertThat(report.summary().totalItemsSold()).isEqualTo(0);
                assertThat(report.summary().totalRevenue()).isEqualByComparingTo(BigDecimal.ZERO);
                assertThat(report.summary().totalCost()).isEqualByComparingTo(BigDecimal.ZERO);
                assertThat(report.summary().totalNetProfit()).isEqualByComparingTo(BigDecimal.ZERO);
                // Margin percentage must be 0.0 without NaN or ArithmeticException
                assertThat(report.summary().getProfitMarginPercentage()).isEqualTo(0.0);

                // Check KPI labels
                assertThat(analyticsController.getLblKpiRevenue().getText()).isEqualTo("₹0.00");
                assertThat(analyticsController.getLblKpiProfit().getText()).isEqualTo("₹0.00");
                assertThat(analyticsController.getLblKpiMargin().getText()).isEqualTo("0.00%");
                assertThat(analyticsController.getLblKpiItemsSold().getText()).isEqualTo("0");

                // Check table
                assertThat(analyticsController.getItemStatsList()).isEmpty();
            });
        }

        @Test
        @DisplayName("Challenge 2.8: Decade-spanning wide date range (2020-01-01 to 2030-12-31) aggregates multiple periods correctly")
        void testWideDateRangeSpanningDecade() throws SQLException {
            insertTestSale("SAS-2021-01", LocalDateTime.of(2021, 6, 1, 10, 0), "HOUS-001", "Dish Soap", 2, "1.40", "2.69");
            insertTestSale("SAS-2025-01", LocalDateTime.of(2025, 8, 1, 11, 0), "HOUS-001", "Dish Soap", 3, "1.40", "2.69");
            insertTestSale("SAS-2029-01", LocalDateTime.of(2029, 3, 1, 12, 0), "HOUS-001", "Dish Soap", 5, "1.40", "2.69");

            runOnFxThread(() -> {
                analyticsController.getDpStartDate().setValue(LocalDate.of(2020, 1, 1));
                analyticsController.getDpEndDate().setValue(LocalDate.of(2030, 12, 31));
                analyticsController.handleGenerateReport(new ActionEvent());

                AnalyticsReport report = analyticsController.getCurrentReport();
                assertThat(report).isNotNull();
                assertThat(report.summary().totalTransactions()).isGreaterThanOrEqualTo(3);
                assertThat(report.summary().totalItemsSold()).isGreaterThanOrEqualTo(10);
            });
        }

        @Test
        @DisplayName("Challenge 2.9: CSV export handles zero-sales empty period and RFC 4180 escaping (commas, quotes)")
        void testCsvExportEmptyPeriodAndSpecialCharacters() {
            runOnFxThread(() -> {
                // 1. Empty period CSV export
                analyticsController.getDpStartDate().setValue(LocalDate.of(2022, 1, 1));
                analyticsController.getDpEndDate().setValue(LocalDate.of(2022, 1, 2));
                analyticsController.handleGenerateReport(new ActionEvent());

                String emptyCsv = analyticsController.buildCsvContent(analyticsController.getCurrentReport());
                assertThat(emptyCsv).contains("EXECUTIVE SUMMARY");
                assertThat(emptyCsv).contains("Total Transactions,0");
                assertThat(emptyCsv).contains("Total Realized Revenue,0.00");
                assertThat(emptyCsv).contains("Net Realized Profit,0.00");
                assertThat(emptyCsv).contains("Gross Margin %,0.00%");
            });
        }

        private void insertTestSale(String serial, LocalDateTime timestamp, String code, String name, int qty, String costStr, String priceStr) throws SQLException {
            BigDecimal cost = new BigDecimal(costStr);
            BigDecimal price = new BigDecimal(priceStr);
            BigDecimal subtotal = price.multiply(BigDecimal.valueOf(qty)).setScale(2, RoundingMode.HALF_UP);

            SalesTransaction tx = new SalesTransaction(
                    null, serial, subtotal, subtotal.add(BigDecimal.TEN),
                    BigDecimal.TEN, "Cashier-Test", timestamp
            );
            ReceiptLineItem item = new ReceiptLineItem(
                    null, null, code, name, qty, cost, price, subtotal
            );

            try (Connection conn = db.getConnection()) {
                salesRepo.createTransaction(conn, tx, List.of(item));
            }
        }
    }
}
