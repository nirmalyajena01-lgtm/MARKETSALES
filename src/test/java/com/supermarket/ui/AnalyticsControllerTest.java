package com.supermarket.ui;

import com.supermarket.domain.AnalyticsReport;
import com.supermarket.domain.Cart;
import com.supermarket.repository.SqliteAnalyticsRepository;
import com.supermarket.repository.SqliteItemRepository;
import com.supermarket.repository.SqliteSalesTransactionRepository;
import com.supermarket.service.AnalyticsService;
import com.supermarket.service.BillingService;
import com.supermarket.ui.controllers.AnalyticsController;
import javafx.event.ActionEvent;
import javafx.fxml.FXMLLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyticsControllerTest extends BaseJavaFxTest {

    private AnalyticsService analyticsService;
    private BillingService billingService;
    private AnalyticsController controller;

    @BeforeEach
    void setUpController() {
        SqliteAnalyticsRepository analyticsRepo = new SqliteAnalyticsRepository(db);
        SqliteItemRepository itemRepo = new SqliteItemRepository(db);
        SqliteSalesTransactionRepository salesRepo = new SqliteSalesTransactionRepository(db);

        analyticsService = new AnalyticsService(analyticsRepo);
        billingService = new BillingService(db, itemRepo, salesRepo);

        // Perform a test transaction today so that analytics has real data
        Cart cart = billingService.createCart();
        billingService.addItemToCart(cart, "DAIRY-001", 10);
        billingService.checkout(cart, new BigDecimal("2000.00"), "Cashier-1");

        runOnFxThread(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/analytics.fxml"));
                loader.setControllerFactory(type -> new AnalyticsController(analyticsService));
                loader.load();
                controller = loader.getController();
            } catch (Exception e) {
                throw new RuntimeException("Failed to load analytics.fxml", e);
            }
        });
    }

    @Test
    @DisplayName("Analytics generates daily report with accurate KPI numbers")
    void testDailyAnalyticsReportGeneration() {
        runOnFxThread(() -> {
            controller.handleFilterToday(new ActionEvent());

            AnalyticsReport report = controller.getCurrentReport();
            assertThat(report).isNotNull();
            assertThat(report.summary().totalRevenue()).isEqualByComparingTo("1568.70");
            assertThat(report.summary().totalCost()).isEqualByComparingTo("996.00");
            assertThat(report.summary().totalNetProfit()).isEqualByComparingTo("572.70");
            assertThat(report.summary().totalItemsSold()).isEqualTo(10);
            assertThat(report.summary().totalTransactions()).isEqualTo(1);

            // KPI labels
            assertThat(controller.getLblKpiRevenue().getText()).isEqualTo("₹1568.70");
            assertThat(controller.getLblKpiProfit().getText()).isEqualTo("₹572.70");
            assertThat(controller.getLblKpiItemsSold().getText()).isEqualTo("10");

            // Itemized stats table
            assertThat(controller.getItemStatsList()).hasSize(1);
            assertThat(controller.getItemStatsList().get(0).itemCode()).isEqualTo("DAIRY-001");
            assertThat(controller.getItemStatsList().get(0).totalQuantitySold()).isEqualTo(10);
            assertThat(controller.getItemStatsList().get(0).totalRevenue()).isEqualByComparingTo("1568.70");
            assertThat(controller.getItemStatsList().get(0).netProfit()).isEqualByComparingTo("572.70");
        });
    }

    @Test
    @DisplayName("Quick filter buttons update date pickers appropriately")
    void testQuickDateFilters() {
        runOnFxThread(() -> {
            LocalDate today = LocalDate.now();

            // Yesterday
            controller.handleFilterYesterday(new ActionEvent());
            assertThat(controller.getDpStartDate().getValue()).isEqualTo(today.minusDays(1));
            assertThat(controller.getDpEndDate().getValue()).isEqualTo(today.minusDays(1));

            // Last 7 Days
            controller.handleFilterLast7Days(new ActionEvent());
            assertThat(controller.getDpStartDate().getValue()).isEqualTo(today.minusDays(6));
            assertThat(controller.getDpEndDate().getValue()).isEqualTo(today);

            // This Month
            controller.handleFilterThisMonth(new ActionEvent());
            assertThat(controller.getDpStartDate().getValue()).isEqualTo(today.withDayOfMonth(1));
            assertThat(controller.getDpEndDate().getValue()).isEqualTo(today);
        });
    }

    @Test
    @DisplayName("CSV export generator produces standard compliant spreadsheet text")
    void testCsvExportBuilder() {
        runOnFxThread(() -> {
            controller.handleFilterToday(new ActionEvent());
            AnalyticsReport report = controller.getCurrentReport();

            String csv = controller.buildCsvContent(report);
            assertThat(csv).contains("Supermarket Automation Software (SAS) - Sales Profit Analytics");
            assertThat(csv).contains("EXECUTIVE SUMMARY");
            assertThat(csv).contains("Total Realized Revenue,1568.70");
            assertThat(csv).contains("Net Realized Profit,572.70");
            assertThat(csv).contains("DAIRY-001");
            assertThat(csv).contains("Whole Milk 1L,10,1568.70,996.00,572.70");
        });
    }
}
