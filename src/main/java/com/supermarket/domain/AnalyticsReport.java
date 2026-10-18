package com.supermarket.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Domain record representing an aggregated sales and profit report
 * over an arbitrary date range or single day.
 */
public record AnalyticsReport(
    LocalDate startDate,
    LocalDate endDate,
    AnalyticsSummary summary,
    List<SalesStatistic> itemStatistics
) {
    public AnalyticsReport {
        Objects.requireNonNull(startDate, "Start date cannot be null");
        Objects.requireNonNull(endDate, "End date cannot be null");
        summary = Objects.requireNonNullElse(summary, new AnalyticsSummary(0, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        itemStatistics = (itemStatistics == null) ? List.of() : Collections.unmodifiableList(itemStatistics);
    }

    public boolean isSingleDay() {
        return startDate.equals(endDate);
    }

    public int totalTransactions() {
        return summary.totalTransactions();
    }

    public int totalItemsSold() {
        return summary.totalItemsSold();
    }

    public BigDecimal totalRevenue() {
        return summary.totalRevenue();
    }

    public BigDecimal totalCost() {
        return summary.totalCost();
    }

    public BigDecimal totalNetProfit() {
        return summary.totalNetProfit();
    }

    public double profitMarginPercentage() {
        return summary.getProfitMarginPercentage();
    }

    public boolean isEmpty() {
        return summary.totalTransactions() == 0 && itemStatistics.isEmpty();
    }
}
