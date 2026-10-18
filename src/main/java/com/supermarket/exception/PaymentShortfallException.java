package com.supermarket.exception;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Exception thrown when cash tendered at checkout is less than the total payable amount.
 */
public class PaymentShortfallException extends RuntimeException {
    private final BigDecimal totalPayable;
    private final BigDecimal amountTendered;
    private final BigDecimal shortfall;

    public PaymentShortfallException(BigDecimal totalPayable, BigDecimal amountTendered) {
        super(String.format("Payment tendered (₹%s) is insufficient for total payable (₹%s). Shortfall: ₹%s",
                Objects.requireNonNullElse(amountTendered, BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP),
                Objects.requireNonNullElse(totalPayable, BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP),
                Objects.requireNonNullElse(totalPayable, BigDecimal.ZERO)
                        .subtract(Objects.requireNonNullElse(amountTendered, BigDecimal.ZERO))
                        .setScale(2, RoundingMode.HALF_UP)));
        this.totalPayable = Objects.requireNonNullElse(totalPayable, BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        this.amountTendered = Objects.requireNonNullElse(amountTendered, BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        this.shortfall = this.totalPayable.subtract(this.amountTendered).setScale(2, RoundingMode.HALF_UP);
    }

    public PaymentShortfallException(String message) {
        super(message);
        this.totalPayable = BigDecimal.ZERO;
        this.amountTendered = BigDecimal.ZERO;
        this.shortfall = BigDecimal.ZERO;
    }

    public BigDecimal getTotalPayable() {
        return totalPayable;
    }

    public BigDecimal getAmountTendered() {
        return amountTendered;
    }

    public BigDecimal getShortfall() {
        return shortfall;
    }
}
