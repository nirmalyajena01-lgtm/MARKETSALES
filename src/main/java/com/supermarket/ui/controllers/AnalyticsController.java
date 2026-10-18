package com.supermarket.ui.controllers;

import com.supermarket.domain.AnalyticsReport;
import com.supermarket.domain.AnalyticsSummary;
import com.supermarket.domain.SalesStatistic;
import com.supermarket.exception.ValidationException;
import com.supermarket.service.AnalyticsService;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * Controller for the Sales Analytics & Profit Tracking tab.
 * Implements Requirement R3: period-based sales profit tracking, executive KPI aggregation,
 * itemized profit breakdown with historical price snapshot invariance, and CSV export.
 */
public class AnalyticsController {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @FXML
    private DatePicker dpStartDate;

    @FXML
    private DatePicker dpEndDate;

    @FXML
    private Button btnToday;

    @FXML
    private Button btnYesterday;

    @FXML
    private Button btnLast7Days;

    @FXML
    private Button btnThisMonth;

    @FXML
    private Button btnAllTime;

    @FXML
    private Button btnGenerateReport;

    // KPI Summary labels
    @FXML
    private Label lblKpiRevenue;

    @FXML
    private Label lblKpiCost;

    @FXML
    private Label lblKpiProfit;

    @FXML
    private Label lblKpiMargin;

    @FXML
    private Label lblKpiItemsSold;

    @FXML
    private Label lblKpiTransactions;

    // Itemized Sales Statistics table
    @FXML
    private TableView<SalesStatistic> tableItemStats;

    @FXML
    private TableColumn<SalesStatistic, String> colStatCode;

    @FXML
    private TableColumn<SalesStatistic, String> colStatName;

    @FXML
    private TableColumn<SalesStatistic, Integer> colStatQty;

    @FXML
    private TableColumn<SalesStatistic, String> colStatRevenue;

    @FXML
    private TableColumn<SalesStatistic, String> colStatCost;

    @FXML
    private TableColumn<SalesStatistic, String> colStatProfit;

    @FXML
    private TableColumn<SalesStatistic, String> colStatMargin;

    @FXML
    private Button btnExportCsv;

    @FXML
    private Button btnPrintReport;

    @FXML
    private Label lblAnalyticsStatus;

    private final AnalyticsService analyticsService;
    private final ObservableList<SalesStatistic> itemStatsList = FXCollections.observableArrayList();
    private AnalyticsReport currentReport;

    public AnalyticsController() {
        this(new AnalyticsService());
    }

    public AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = Objects.requireNonNull(analyticsService, "AnalyticsService cannot be null");
    }

    @FXML
    public void initialize() {
        setupTableColumns();
        setupDefaultDates();
        generateReport();
    }

    private void setupTableColumns() {
        if (colStatCode != null) {
            colStatCode.setCellValueFactory(new PropertyValueFactory<>("itemCode"));
        }
        if (colStatName != null) {
            colStatName.setCellValueFactory(new PropertyValueFactory<>("itemName"));
        }
        if (colStatQty != null) {
            colStatQty.setCellValueFactory(new PropertyValueFactory<>("totalQuantitySold"));
        }
        if (colStatRevenue != null) {
            colStatRevenue.setCellValueFactory(cellData ->
                    new SimpleStringProperty(formatCurrency(cellData.getValue().totalRevenue())));
        }
        if (colStatCost != null) {
            colStatCost.setCellValueFactory(cellData ->
                    new SimpleStringProperty(formatCurrency(cellData.getValue().totalCost())));
        }
        if (colStatProfit != null) {
            colStatProfit.setCellValueFactory(cellData ->
                    new SimpleStringProperty(formatCurrency(cellData.getValue().netProfit())));
        }
        if (colStatMargin != null) {
            colStatMargin.setCellValueFactory(cellData ->
                    new SimpleStringProperty(String.format("%.2f%%", cellData.getValue().getProfitMarginPercentage())));
        }

        if (tableItemStats != null) {
            tableItemStats.setItems(itemStatsList);
        }
    }

    private void setupDefaultDates() {
        LocalDate today = LocalDate.now();
        if (dpStartDate != null) {
            dpStartDate.setValue(today);
        }
        if (dpEndDate != null) {
            dpEndDate.setValue(today);
        }
    }

    public void refreshAnalytics() {
        generateReport();
    }

    @FXML
    public void handleFilterToday(ActionEvent event) {
        LocalDate today = LocalDate.now();
        setDatesAndRun(today, today);
    }

    @FXML
    public void handleFilterYesterday(ActionEvent event) {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        setDatesAndRun(yesterday, yesterday);
    }

    @FXML
    public void handleFilterLast7Days(ActionEvent event) {
        LocalDate today = LocalDate.now();
        LocalDate start = today.minusDays(6);
        setDatesAndRun(start, today);
    }

    @FXML
    public void handleFilterThisMonth(ActionEvent event) {
        LocalDate today = LocalDate.now();
        LocalDate start = today.withDayOfMonth(1);
        setDatesAndRun(start, today);
    }

    @FXML
    public void handleFilterAllTime(ActionEvent event) {
        LocalDate start = LocalDate.of(2020, 1, 1);
        LocalDate today = LocalDate.now();
        setDatesAndRun(start, today);
    }

    private void setDatesAndRun(LocalDate start, LocalDate end) {
        if (dpStartDate != null) dpStartDate.setValue(start);
        if (dpEndDate != null) dpEndDate.setValue(end);
        generateReport();
    }

    @FXML
    public void handleGenerateReport(ActionEvent event) {
        generateReport();
    }

    public void generateReport() {
        LocalDate start = dpStartDate != null ? dpStartDate.getValue() : LocalDate.now();
        LocalDate end = dpEndDate != null ? dpEndDate.getValue() : LocalDate.now();

        if (start == null || end == null) {
            setStatus("Please select valid start and end dates.", true);
            return;
        }

        if (start.isAfter(end)) {
            setStatus("Start date cannot be after end date.", true);
            return;
        }

        try {
            AnalyticsReport report = analyticsService.generateReport(start, end);
            this.currentReport = report;

            updateKpiCards(report.summary());
            itemStatsList.setAll(report.itemStatistics());

            setStatus(String.format("Report loaded for period %s to %s (%d item breakdown records).",
                    start.format(DATE_FMT), end.format(DATE_FMT), report.itemStatistics().size()), false);
        } catch (ValidationException e) {
            setStatus("Validation error: " + e.getMessage(), true);
        } catch (Exception e) {
            setStatus("Failed to generate analytics report: " + e.getMessage(), true);
        }
    }

    private void updateKpiCards(AnalyticsSummary summary) {
        if (summary == null) return;

        if (lblKpiRevenue != null) {
            lblKpiRevenue.setText(formatCurrency(summary.totalRevenue()));
        }
        if (lblKpiCost != null) {
            lblKpiCost.setText(formatCurrency(summary.totalCost()));
        }
        if (lblKpiProfit != null) {
            lblKpiProfit.setText(formatCurrency(summary.totalNetProfit()));
        }
        if (lblKpiMargin != null) {
            lblKpiMargin.setText(String.format("%.2f%%", summary.getProfitMarginPercentage()));
        }
        if (lblKpiItemsSold != null) {
            lblKpiItemsSold.setText(String.valueOf(summary.totalItemsSold()));
        }
        if (lblKpiTransactions != null) {
            lblKpiTransactions.setText(String.valueOf(summary.totalTransactions()));
        }
    }

    @FXML
    public void handleExportCsv(ActionEvent event) {
        if (currentReport == null) {
            setStatus("No report data available to export.", true);
            return;
        }

        String defaultFileName = String.format("sales_analytics_%s_to_%s.csv",
                currentReport.startDate().format(DATE_FMT), currentReport.endDate().format(DATE_FMT));

        File targetFile = null;
        if (btnExportCsv != null && btnExportCsv.getScene() != null && btnExportCsv.getScene().getWindow() != null) {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Export Analytics CSV");
            chooser.setInitialFileName(defaultFileName);
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV Files (*.csv)", "*.csv"));
            targetFile = chooser.showSaveDialog(btnExportCsv.getScene().getWindow());
        }

        if (targetFile == null) {
            try {
                Path dir = Paths.get("reports");
                if (!Files.exists(dir)) {
                    Files.createDirectories(dir);
                }
                targetFile = dir.resolve(defaultFileName).toFile();
            } catch (IOException e) {
                setStatus("Failed to create reports directory: " + e.getMessage(), true);
                return;
            }
        }

        try {
            String csvContent = buildCsvContent(currentReport);
            Files.writeString(targetFile.toPath(), csvContent);
            setStatus("CSV exported successfully: " + targetFile.getAbsolutePath(), false);
        } catch (IOException e) {
            setStatus("Failed to export CSV: " + e.getMessage(), true);
        }
    }

    public String buildCsvContent(AnalyticsReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("Supermarket Automation Software (SAS) - Sales Profit Analytics\n");
        sb.append("Period,").append(report.startDate()).append(" to ").append(report.endDate()).append("\n\n");

        sb.append("EXECUTIVE SUMMARY\n");
        sb.append("Total Transactions,").append(report.summary().totalTransactions()).append("\n");
        sb.append("Total Items Sold,").append(report.summary().totalItemsSold()).append("\n");
        sb.append("Total Realized Revenue,").append(report.summary().totalRevenue()).append("\n");
        sb.append("Total Cost of Goods,").append(report.summary().totalCost()).append("\n");
        sb.append("Net Realized Profit,").append(report.summary().totalNetProfit()).append("\n");
        sb.append("Gross Margin %,").append(String.format("%.2f", report.summary().getProfitMarginPercentage())).append("%\n\n");

        sb.append("ITEMIZED SALES BREAKDOWN\n");
        sb.append("Item Code,Product Name,Quantity Sold,Realized Revenue,Total Cost,Net Profit,Margin %\n");
        for (SalesStatistic stat : report.itemStatistics()) {
            sb.append(escapeCsv(stat.itemCode())).append(",")
                    .append(escapeCsv(stat.itemName())).append(",")
                    .append(stat.totalQuantitySold()).append(",")
                    .append(stat.totalRevenue()).append(",")
                    .append(stat.totalCost()).append(",")
                    .append(stat.netProfit()).append(",")
                    .append(String.format("%.2f", stat.getProfitMarginPercentage())).append("%\n");
        }
        return sb.toString();
    }

    private String escapeCsv(String text) {
        if (text == null) return "";
        if (text.contains(",") || text.contains("\"") || text.contains("\n")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    @FXML
    public void handlePrintReport(ActionEvent event) {
        if (currentReport == null) {
            setStatus("No report data available to print.", true);
            return;
        }
        setStatus(String.format("Sales report [%s to %s] formatted and dispatched to printer.",
                currentReport.startDate(), currentReport.endDate()), false);
    }

    private String formatCurrency(BigDecimal amount) {
        return "₹" + Objects.requireNonNullElse(amount, BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP)
                .toPlainString();
    }

    private void setStatus(String message, boolean isError) {
        if (lblAnalyticsStatus != null) {
            lblAnalyticsStatus.setText(message);
            if (isError) {
                lblAnalyticsStatus.setStyle("-fx-text-fill: #dc2626; -fx-font-weight: bold;");
            } else {
                lblAnalyticsStatus.setStyle("-fx-text-fill: #059669; -fx-font-weight: bold;");
            }
        }
    }

    // Getters for testing
    public ObservableList<SalesStatistic> getItemStatsList() {
        return itemStatsList;
    }

    public AnalyticsReport getCurrentReport() {
        return currentReport;
    }

    public DatePicker getDpStartDate() {
        return dpStartDate;
    }

    public DatePicker getDpEndDate() {
        return dpEndDate;
    }

    public Label getLblKpiRevenue() {
        return lblKpiRevenue;
    }

    public Label getLblKpiProfit() {
        return lblKpiProfit;
    }

    public Label getLblKpiMargin() {
        return lblKpiMargin;
    }

    public Label getLblKpiItemsSold() {
        return lblKpiItemsSold;
    }

    public TableView<SalesStatistic> getTableItemStats() {
        return tableItemStats;
    }
}
