package com.supermarket.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Domain record representing a master sales billing transaction.
 */
public record SalesTransaction(
    Long id,
    String serialNumber,
    BigDecimal totalAmount,
    BigDecimal amountTendered,
    BigDecimal changeAmount,
    String cashierName,
    LocalDateTime timestamp
) {
    public SalesTransaction {
        Objects.requireNonNull(serialNumber, "Serial number cannot be null");
        totalAmount = Objects.requireNonNullElse(totalAmount, BigDecimal.ZERO);
        amountTendered = Objects.requireNonNullElse(amountTendered, BigDecimal.ZERO);
        changeAmount = Objects.requireNonNullElse(changeAmount, BigDecimal.ZERO);
        cashierName = (cashierName == null || cashierName.isBlank()) ? "Cashier" : cashierName;
        timestamp = (timestamp == null) ? LocalDateTime.now() : timestamp;
    }
}
