package com.supermarket.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Domain record representing executive-level KPI metrics aggregated
 * across all sales for an arbitrary date range.
 */
public record AnalyticsSummary(
    int totalTransactions,
    int totalItemsSold,
    BigDecimal totalRevenue,
    BigDecimal totalCost,
    BigDecimal totalNetProfit
) {
    public AnalyticsSummary {
        totalRevenue = Objects.requireNonNullElse(totalRevenue, BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        totalCost = Objects.requireNonNullElse(totalCost, BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        totalNetProfit = Objects.requireNonNullElse(totalNetProfit, BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }

    public double getProfitMarginPercentage() {
        if (totalRevenue.compareTo(BigDecimal.ZERO) == 0) {
            return 0.0;
        }
        return totalNetProfit.divide(totalRevenue, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP)
                .doubleValue();
    }
}
