package com.supermarket.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Domain record representing an itemized line on a receipt, including
 * immutable price snapshots taken at the exact moment of checkout.
 */
public record ReceiptLineItem(
    Long id,
    Long transactionId,
    String itemCode,
    String itemName,
    int quantity,
    BigDecimal unitCostPriceSnapshot,
    BigDecimal unitSellingPriceSnapshot,
    BigDecimal subtotal
) {
    public ReceiptLineItem {
        Objects.requireNonNull(itemCode, "Item code cannot be null");
        Objects.requireNonNull(itemName, "Item name cannot be null");
        unitCostPriceSnapshot = Objects.requireNonNullElse(unitCostPriceSnapshot, BigDecimal.ZERO);
        unitSellingPriceSnapshot = Objects.requireNonNullElse(unitSellingPriceSnapshot, BigDecimal.ZERO);
        if (subtotal == null) {
            subtotal = unitSellingPriceSnapshot.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
        }
    }

    /**
     * Net profit realized on this item line = subtotal - (quantity * unitCostPriceSnapshot).
     */
    public BigDecimal getProfit() {
        BigDecimal totalCost = unitCostPriceSnapshot.multiply(BigDecimal.valueOf(quantity));
        return subtotal.subtract(totalCost).setScale(2, RoundingMode.HALF_UP);
    }
}
