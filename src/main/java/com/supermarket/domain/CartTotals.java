package com.supermarket.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Domain record representing financial totals for a cart at a specific point in time.
 */
public record CartTotals(
    BigDecimal subtotal,
    BigDecimal taxRate,
    BigDecimal taxAmount,
    BigDecimal totalPayable,
    BigDecimal amountTendered,
    BigDecimal changeDue
) {
    public CartTotals {
        subtotal = Objects.requireNonNullElse(subtotal, BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        taxRate = Objects.requireNonNullElse(taxRate, BigDecimal.ZERO).setScale(4, RoundingMode.HALF_UP);
        taxAmount = Objects.requireNonNullElse(taxAmount, BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        totalPayable = Objects.requireNonNullElse(totalPayable, BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        amountTendered = Objects.requireNonNullElse(amountTendered, BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        changeDue = Objects.requireNonNullElse(changeDue, BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }

    public static CartTotals calculate(BigDecimal subtotal, BigDecimal taxRate, BigDecimal amountTendered) {
        BigDecimal sub = Objects.requireNonNullElse(subtotal, BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        BigDecimal rate = Objects.requireNonNullElse(taxRate, BigDecimal.ZERO).setScale(4, RoundingMode.HALF_UP);
        BigDecimal tax = sub.multiply(rate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal total = sub.add(tax).setScale(2, RoundingMode.HALF_UP);
        BigDecimal tendered = Objects.requireNonNullElse(amountTendered, BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        BigDecimal change = tendered.subtract(total).setScale(2, RoundingMode.HALF_UP);
        return new CartTotals(sub, rate, tax, total, tendered, change);
    }

    public static CartTotals calculate(BigDecimal subtotal, BigDecimal amountTendered) {
        return calculate(subtotal, BigDecimal.ZERO, amountTendered);
    }

    public static CartTotals ofSubtotal(BigDecimal subtotal) {
        return calculate(subtotal, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    public boolean isPaidInFull() {
        return amountTendered.compareTo(totalPayable) >= 0;
    }

    public BigDecimal getShortfall() {
        if (isPaidInFull()) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return totalPayable.subtract(amountTendered).setScale(2, RoundingMode.HALF_UP);
    }
}
