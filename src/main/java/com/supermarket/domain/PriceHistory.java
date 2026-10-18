package com.supermarket.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Domain record representing an audit log entry for managerial price modifications.
 */
public record PriceHistory(
    Long id,
    String itemCode,
    BigDecimal oldPrice,
    BigDecimal newPrice,
    String changedBy,
    LocalDateTime timestamp
) {
    public PriceHistory {
        Objects.requireNonNull(itemCode, "Item code cannot be null");
        oldPrice = Objects.requireNonNullElse(oldPrice, BigDecimal.ZERO);
        newPrice = Objects.requireNonNullElse(newPrice, BigDecimal.ZERO);
        changedBy = (changedBy == null || changedBy.isBlank()) ? "Manager" : changedBy;
        timestamp = (timestamp == null) ? LocalDateTime.now() : timestamp;
    }
}
