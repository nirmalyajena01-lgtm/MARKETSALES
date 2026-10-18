package com.supermarket.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Domain record representing aggregated sales and profit statistics for an item
 * over an arbitrary date range.
 */
public record SalesStatistic(
    String itemCode,
    String itemName,
    int totalQuantitySold,
    BigDecimal totalRevenue,
    BigDecimal totalCost,
    BigDecimal netProfit
) {
    public SalesStatistic {
        Objects.requireNonNull(itemCode, "Item code cannot be null");
        itemName = (itemName == null) ? "" : itemName;
        totalRevenue = Objects.requireNonNullElse(totalRevenue, BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        totalCost = Objects.requireNonNullElse(totalCost, BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        netProfit = Objects.requireNonNullElse(netProfit, BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }

    public double getProfitMarginPercentage() {
        if (totalRevenue.compareTo(BigDecimal.ZERO) == 0) {
            return 0.0;
        }
        return netProfit.divide(totalRevenue, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP)
                .doubleValue();
    }
}
